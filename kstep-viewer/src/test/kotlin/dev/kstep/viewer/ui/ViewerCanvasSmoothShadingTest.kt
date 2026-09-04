package dev.kstep.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.TriangleMesh
import dev.kstep.viewer.camera.ViewerCameraState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode
import kotlin.math.sqrt

private const val SCENE_SIZE = 256

/**
 * The Gouraud-shading half of kSTEP's smooth per-vertex normals wave (see
 * docs/adr/ADR-0018-smooth-vertex-normals.adoc): [ShapeCanvas]'s Skia `drawVertices` batch path,
 * end to end through the real Compose draw phase (same [ImageComposeScene] technique
 * [ViewerCanvasCameraRenderTest] already uses), plus a direct-Skia regression pin for the
 * `BlendMode.DST` choice itself (test `cc`) -- see that ADR's Measurements section for how it was
 * determined (against this project's pinned Skiko 0.144.6).
 */
class ViewerCanvasSmoothShadingTest :
    StringSpec({
        fun normalize(
            x: Double,
            y: Double,
            z: Double,
        ): DoubleArray {
            val len = sqrt(x * x + y * y + z * z)
            return doubleArrayOf(x / len, y / len, z / len)
        }

        fun faceNormal(
            v0: DoubleArray,
            v1: DoubleArray,
            v2: DoubleArray,
        ): DoubleArray {
            val ux = v1[0] - v0[0]
            val uy = v1[1] - v0[1]
            val uz = v1[2] - v0[2]
            val wx = v2[0] - v0[0]
            val wy = v2[1] - v0[1]
            val wz = v2[2] - v0[2]
            return normalize(uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx)
        }

        fun cubeTriples(): List<Triple<DoubleArray, DoubleArray, DoubleArray>> {
            val p0 = doubleArrayOf(0.0, 0.0, 0.0)
            val p1 = doubleArrayOf(1.0, 0.0, 0.0)
            val p2 = doubleArrayOf(1.0, 1.0, 0.0)
            val p3 = doubleArrayOf(0.0, 1.0, 0.0)
            val p4 = doubleArrayOf(0.0, 0.0, 1.0)
            val p5 = doubleArrayOf(1.0, 0.0, 1.0)
            val p6 = doubleArrayOf(1.0, 1.0, 1.0)
            val p7 = doubleArrayOf(0.0, 1.0, 1.0)
            return listOf(
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
        }

        fun cubeMesh(): TriangleMesh {
            val triangles = cubeTriples()
            val coords = DoubleArray(triangles.size * 9)
            triangles.forEachIndexed { i, (a, b, c) ->
                System.arraycopy(a, 0, coords, i * 9, 3)
                System.arraycopy(b, 0, coords, i * 9 + 3, 3)
                System.arraycopy(c, 0, coords, i * 9 + 6, 3)
            }
            return TriangleMesh(coords)
        }

        /** The cube, but with a per-vertex normal EQUAL to its own triangle's flat face normal
         *  at every corner -- the "planar equivalence" case (test gg): Gouraud interpolation of
         *  three identical corner normals must reproduce (near enough) the flat-shaded result. */
        fun cubeMeshWithFlatVertexNormals(): TriangleMesh {
            val triangles = cubeTriples()
            val coords = DoubleArray(triangles.size * 9)
            val normals = DoubleArray(triangles.size * 9)
            triangles.forEachIndexed { i, (a, b, c) ->
                System.arraycopy(a, 0, coords, i * 9, 3)
                System.arraycopy(b, 0, coords, i * 9 + 3, 3)
                System.arraycopy(c, 0, coords, i * 9 + 6, 3)
                val n = faceNormal(a, b, c)
                for (corner in 0 until 3) System.arraycopy(n, 0, normals, i * 9 + corner * 3, 3)
            }
            return TriangleMesh(coords, normals)
        }

        /** The cube, with genuinely DIVERGENT per-vertex normals on every triangle (each corner
         *  nudged toward a different direction than its own face normal) -- the "Gouraud wirkt"
         *  case (test ff): must render visibly different pixels than the flat-shaded cube. */
        fun cubeMeshWithDivergentVertexNormals(): TriangleMesh {
            val triangles = cubeTriples()
            val coords = DoubleArray(triangles.size * 9)
            val normals = DoubleArray(triangles.size * 9)
            triangles.forEachIndexed { i, (a, b, c) ->
                System.arraycopy(a, 0, coords, i * 9, 3)
                System.arraycopy(b, 0, coords, i * 9 + 3, 3)
                System.arraycopy(c, 0, coords, i * 9 + 6, 3)
                val n = faceNormal(a, b, c)
                val nudges =
                    listOf(
                        doubleArrayOf(0.3, 0.2, 0.1),
                        doubleArrayOf(-0.2, 0.3, -0.1),
                        doubleArrayOf(0.1, -0.3, 0.2),
                    )
                for (corner in 0 until 3) {
                    val nudge = nudges[corner]
                    val nudged = normalize(n[0] + nudge[0], n[1] + nudge[1], n[2] + nudge[2])
                    System.arraycopy(nudged, 0, normals, i * 9 + corner * 3, 3)
                }
            }
            return TriangleMesh(coords, normals)
        }

        fun renderPng(mesh: TriangleMesh): ByteArray {
            val scene = ImageComposeScene(width = SCENE_SIZE, height = SCENE_SIZE)
            try {
                scene.setContent {
                    ShapeCanvas(
                        mesh,
                        Modifier.size(SCENE_SIZE.dp),
                        state = mutableStateOf(ViewerCameraState.HOME),
                    )
                }
                scene.render()
                val image = scene.render()
                return image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            } finally {
                scene.close()
            }
        }

        fun renderPixels(mesh: TriangleMesh): IntArray {
            val scene = ImageComposeScene(width = SCENE_SIZE, height = SCENE_SIZE)
            try {
                scene.setContent {
                    ShapeCanvas(
                        mesh,
                        Modifier.size(SCENE_SIZE.dp),
                        state = mutableStateOf(ViewerCameraState.HOME),
                    )
                }
                scene.render()
                val image = scene.render()
                val bitmap = Bitmap()
                bitmap.allocN32Pixels(SCENE_SIZE, SCENE_SIZE)
                image.readPixels(bitmap)
                val pixels = IntArray(SCENE_SIZE * SCENE_SIZE)
                for (y in 0 until SCENE_SIZE) {
                    for (x in 0 until SCENE_SIZE) {
                        pixels[y * SCENE_SIZE + x] = bitmap.getColor(x, y)
                    }
                }
                return pixels
            } finally {
                scene.close()
            }
        }

        // cc: direct-Skia regression pin for BlendMode.DST -- see this file's own KDoc and
        // ADR-0018's Measurements section for how this was determined. If a future Skiko upgrade
        // changes drawVertices' blend semantics, THIS test fails first, independently of the full
        // ShapeCanvas pipeline.
        "Skia drawVertices with BlendMode.DST reproduces vertex colors verbatim, ignoring the paint's own color" {
            val scene = ImageComposeScene(width = SCENE_SIZE, height = SCENE_SIZE)
            try {
                var topPixel = 0
                var blPixel = 0
                var brPixel = 0
                scene.setContent {
                    Canvas(Modifier.size(SCENE_SIZE.dp)) {
                        drawIntoCanvas { canvas ->
                            val positions =
                                floatArrayOf(
                                    SCENE_SIZE / 2f,
                                    5f,
                                    5f,
                                    SCENE_SIZE - 5f,
                                    SCENE_SIZE - 5f,
                                    SCENE_SIZE - 5f,
                                )
                            val colors =
                                intArrayOf(
                                    Color.makeRGB(255, 0, 0),
                                    Color.makeRGB(0, 255, 0),
                                    Color.makeRGB(0, 0, 255),
                                )
                            // Deliberately Paint()'s default color (opaque black), NOT an
                            // arbitrary color: this must match ShapeCanvas's own
                            // production paint (`SkiaPaint()`, ViewerCanvas.kt) exactly, or a
                            // blend-mode regression that only misbehaves against a dark paint
                            // (e.g. DST degrading to a src*dst-like MODULATE, which a white paint
                            // would hide -- white * vertex color == vertex color) could pass here
                            // while still breaking production. See this test's KDoc above.
                            val paint = Paint()
                            canvas.skiaCanvas.drawVertices(
                                VertexMode.TRIANGLES,
                                positions,
                                colors,
                                null,
                                null,
                                BlendMode.DST,
                                paint,
                            )
                        }
                    }
                }
                scene.render()
                val image = scene.render()
                val bitmap = Bitmap()
                bitmap.allocN32Pixels(SCENE_SIZE, SCENE_SIZE)
                image.readPixels(bitmap)
                topPixel = bitmap.getColor(SCENE_SIZE / 2, 12)
                blPixel = bitmap.getColor(14, SCENE_SIZE - 12)
                brPixel = bitmap.getColor(SCENE_SIZE - 14, SCENE_SIZE - 12)

                fun channel(
                    pixel: Int,
                    shift: Int,
                ) = (pixel ushr shift) and 0xFF

                // Near-corner sampling (a few px in from each vertex) picks up antialiasing --
                // require the DOMINANT channel to be clearly dominant, not exact equality.
                (channel(topPixel, 16) > 200 && channel(topPixel, 8) < 60 && channel(topPixel, 0) < 60) shouldBe true
                (channel(blPixel, 8) > 200 && channel(blPixel, 16) < 60 && channel(blPixel, 0) < 60) shouldBe true
                (channel(brPixel, 0) > 200 && channel(brPixel, 16) < 60 && channel(brPixel, 8) < 60) shouldBe true
            } finally {
                scene.close()
            }
        }

        // dd: painter's-algorithm order within ONE drawVertices batch call -- a later-listed
        // triangle must win over an earlier one on overlap, exactly like sequential drawPath
        // calls would (see ADR-0018's Measurements section).
        "Skia drawVertices paints later-listed triangles over earlier ones within one batch call" {
            val scene = ImageComposeScene(width = SCENE_SIZE, height = SCENE_SIZE)
            try {
                scene.setContent {
                    Canvas(Modifier.size(SCENE_SIZE.dp)) {
                        drawIntoCanvas { canvas ->
                            val square =
                                floatArrayOf(
                                    5f,
                                    5f,
                                    SCENE_SIZE - 5f,
                                    5f,
                                    5f,
                                    SCENE_SIZE - 5f,
                                    5f,
                                    SCENE_SIZE - 5f,
                                    SCENE_SIZE - 5f,
                                    5f,
                                    SCENE_SIZE - 5f,
                                    SCENE_SIZE - 5f,
                                )
                            val red = Color.makeRGB(255, 0, 0)
                            val blue = Color.makeRGB(0, 0, 255)
                            val colors = intArrayOf(red, red, red, blue, blue, blue)
                            val paint = Paint().apply { color = Color.makeRGB(255, 255, 255) }
                            canvas.skiaCanvas.drawVertices(
                                VertexMode.TRIANGLES,
                                square,
                                colors,
                                null,
                                null,
                                BlendMode.DST,
                                paint,
                            )
                        }
                    }
                }
                scene.render()
                val image = scene.render()
                val bitmap = Bitmap()
                bitmap.allocN32Pixels(SCENE_SIZE, SCENE_SIZE)
                image.readPixels(bitmap)
                val center = bitmap.getColor(SCENE_SIZE / 2, SCENE_SIZE / 2)
                center shouldBe Color.makeARGB(255, 0, 0, 255)
            } finally {
                scene.close()
            }
        }

        // ee: flat fallback (no vertexNormals) is unaffected -- byte-identical across two
        // independent renders, same guarantee ViewerCanvasCameraRenderTest already pins for HOME.
        "a mesh without vertexNormals renders byte-identically across two independent renders" {
            val first = renderPng(cubeMesh())
            val second = renderPng(cubeMesh())
            first.contentEquals(second) shouldBe true
        }

        // ff: Gouraud actually changes the rendered pixels for a mesh with divergent per-vertex
        // normals, versus the same mesh with no vertexNormals at all.
        "a mesh with divergent vertexNormals renders different pixels than the same mesh without" {
            val flatBytes = renderPng(cubeMesh())
            val smoothBytes = renderPng(cubeMeshWithDivergentVertexNormals())
            flatBytes.contentEquals(smoothBytes) shouldBe false
        }

        // gg: a mesh whose vertex normals equal their own face normals renders VISUALLY close to
        // (not byte-identical to -- the drawVertices-vs-drawPath rasterization path itself
        // differs, see this file's own KDoc and ADR-0018's Design-Team-Sitzung) the flat-shaded
        // render: mean absolute per-channel difference stays under 2/255.
        "a mesh whose vertex normals equal their face normals renders visually close to the flat-shaded result" {
            val flatPixels = renderPixels(cubeMesh())
            val planarSmoothPixels = renderPixels(cubeMeshWithFlatVertexNormals())
            flatPixels.size shouldBe planarSmoothPixels.size

            fun channel(
                pixel: Int,
                shift: Int,
            ) = (pixel ushr shift) and 0xFF

            var totalDiff = 0L
            var channelCount = 0L
            for (i in flatPixels.indices) {
                for (shift in intArrayOf(16, 8, 0)) {
                    val diff = kotlin.math.abs(channel(flatPixels[i], shift) - channel(planarSmoothPixels[i], shift))
                    totalDiff += diff
                    channelCount++
                }
            }
            val meanAbsDiff = totalDiff.toDouble() / channelCount.toDouble()
            (meanAbsDiff < 2.0) shouldBe true
        }
    })
