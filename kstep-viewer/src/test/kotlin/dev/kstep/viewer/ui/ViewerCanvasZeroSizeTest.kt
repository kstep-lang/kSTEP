package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.TriangleMesh
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec

/**
 * Regression test for the zero-size guard in [ShapeCanvas]. A Compose layout pass can
 * legitimately hand the `Canvas` draw scope a zero-sized `size` -- e.g. a modifier that
 * (temporarily) collapses to 0 width/height before the surrounding layout settles -- and
 * [ShapeCanvas] must draw nothing for that frame instead of forwarding that size into
 * [dev.kstep.render.mesh.IsometricProjection.project], whose own `require` throws
 * `IllegalArgumentException` for a non-positive canvas size (see `IsometricProjectionTest`) and
 * would otherwise take the whole window down with it (see ADR-0010).
 *
 * Drives the real Compose draw phase headlessly via [ImageComposeScene] (no window, no display
 * server needed -- `ui-desktop`'s `ImageComposeScene` is already on the test classpath via
 * `compose.desktop.currentOs`), instead of unit-testing the guard condition in isolation, so a
 * future refactor that drops the guard or moves it behind the `project` call fails this test the
 * same way it would fail a real window.
 */
class ViewerCanvasZeroSizeTest :
    StringSpec({
        fun singleTriangleMesh(): TriangleMesh {
            val coords =
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                )
            return TriangleMesh(coords)
        }

        "ShapeCanvas draws nothing instead of crashing when collapsed to zero size" {
            val scene = ImageComposeScene(width = 64, height = 64)
            try {
                scene.setContent {
                    ShapeCanvas(singleTriangleMesh(), Modifier.size(0.dp))
                }
                shouldNotThrowAny { scene.render() }
            } finally {
                scene.close()
            }
        }

        "ShapeCanvas renders normally through the same draw phase at a non-zero size" {
            val scene = ImageComposeScene(width = 64, height = 64)
            try {
                scene.setContent {
                    ShapeCanvas(singleTriangleMesh(), Modifier.size(64.dp))
                }
                shouldNotThrowAny { scene.render() }
            } finally {
                scene.close()
            }
        }
    })
