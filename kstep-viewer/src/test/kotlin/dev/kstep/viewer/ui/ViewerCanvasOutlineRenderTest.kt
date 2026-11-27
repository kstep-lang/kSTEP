package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.ColoredMesh
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import dev.kstep.viewer.camera.ViewerCameraState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import kotlin.time.Duration.Companion.seconds

private const val SIZE = 200

/** The selection outline end to end through the real draw phase, both draw paths (ADR-0093). */
class ViewerCanvasOutlineRenderTest :
    StringSpec({
        fun square(normals: Boolean): ColoredMesh {
            val c =
                doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0, 0.0)
            val mesh =
                if (normals) {
                    TriangleMesh(
                        c,
                        DoubleArray(18) {
                            if (it % 3 ==
                                2
                            ) {
                                1.0
                            } else {
                                0.0
                            }
                        },
                    )
                } else {
                    TriangleMesh(c)
                }
            return MeshComposition.mergeColored(listOf(PlacedMesh(mesh, color = MeshColor(0.0, 0.0, 1.0))))
        }

        fun render(
            mesh: ColoredMesh,
            highlight: CanvasHighlight,
        ): Bitmap {
            val image = ImageComposeScene(width = SIZE, height = SIZE)
            try {
                image.setContent {
                    ShapeCanvas(
                        mesh,
                        Modifier.size(SIZE.dp),
                        mutableStateOf(ViewerCameraState.HOME),
                        null,
                        mutableStateOf<CanvasHighlight?>(highlight),
                        null,
                        null,
                    )
                }
                image.render()
                val out = Bitmap()
                out.allocN32Pixels(SIZE, SIZE)
                image.render().readPixels(out)
                return out
            } finally {
                image.close()
            }
        }

        // Pixels in the pure accent colour: the tinted blue fill can reach red 0.45 * 255 at most, never 200.
        fun accentPixels(bitmap: Bitmap): Int {
            var n = 0
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    val p = bitmap.getColor(x, y)
                    if (((p ushr 16) and 0xFF) > 200 && ((p ushr 8) and 0xFF) < 60 && (p and 0xFF) < 60) n++
                }
            }
            return n
        }

        val accent = doubleArrayOf(1.0, 0.0, 0.0)

        for (normals in listOf(true, false)) {
            val path = if (normals) "smooth" else "flat"

            "$path: a selected part gets an accent coloured outline".config(timeout = 60.seconds) {
                val h = CanvasHighlight(intArrayOf(0, 0), 0, -1, accent)
                accentPixels(render(square(normals), h)) shouldBeGreaterThan 100
            }

            "$path: a part that is only hovered gets none".config(timeout = 60.seconds) {
                val h = CanvasHighlight(intArrayOf(0, 0), -1, 0, accent)
                accentPixels(render(square(normals), h)) shouldBe 0
            }

            "$path: a selection over the budget falls back to the tint alone".config(timeout = 60.seconds) {
                val h = CanvasHighlight(intArrayOf(0, 0), 0, -1, accent)
                val saved = SelectionOutline.triangleBudget
                SelectionOutline.triangleBudget = 1
                try {
                    accentPixels(render(square(normals), h)) shouldBe 0
                } finally {
                    SelectionOutline.triangleBudget = saved
                }
            }
        }
    })
