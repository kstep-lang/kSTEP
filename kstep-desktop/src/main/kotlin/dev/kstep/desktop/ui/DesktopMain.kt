package dev.kstep.desktop.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.kstep.desktop.run.LiveRunController
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import java.awt.Dimension
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
        val state by controller.state.collectAsState()
        val windowState = rememberWindowState(size = DpSize(INITIAL_WIDTH.dp, INITIAL_HEIGHT.dp))
        Window(
            title = "kSTEP Desktop" + (state.file?.fileName?.let { " — $it" } ?: ""),
            state = windowState,
            onCloseRequest = {
                controller.close()
                exitApplication()
            },
        ) {
            window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT)
            DesktopApp(controller = controller, initialFile = initial, window = window)
        }
    }
}
