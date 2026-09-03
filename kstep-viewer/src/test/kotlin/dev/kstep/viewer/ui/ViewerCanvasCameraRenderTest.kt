package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.mesh.Camera
import dev.kstep.viewer.camera.ViewerCameraState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.EncodedImageFormat

private const val SCENE_SIZE = 256

/**
 * Drives [ShapeCanvas]'s real Compose draw phase headlessly (same [ImageComposeScene] technique
 * as [ViewerCanvasZeroSizeTest]) with different [ViewerCameraState] values, and compares the
 * rendered pixels -- proves the camera pose/zoom passed via [ShapeCanvas]'s `state` parameter
 * actually reaches [dev.kstep.render.mesh.MeshProjection.project] and changes what gets drawn,
 * end to end through the real Compose layout/draw pipeline, not just through
 * `CameraInteraction`'s pure-function unit tests.
 */
class ViewerCanvasCameraRenderTest :
    StringSpec({
        fun cubeMesh(): TriangleMesh {
            val p0 = doubleArrayOf(0.0, 0.0, 0.0)
            val p1 = doubleArrayOf(1.0, 0.0, 0.0)
            val p2 = doubleArrayOf(1.0, 1.0, 0.0)
            val p3 = doubleArrayOf(0.0, 1.0, 0.0)
            val p4 = doubleArrayOf(0.0, 0.0, 1.0)
            val p5 = doubleArrayOf(1.0, 0.0, 1.0)
            val p6 = doubleArrayOf(1.0, 1.0, 1.0)
            val p7 = doubleArrayOf(0.0, 1.0, 1.0)
            val triangles =
                listOf(
                    Triple(p0, p3, p2),
                    Triple(p0, p2, p1),
                    Triple(p4, p5, p6),
                    Triple(p4, p6, p7),
                    Triple(p0, p4, p7),
                    Triple(p0, p7, p3),
                    Triple(p1, p2, p6),
                    Triple(p1, p6, p5),
                    Triple(p0, p1, p5),
                    Triple(p0, p5, p4),
                    Triple(p3, p6, p2),
                    Triple(p3, p7, p6),
                )
            val coords = DoubleArray(triangles.size * 9)
            triangles.forEachIndexed { i, (a, b, c) ->
                System.arraycopy(a, 0, coords, i * 9, 3)
                System.arraycopy(b, 0, coords, i * 9 + 3, 3)
                System.arraycopy(c, 0, coords, i * 9 + 6, 3)
            }
            return TriangleMesh(coords)
        }

        /**
         * Renders [ShapeCanvas] with the given fixed [cameraState] and returns the encoded PNG
         * bytes of the settled frame.
         *
         * Calls `render()` TWICE, keeping only the second frame's bytes: [ShapeCanvas]'s auto-fit
         * scale is computed via `remember(mesh, canvasSize)`, itself only populated once
         * `Modifier.onSizeChanged` has fired during layout -- on a fresh scene's very first
         * `render()` call this can still be settling, so a single-call capture would risk reading
         * a still-blank (zero-size-guarded) frame. A second call, after that state has already
         * propagated, is unaffected by that startup race and reflects the actually-settled scene
         * -- this is a test-robustness measure, not evidence either way about whether production
         * ever visibly shows that one transient blank frame in a real window.
         */
        fun renderPng(cameraState: ViewerCameraState): ByteArray {
            val scene = ImageComposeScene(width = SCENE_SIZE, height = SCENE_SIZE)
            try {
                scene.setContent {
                    ShapeCanvas(
                        cubeMesh(),
                        Modifier.size(SCENE_SIZE.dp),
                        state = mutableStateOf(cameraState),
                    )
                }
                scene.render()
                val image = scene.render()
                return image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            } finally {
                scene.close()
            }
        }

        "a rotated camera pose renders different pixels than HOME" {
            val homeBytes = renderPng(ViewerCameraState.HOME)
            val rotatedBytes = renderPng(ViewerCameraState(Camera.of(85.0, Camera.ISOMETRIC.elevationDeg), 1.0))
            (homeBytes.contentEquals(rotatedBytes)) shouldBe false
        }

        "a zoomed-in camera renders different pixels than HOME" {
            val homeBytes = renderPng(ViewerCameraState.HOME)
            val zoomedBytes = renderPng(ViewerCameraState(Camera.ISOMETRIC, zoom = 2.0))
            (homeBytes.contentEquals(zoomedBytes)) shouldBe false
        }

        "rendering the same HOME pose twice, independently, produces byte-identical output" {
            val first = renderPng(ViewerCameraState.HOME)
            val second = renderPng(ViewerCameraState.HOME)
            first.contentEquals(second) shouldBe true
        }
    })
