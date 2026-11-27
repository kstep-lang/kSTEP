package dev.kstep.desktop.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.kstep.cli.WorkerTempDir
import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.aiwire.AiController
import dev.kstep.desktop.draft.DraftStore
import dev.kstep.desktop.drop.StartArgument
import dev.kstep.desktop.edit.AtomicSaver
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.export.ExportController
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.okf.KnowledgeWorkspaceController
import dev.kstep.desktop.run.AiCandidateCompiler
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.UserScriptSandbox
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.run.WorkerSandbox
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import javax.swing.SwingUtilities

private const val INITIAL_WIDTH = 1200
private const val INITIAL_HEIGHT = 800
private const val MIN_WIDTH = 640
private const val MIN_HEIGHT = 480

private val logger = KotlinLogging.logger {}

fun runDesktopApp(args: Array<String>) {
    // Must run before the first AWT or Compose use: the menu bar goes to the macOS system menu, named "kSTEP Desktop".
    if (MenuOs.current() == MenuOs.MAC) {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
        System.setProperty("apple.awt.application.name", "kSTEP Desktop")
    }
    // Same reason as in the CLI: kotlin-logging's start-up banner is noise.
    KotlinLoggingConfiguration.logStartupMessage = false
    val initial = StartArgument.parse(args)
    application {
        // Knowledge workspace (ADR-0058): own session directory and worker; it routes a note to its block runner.
        // The sandbox of the user's own flows (ADR-0062), probed once at the first run; the AI check keeps its own.
        val userSandbox = remember { UserScriptSandbox.production() }
        val knowledge = remember { KnowledgeWorkspaceController.production(userSandbox) }
        val controller =
            remember {
                LiveRunController
                    .production(
                        userSandbox,
                    ) { file -> if (knowledge.isWorkspaceFile(file)) knowledge::runBlock else null }
                    .also {
                        it.runFilter = knowledge::runFilter
                        knowledge.onRunRequested = it::forceRun
                    }
            }
        val session =
            remember {
                // The one watcher of the run controller also tells the editor about disk changes (ADR-0052). The saver
                // refuses a workspace note that became a link or left the workspace (ADR-0058, D13).
                val atomic = AtomicSaver()
                EditorSession(
                    drafts = DraftStore(),
                    saver = { path, bytes, hash, force ->
                        knowledge.guardSave(path)?.let { AtomicSaver.Result.Failed(it) }
                            ?: atomic.save(path, bytes, hash, force)
                    },
                ).also {
                    controller.onDiskSignal = it::onDiskSignal
                    // SIGTERM, log-out, shutdown: one last draft write of an unsaved buffer (ADR-0084)
                    Runtime.getRuntime().addShutdownHook(Thread({ it.flushForShutdown() }, "kstep-draft-flush-hook"))
                }
            }
        val ai =
            remember {
                // An own session directory and worker for the candidate check: the live preview is never disturbed.
                val dir = LiveRunController.createSessionDir()
                Runtime.getRuntime().addShutdownHook(
                    Thread({ WorkerTempDir.deleteRecursively(dir) }, "kstep-ai-cleanup"),
                )
                // The candidate check runs only inside an OS sandbox (ADR-0059); detected once, off the UI thread.
                val detected = lazy { WorkerSandbox.detect(dir, dir) }
                val runner = lazy { WorkerRunner(dir, sandbox = detected.value) }
                AiController.production(
                    AiCandidateCompiler(dir, sandbox = { detected.value }) { file, gen, started ->
                        runner.value.run(file, gen, started)
                    },
                    sandbox = { detected.value },
                )
            }
        // Own session directory and worker for exports: the live preview is never disturbed (ADR-0057).
        val exporter = remember { ExportController.production(userSandbox) }
        val state by controller.state.collectAsState()
        val editor by session.view.collectAsState()
        val frame = remember { arrayOfNulls<Frame>(1) }
        val windowState = rememberWindowState(size = DpSize(INITIAL_WIDTH.dp, INITIAL_HEIGHT.dp))
        // A failed save (or Cancel) keeps the window open: the buffer is never lost. Used by the close button, File > Quit
        // and the macOS Cmd+Q quit handler alike.
        val requestQuit: () -> Boolean = {
            if (confirmUnsaved(frame[0], session)) {
                // the draft of what the user just saved or discarded must not be written again by the shutdown hook
                session.finishForQuit()
                controller.close()
                session.close()
                ai.close()
                exporter.close()
                knowledge.close()
                exitApplication()
                true
            } else {
                false
            }
        }
        remember { installMacQuitHandler(requestQuit) }
        Window(
            title =
                state.file?.fileName?.let {
                    (if (editor.dirty) "● " else "") + "${DisplayName.escaped(it.toString())} — kSTEP Desktop"
                }
                    ?: "kSTEP Desktop",
            state = windowState,
            onCloseRequest = { requestQuit() },
        ) {
            window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
            frame[0] = window
            SideEffect {
                // The macOS "edited" dot in the close button; other platforms ignore the property.
                window.rootPane.putClientProperty("Window.documentModified", editor.dirty)
            }
            DesktopApp(
                controller = controller,
                session = session,
                initialRequest = initial,
                window = window,
                ai = ai,
                exporter = exporter,
                knowledge = knowledge,
                onQuit = { requestQuit() },
                userSandbox = userSandbox.state,
            )
        }
    }
}

/**
 * Cmd+Q on macOS goes to the application, not to the window: without this handler it would exit at once and lose
 * unsaved text. The handler runs on a non-UI thread, so the question (a Swing dialog) is asked on the event thread.
 * Registered on macOS only; a failure is logged, never swallowed.
 */
private fun installMacQuitHandler(requestQuit: () -> Boolean) {
    if (MenuOs.current() != MenuOs.MAC) return
    try {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            logger.warn { "No macOS quit handler available: Cmd+Q will not ask about unsaved changes." }
            return
        }
        Desktop.getDesktop().setQuitHandler { _, response ->
            val quit =
                if (SwingUtilities.isEventDispatchThread()) {
                    requestQuit()
                } else {
                    var result = false
                    SwingUtilities.invokeAndWait { result = requestQuit() }
                    result
                }
            // exitApplication() has already started the shutdown when quit is true; performQuit is harmless then.
            if (quit) response.performQuit() else response.cancelQuit()
        }
    } catch (e: UnsupportedOperationException) {
        logger.warn(e) { "Could not install the macOS quit handler." }
    } catch (e: SecurityException) {
        logger.warn(e) { "Could not install the macOS quit handler." }
    }
}

/**
 * Routes the macOS application-menu "About" entry to [onAbout]. Registered on macOS only. About is in the Help menu on
 * all systems; the handler adds the application-menu entry on macOS. The result only says whether it was installed (for
 * logging). The handler runs off the UI thread: [onAbout] must only set Compose state.
 */
internal fun installMacAboutHandler(onAbout: () -> Unit): Boolean {
    if (MenuOs.current() != MenuOs.MAC) return false
    return try {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
            logger.warn { "No macOS about handler available: About is only in the Help menu." }
            false
        } else {
            Desktop.getDesktop().setAboutHandler { onAbout() }
            true
        }
    } catch (e: UnsupportedOperationException) {
        logger.warn(e) { "Could not install the macOS about handler." }
        false
    } catch (e: SecurityException) {
        logger.warn(e) { "Could not install the macOS about handler." }
        false
    }
}
