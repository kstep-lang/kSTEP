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
import dev.kstep.desktop.edit.AtomicSaver
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.export.ExportController
import dev.kstep.desktop.okf.KnowledgeWorkspaceController
import dev.kstep.desktop.run.AiCandidateCompiler
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.UserScriptSandbox
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.run.WorkerSandbox
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import java.awt.Dimension
import java.awt.Frame
import java.nio.file.Path

private const val INITIAL_WIDTH = 1200
private const val INITIAL_HEIGHT = 800
private const val MIN_WIDTH = 640
private const val MIN_HEIGHT = 480

fun runDesktopApp(args: Array<String>) {
    // Same reason as in the CLI: kotlin-logging's start-up banner is noise.
    KotlinLoggingConfiguration.logStartupMessage = false
    val initial = args.firstOrNull()?.let { Path.of(it) }
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
                    saver = { path, bytes, hash, force ->
                        knowledge.guardSave(path)?.let { AtomicSaver.Result.Failed(it) }
                            ?: atomic.save(path, bytes, hash, force)
                    },
                ).also { controller.onDiskSignal = it::onDiskSignal }
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
        Window(
            title =
                state.file?.fileName?.let {
                    (if (editor.dirty) "● " else "") + "${DisplayName.escaped(it.toString())} — kSTEP Desktop"
                }
                    ?: "kSTEP Desktop",
            state = windowState,
            onCloseRequest = {
                // A failed save (or Cancel) keeps the window open: the buffer is never lost.
                if (confirmUnsaved(frame[0], session)) {
                    controller.close()
                    session.close()
                    ai.close()
                    exporter.close()
                    knowledge.close()
                    exitApplication()
                }
            },
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
                initialFile = initial,
                window = window,
                ai = ai,
                exporter = exporter,
                knowledge = knowledge,
                userSandbox = userSandbox.state,
            )
        }
    }
}
