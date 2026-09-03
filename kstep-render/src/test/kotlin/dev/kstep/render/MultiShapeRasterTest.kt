package dev.kstep.render

import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.image.TriangleRasterizer
import dev.kstep.render.mesh.MeshProjection
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val WIDTH = 1200
private const val HEIGHT = 600

/**
 * Headless proof (plain JDK `BufferedImage`, no OCCT, no display -- see `TriangleRasterizerTest`'s
 * own KDoc for the same rationale) that a MERGED two-part scene (see `MeshComposition.merge`,
 * `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`) rasterizes as two visually
 * SEPARATE solids, not one run-together blob: a horizontal scanline through the middle of the
 * canvas must cross background->foreground at least twice.
 */
class MultiShapeRasterTest :
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

        // Two unit cubes, separated along world (+X, -Y, 0) -- NOT plain (+X, 0, 0). Under the
        // fixed ISOMETRIC camera, a pure world-X offset shifts a silhouette diagonally on screen
        // (both screen-x AND screen-y move, since ISOMETRIC's `right`/`trueUp` basis vectors both
        // have a nonzero X component) -- verified by hand computation before choosing this
        // direction: (+X, 0, 0) put the two cubes in diagonally opposite canvas corners with NO
        // shared horizontal scanline at all (0 transitions, not >= 2). `(dx, -dx, 0)` is
        // orthogonal to ISOMETRIC's `trueUp` (dot product exactly 0), so it shifts the second
        // cube purely along screen-x, keeping both cubes on the same horizontal band -- exactly
        // what a horizontal-scanline test needs.
        fun twoCubesMerged(): TriangleMesh =
            MeshComposition.merge(
                listOf(
                    PlacedMesh(unitCubeMesh()),
                    PlacedMesh(unitCubeMesh(), Placement.translation(6.0, -6.0, 0.0)),
                ),
            )

        fun countTransitions(
            image: BufferedImage,
            row: Int,
        ): Int {
            var transitions = 0
            var wasBackground = true
            for (x in 0 until image.width) {
                val isBackground = image.getRGB(x, row) == -1
                if (wasBackground && !isBackground) {
                    transitions++
                }
                wasBackground = isBackground
            }
            return transitions
        }

        "a merged two-cube scene shows at least two background-to-foreground transitions on a middle scanline" {
            val triangles = MeshProjection.project(twoCubesMerged(), WIDTH.toDouble(), HEIGHT.toDouble())
            val image = TriangleRasterizer.render(triangles, WIDTH, HEIGHT)
            val transitions = countTransitions(image, HEIGHT / 2)
            (transitions >= 2) shouldBe true
        }

        "a single cube (no merge) shows exactly one background-to-foreground transition on the same scanline" {
            // Companion/control case: proves the >= 2 transitions above genuinely come from TWO
            // separate solids, not from some artifact of the rasterizer/projection pipeline that
            // would produce multiple transitions even for one shape.
            val triangles = MeshProjection.project(unitCubeMesh(), WIDTH.toDouble(), HEIGHT.toDouble())
            val image = TriangleRasterizer.render(triangles, WIDTH, HEIGHT)
            val transitions = countTransitions(image, HEIGHT / 2)
            transitions shouldBe 1
        }

        "a PNG of the merged two-cube scene is written for human inspection" {
            val triangles = MeshProjection.project(twoCubesMerged(), WIDTH.toDouble(), HEIGHT.toDouble())
            val image = TriangleRasterizer.render(triangles, WIDTH, HEIGHT)
            val outFile = File("build/sample-output/render/multi-shape-two-cubes.png")
            outFile.parentFile.mkdirs()
            ImageIO.write(image, "png", outFile)
            outFile.exists() shouldBe true
            (outFile.length() > 0) shouldBe true
        }
    })
