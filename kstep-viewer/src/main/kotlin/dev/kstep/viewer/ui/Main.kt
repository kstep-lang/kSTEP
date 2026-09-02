package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel

/**
 * Opens a real, on-screen window showing a static isometric box. Opt-in only -- run explicitly
 * via `./gradlew :kstep-viewer:run`, never as part of `check` (see `kstep-viewer/build.gradle.kts`
 * and docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc).
 */
fun main() =
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "kSTEP Viewer",
            state = rememberWindowState(width = 900.dp, height = 700.dp),
        ) {
            when (val availability = OcctKernel.availability()) {
                is OcctAvailability.Available -> {
                    // makeBox(10, 20, 30) -- use { } closes the OcctShape right after
                    // triangulation; the resulting TriangleMesh is plain JVM data and survives
                    // that close.
                    //
                    // runCatching, not a bare call: OCCT being reported Available does not
                    // guarantee triangulate() succeeds (OcctGeometryException from a
                    // Standard_Failure, or IllegalArgumentException from the MAX_TRIANGLES guard
                    // once a future wave lets an arbitrary/imported shape reach this path). Left
                    // unguarded, that exception unwinds out of composition and kills the whole
                    // window with a stack trace -- exactly the unexplained-failure state
                    // UnavailableNotice exists to prevent, just reached from the Available branch
                    // instead of the Unavailable one.
                    val meshResult =
                        remember { runCatching { OcctKernel.makeBox(10.0, 20.0, 30.0).use { it.triangulate() } } }
                    meshResult.fold(
                        onSuccess = { mesh -> ShapeCanvas(mesh, Modifier.fillMaxSize()) },
                        onFailure = { error ->
                            UnavailableNotice(
                                reason = error.message ?: error.toString(),
                                modifier = Modifier.fillMaxSize(),
                                heading = "kSTEP Viewer could not triangulate the demo shape.",
                                showInstallInstructions = false,
                            )
                        },
                    )
                }
                is OcctAvailability.Unavailable -> UnavailableNotice(availability.reason, Modifier.fillMaxSize())
            }
        }
    }
