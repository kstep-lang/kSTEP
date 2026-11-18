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
import dev.kstep.desktop.aiwire.AiController
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.run.AiCandidateCompiler
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.WorkerRunner
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
        val controller = remember { LiveRunController.production() }
        val session =
            remember {
                // The one watcher of the run controller also tells the editor about disk changes (ADR-0052).
                EditorSession().also { controller.onDiskSignal = it::onDiskSignal }
            }
        val ai =
            remember {
                // An own session directory and worker for the candidate check: the live preview is never disturbed.
                val dir = LiveRunController.createSessionDir()
                Runtime.getRuntime().addShutdownHook(
                    Thread({ WorkerTempDir.deleteRecursively(dir) }, "kstep-ai-cleanup"),
                )
                val runner = WorkerRunner(dir)
                AiController.production(
                    AiCandidateCompiler(dir) { file, gen, started -> runner.run(file, gen, started) },
                )
            }
        val state by controller.state.collectAsState()
        val editor by session.view.collectAsState()
        val frame = remember { arrayOfNulls<Frame>(1) }
        val windowState = rememberWindowState(size = DpSize(INITIAL_WIDTH.dp, INITIAL_HEIGHT.dp))
        Window(
            title =
                state.file?.fileName?.let { (if (editor.dirty) "● " else "") + "$it — kSTEP Desktop" }
                    ?: "kSTEP Desktop",
            state = windowState,
            onCloseRequest = {
                // A failed save (or Cancel) keeps the window open: the buffer is never lost.
                if (confirmUnsaved(frame[0], session)) {
                    controller.close()
                    session.close()
                    ai.close()
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
            DesktopApp(controller = controller, session = session, initialFile = initial, window = window, ai = ai)
        }
    }
}
