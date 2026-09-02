package dev.kstep.render

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.render.image.TriangleRasterizer
import dev.kstep.render.mesh.IsometricProjection
import dev.kstep.render.svg.TriangleSvgWriter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val WIDTH = 800
private const val HEIGHT = 600

// See TriangleRasterizerTest's identical constant for the measured rationale (three real face
// plateaus at >24 000 pixels each vs. ~80 antialiasing-fringe levels at a handful of pixels each
// on this suite's own occt-box.png).
private const val MIN_PLATEAU_PIXELS = 500

/**
 * The real, end-to-end proof: `OcctKernel.makeBox(...)` -> [dev.kstep.geometry.OcctShape.triangulate]
 * -> [IsometricProjection.project] -> [TriangleRasterizer.render]/[TriangleSvgWriter.render],
 * against a real OCCT 7.9.2 install. `TriangleRasterizerTest` proves the same pipeline's
 * math/rendering half against a hand-written fixture, independent of OCCT; this suite is the one
 * that proves the native bridge's output actually flows through unmodified, into BOTH the raster
 * and vector writers.
 *
 * Moved from `kstep-viewer` (`ViewerOcctPipelineTest`) in kSTEP's headless-preview-rendering
 * wave (see docs/adr/ADR-0011-headless-preview-rendering.adoc), and extended with the SVG half.
 *
 * `.config(enabled = available)`, mirroring every other OCCT-gated suite in this repo. The first
 * case below is this module's own `-Pkstep.occt.require=true` guard, reading the
 * `kstep.occt.require` system property `kstep-render/build.gradle.kts` sets from that Gradle
 * property -- mirroring `OcctBridgeSmokeTest`'s identical first case in `kstep-tests`.
 */
class RenderOcctPipelineTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "the OCCT bridge is available when kstep-render's build requires it" {
            if (System.getProperty("kstep.occt.require") == "true") {
                OcctKernel.availability().shouldBeInstanceOf<OcctAvailability.Available>()
            }
        }

        fun nonBackgroundFraction(image: BufferedImage): Double {
            var nonBackground = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if (image.getRGB(x, y) != -1) nonBackground++
                }
            }
            return nonBackground.toDouble() / (image.width.toDouble() * image.height.toDouble())
        }

        fun brightnessHistogram(image: BufferedImage): Map<Int, Int> {
            val histogram = mutableMapOf<Int, Int>()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val rgb = image.getRGB(x, y)
                    if (rgb != -1) {
                        val level = rgb and 0xFF
                        histogram[level] = (histogram[level] ?: 0) + 1
                    }
                }
            }
            return histogram
        }

        "a real OCCT box triangulates, projects, and rasterizes into a plausible isometric image".config(
            enabled = available,
        ) {
            val mesh = OcctKernel.makeBox(10.0, 20.0, 30.0).use { it.triangulate() }
            val triangles = IsometricProjection.project(mesh, WIDTH.toDouble(), HEIGHT.toDouble())
            triangles.isNotEmpty() shouldBe true
            val image = TriangleRasterizer.render(triangles, WIDTH, HEIGHT)

            val fraction = nonBackgroundFraction(image)
            (fraction in 0.15..0.75) shouldBe true
            val plateaus = brightnessHistogram(image).values.count { it >= MIN_PLATEAU_PIXELS }
            (plateaus >= 3) shouldBe true

            val outFile = File("build/sample-output/render/occt-box.png")
            outFile.parentFile.mkdirs()
            ImageIO.write(image, "png", outFile)
            outFile.exists() shouldBe true
            (outFile.length() > 0) shouldBe true
        }

        "a real OCCT box triangulates, projects, and writes into a plausible isometric SVG".config(
            enabled = available,
        ) {
            val mesh = OcctKernel.makeBox(10.0, 20.0, 30.0).use { it.triangulate() }
            val triangles = IsometricProjection.project(mesh, WIDTH.toDouble(), HEIGHT.toDouble())
            val svg = TriangleSvgWriter.render(triangles, WIDTH, HEIGHT)

            svg shouldContain "<svg"
            svg shouldContain "</svg>"
            // A box triangulates to 12 total triangles (2 per face x 6 faces -- see
            // OcctTriangulationTest); the fixed isometric view direction always culls exactly
            // half of them (3 of 6 faces face away from the viewer -- see
            // IsometricProjectionTest's identical unit-cube assertion), leaving 6 visible.
            (svg.split("<polygon").size - 1 >= 6) shouldBe true

            val outFile = File("build/sample-output/render/occt-box.svg")
            outFile.parentFile.mkdirs()
            outFile.writeText(svg)
            outFile.exists() shouldBe true
            (outFile.length() > 0) shouldBe true
        }
    })
