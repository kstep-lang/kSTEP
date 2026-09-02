package dev.kstep.viewer

import dev.kstep.geometry.TriangleMesh
import dev.kstep.viewer.mesh.IsometricProjection
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val WIDTH = 800
private const val HEIGHT = 600

// Minimum pixel count for a brightness level to count as a real, flat-shaded face plateau
// rather than antialiasing fringe. Measured on this suite's own `occt-box-isometric.png`
// companion (`ViewerOcctPipelineTest`): the three genuine face plateaus each cover >24 000
// pixels, while 80 distinct antialiasing-fringe levels sit on the silhouette edge with a
// handful of pixels each -- 500 sits comfortably below the former and well above the latter.
private const val MIN_PLATEAU_PIXELS = 500

/**
 * Headless proof (plain JDK `BufferedImage`/`Graphics2D`, no OCCT, no display) that
 * [IsometricProjection]'s output actually rasterizes into a recognizable, shaded solid -- not
 * just "the math runs", but "a human looking at the PNG this test writes sees a box". See
 * `ViewerOcctPipelineTest` for the real-OCCT end-to-end companion of this suite.
 */
class ViewerRasterTest :
    StringSpec({
        fun unitCubeMesh(): TriangleMesh {
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

        fun nonBackgroundFraction(image: BufferedImage): Double {
            var nonBackground = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if (image.getRGB(x, y) != -1) { // -1 == 0xFFFFFFFF == white
                        nonBackground++
                    }
                }
            }
            return nonBackground.toDouble() / (image.width.toDouble() * image.height.toDouble())
        }

        // Histogram of non-background brightness levels -> pixel count. A raw `Set<Int>` of
        // distinct levels (the original shape of this helper) is USELESS as an "at least three
        // faces are visibly different shades" proof: `TriangleRasterizer` draws with
        // `RenderingHints.VALUE_ANTIALIAS_ON`, so the antialiased silhouette edge against the
        // white background alone contributes dozens of one-off brightness levels with a handful
        // of pixels each. Only levels with a substantial pixel count are real, flat-shaded face
        // plateaus -- see MIN_PLATEAU_PIXELS below and this test's KDoc.
        fun brightnessHistogram(image: BufferedImage): Map<Int, Int> {
            val histogram = mutableMapOf<Int, Int>()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    if (rgb != -1) {
                        val level = rgb and 0xFF // grayscale: R=G=B, low byte suffices
                        histogram[level] = (histogram[level] ?: 0) + 1
                    }
                }
            }
            return histogram
        }

        // R-1, R-3
        "rasterizing the unit cube fills a plausible fraction of the canvas, not empty or solid" {
            val triangles = IsometricProjection.project(unitCubeMesh(), WIDTH.toDouble(), HEIGHT.toDouble())
            val image = TriangleRasterizer.render(triangles, WIDTH, HEIGHT)
            val fraction = nonBackgroundFraction(image)
            (fraction in 0.15..0.75) shouldBe true
        }

        // R-2: counts only brightness levels with a real pixel footprint (see
        // MIN_PLATEAU_PIXELS/brightnessHistogram's KDoc) -- a naive distinct-level count would
        // also pass with a single uniform face shade plus antialiasing fringe, defeating the
        // point of this assertion (see ADR-0010's Stolperfalle 9, the camera-aligned-light bug
        // this test exists to catch).
        "rasterizing the unit cube shows at least three distinguishable brightness plateaus" {
            val triangles = IsometricProjection.project(unitCubeMesh(), WIDTH.toDouble(), HEIGHT.toDouble())
            val image = TriangleRasterizer.render(triangles, WIDTH, HEIGHT)
            val plateaus = brightnessHistogram(image).values.count { it >= MIN_PLATEAU_PIXELS }
            (plateaus >= 3) shouldBe true
        }

        // R-4: written on EVERY run, for human visual inspection -- kUML's SampleOutput
        // convention, introduced into kSTEP for the first time here (see
        // docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc).
        "a PNG of the rasterized unit cube is written for human inspection" {
            val triangles = IsometricProjection.project(unitCubeMesh(), WIDTH.toDouble(), HEIGHT.toDouble())
            val image = TriangleRasterizer.render(triangles, WIDTH, HEIGHT)
            val outFile = File("build/sample-output/viewer/box-isometric.png")
            outFile.parentFile.mkdirs()
            ImageIO.write(image, "png", outFile)
            outFile.exists() shouldBe true
            (outFile.length() > 0) shouldBe true
        }
    })
