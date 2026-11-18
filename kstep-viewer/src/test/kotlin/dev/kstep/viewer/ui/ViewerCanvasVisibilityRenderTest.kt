package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
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
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import kotlin.time.Duration.Companion.seconds

private const val SIZE = 200
private const val CENTER = SIZE / 2

/**
 * ShapeCanvas with per-part visibility (ADR-0055), end to end through the real draw phase for both draw paths (the
 * smooth Gouraud path with vertex normals, the flat path without). Two parallel squares: part 0 red, part 1 blue. Which
 * of them is nearer depends on the isometric view, so every test first finds the nearer one from an opaque render.
 */
class ViewerCanvasVisibilityRenderTest :
    StringSpec({
        fun square(
            z: Double,
            normals: Boolean,
        ): TriangleMesh {
            val c =
                doubleArrayOf(
                    0.0,
                    0.0,
                    z,
                    1.0,
                    0.0,
                    z,
                    1.0,
                    1.0,
                    z,
                    0.0,
                    0.0,
                    z,
                    1.0,
                    1.0,
                    z,
                    0.0,
                    1.0,
                    z,
                )
            return if (normals) TriangleMesh(c, DoubleArray(18) { if (it % 3 == 2) 1.0 else 0.0 }) else TriangleMesh(c)
        }

        fun scene(normals: Boolean): ColoredMesh =
            MeshComposition.mergeColored(
                listOf(
                    PlacedMesh(square(0.0, normals), color = MeshColor(1.0, 0.0, 0.0)),
                    PlacedMesh(square(0.3, normals), color = MeshColor(0.0, 0.0, 1.0)),
                ),
            )

        // triangle index -> part: 0,1 are part 0 and 2,3 are part 1
        val triangleToPart = intArrayOf(0, 0, 1, 1)

        fun visibility(
            mode0: Byte,
            mode1: Byte,
            alpha: Float = 0.25f,
        ): State<CanvasVisibility?> = mutableStateOf(CanvasVisibility(triangleToPart, byteArrayOf(mode0, mode1), alpha))

        fun render(
            mesh: ColoredMesh,
            visibility: State<CanvasVisibility?>?,
            highlight: State<CanvasHighlight?>? = null,
        ): Bitmap {
            val image = ImageComposeScene(width = SIZE, height = SIZE)
            try {
                image.setContent {
                    val camera = mutableStateOf(ViewerCameraState.HOME)
                    if (highlight == null && visibility == null) {
                        ShapeCanvas(mesh, Modifier.size(SIZE.dp), camera, null)
                    } else {
                        ShapeCanvas(
                            mesh,
                            Modifier.size(SIZE.dp),
                            camera,
                            null,
                            highlight ?: mutableStateOf(null),
                            null,
                            visibility,
                        )
                    }
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

        fun r(p: Int) = (p ushr 16) and 0xFF

        fun g(p: Int) = (p ushr 8) and 0xFF

        fun b(p: Int) = p and 0xFF

        fun centre(bitmap: Bitmap) = bitmap.getColor(CENTER, CENTER)

        for (normals in listOf(true, false)) {
            val path = if (normals) "smooth" else "flat"

            fun frontIsRed(): Boolean {
                val p = centre(render(scene(normals), null))
                return r(p) > b(p)
            }

            fun modes(
                front: Byte,
                back: Byte,
            ): State<CanvasVisibility?> = if (frontIsRed()) visibility(front, back) else visibility(back, front)

            "$path: an opaque part in front of a transparent one covers it completely".config(timeout = 60.seconds) {
                val opaque = centre(render(scene(normals), null))
                val p =
                    centre(
                        render(
                            scene(normals),
                            modes(CanvasVisibility.VISIBLE.toByte(), CanvasVisibility.TRANSPARENT.toByte()),
                        ),
                    )
                (kotlin.math.abs(r(p) - r(opaque)) <= 2 && kotlin.math.abs(b(p) - b(opaque)) <= 2) shouldBe true
            }

            "$path: a transparent part in front of an opaque one blends with it".config(timeout = 60.seconds) {
                val p =
                    centre(
                        render(
                            scene(normals),
                            modes(CanvasVisibility.TRANSPARENT.toByte(), CanvasVisibility.VISIBLE.toByte()),
                        ),
                    )
                // both parts contribute: red from the one, blue from the other
                (r(p) > 20 && b(p) > 20) shouldBe true
            }

            "$path: a hidden part leaves no pixels, the part behind shows".config(timeout = 60.seconds) {
                val front = frontIsRed()
                val hiddenFront =
                    centre(
                        render(
                            scene(normals),
                            modes(CanvasVisibility.HIDDEN.toByte(), CanvasVisibility.VISIBLE.toByte()),
                        ),
                    )
                // the back part has the other colour
                if (front) {
                    (b(hiddenFront) > r(hiddenFront)) shouldBe true
                } else {
                    (r(hiddenFront) > b(hiddenFront)) shouldBe
                        true
                }
                val allHidden =
                    render(
                        scene(normals),
                        visibility(CanvasVisibility.HIDDEN.toByte(), CanvasVisibility.HIDDEN.toByte()),
                    )
                val background = allHidden.getColor(2, 2)
                centre(allHidden) shouldBe background
            }

            "$path: a null visibility draws like before".config(timeout = 60.seconds) {
                val plain = render(scene(normals), null)
                val allVisible =
                    render(
                        scene(normals),
                        visibility(CanvasVisibility.VISIBLE.toByte(), CanvasVisibility.VISIBLE.toByte()),
                    )
                for (x in 0 until SIZE step 7) {
                    for (y in 0 until SIZE step 7) plain.getColor(x, y) shouldBe allVisible.getColor(x, y)
                }
            }

            "$path: the highlight tint stays on a transparent part and does not reach a hidden one".config(
                timeout = 60.seconds,
            ) {
                val front = frontIsRed()
                val frontPart = if (front) 0 else 1

                fun tint(): State<CanvasHighlight?> =
                    mutableStateOf(CanvasHighlight(triangleToPart, frontPart, -1, doubleArrayOf(0.0, 1.0, 0.0)))
                val transparent = modes(CanvasVisibility.TRANSPARENT.toByte(), CanvasVisibility.VISIBLE.toByte())
                val plain = centre(render(scene(normals), transparent))
                val tinted = centre(render(scene(normals), transparent, tint()))
                (g(tinted) > g(plain)) shouldBe true
                val hidden = modes(CanvasVisibility.HIDDEN.toByte(), CanvasVisibility.VISIBLE.toByte())
                centre(render(scene(normals), hidden, tint())) shouldBe centre(render(scene(normals), hidden))
            }
        }

        "a hidden or transparent index out of the table is drawn in full" {
            val v = CanvasVisibility(intArrayOf(0, 7, -1), byteArrayOf(CanvasVisibility.HIDDEN.toByte()), 0.25f)
            v.modeOf(0) shouldBe CanvasVisibility.HIDDEN
            v.modeOf(1) shouldBe CanvasVisibility.VISIBLE
            v.modeOf(2) shouldBe CanvasVisibility.VISIBLE
            v.modeOf(99) shouldBe CanvasVisibility.VISIBLE
            v.modeOf(-1) shouldBe CanvasVisibility.VISIBLE
            v.hasHidden shouldBe true
            CanvasVisibility(intArrayOf(), byteArrayOf(0, 1), Float.NaN).alphaByte shouldBe 64
        }

        "130000 triangles with mixed visibility draw within the time limit".config(timeout = 60.seconds) {
            val n = 130_000
            val coords = DoubleArray(n * 9)
            for (t in 0 until n) {
                val x = (t % 500) * 0.01
                val y = (t / 500) * 0.01
                val o = t * 9
                coords[o] = x
                coords[o + 1] = y
                coords[o + 3] = x + 0.01
                coords[o + 4] = y
                coords[o + 6] = x
                coords[o + 7] = y + 0.01
            }
            val mesh =
                MeshComposition.mergeColored(
                    listOf(PlacedMesh(TriangleMesh(coords), color = MeshColor(0.5, 0.5, 0.5))),
                )
            val mapping = IntArray(n) { it % 3 }
            val v = mutableStateOf<CanvasVisibility?>(CanvasVisibility(mapping, byteArrayOf(0, 1, 2), 0.3f))
            val bitmap = render(mesh, v)
            bitmap.width shouldBe SIZE
        }
    })
