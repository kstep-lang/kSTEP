package dev.kstep.render.mesh

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val CANVAS_WIDTH = 800.0
private const val CANVAS_HEIGHT = 600.0

/**
 * Proves that kSTEP's "merge first, project once" multi-shape approach (see
 * `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`) actually produces a bigger,
 * differently-shaped silhouette than a single part -- [MeshProjection] itself needed NO change to
 * support this (it only ever sees one [TriangleMesh]); this test is what proves that claim rather
 * than just asserting it.
 */
class MultiShapeProjectionTest :
    StringSpec({
        // A minimal, hand-built "box": two triangles forming one unit-square face facing the
        // isometric viewer's +Z side (normal (0,0,1)) -- enough to survive backface culling
        // without needing a full 12-triangle cube for this test's purposes.
        fun unitSquareMesh(): TriangleMesh =
            TriangleMesh(
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    1.0,
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    1.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                ),
            )

        "a merged two-box scene has strictly more triangles than a single box" {
            val singleMesh = unitSquareMesh()
            val singleTriangles = MeshProjection.project(singleMesh, CANVAS_WIDTH, CANVAS_HEIGHT)

            val merged =
                MeshComposition.merge(
                    listOf(
                        PlacedMesh(unitSquareMesh()),
                        PlacedMesh(unitSquareMesh(), Placement.translation(5.0, 0.0, 0.0)),
                    ),
                )
            val mergedTriangles = MeshProjection.project(merged, CANVAS_WIDTH, CANVAS_HEIGHT)

            mergedTriangles.size shouldBe (singleTriangles.size * 2)
        }

        "two widely-separated parts project to disjoint x-ranges on the canvas" {
            val merged =
                MeshComposition.merge(
                    listOf(
                        PlacedMesh(unitSquareMesh()),
                        PlacedMesh(unitSquareMesh(), Placement.translation(50.0, 0.0, 0.0)),
                    ),
                )
            val triangles = MeshProjection.project(merged, CANVAS_WIDTH, CANVAS_HEIGHT)
            // Two triangles per unitSquareMesh() part -- see "a merged two-box scene..." above.
            triangles.size shouldBe 4

            // Depth-sorting (painter's algorithm) does not group a part's own triangles at fixed
            // list indices, so this does not assume "triangles[0]/[1] are part A" -- instead it
            // sorts every vertex's x-coordinate across the WHOLE merged scene and looks for a
            // single large gap. Two genuinely disjoint silhouettes produce exactly that: a wide
            // gap between the two clusters and only small, within-part gaps inside each cluster.
            val allXs = triangles.flatMap { listOf(it.ax, it.bx, it.cx) }.sorted()
            val totalSpan = allXs.last() - allXs.first()
            val maxGap = (1 until allXs.size).maxOf { allXs[it] - allXs[it - 1] }
            (maxGap > totalSpan * 0.3) shouldBe true
        }

        "auto-fit for a merged scene encloses both parts within the canvas margin" {
            val merged =
                MeshComposition.merge(
                    listOf(
                        PlacedMesh(unitSquareMesh()),
                        PlacedMesh(unitSquareMesh(), Placement.translation(3.0, 0.0, 0.0)),
                    ),
                )
            val triangles = MeshProjection.project(merged, CANVAS_WIDTH, CANVAS_HEIGHT)
            triangles.forEach { t ->
                listOf(t.ax, t.bx, t.cx).forEach { x -> (x in 0.0..CANVAS_WIDTH) shouldBe true }
                listOf(t.ay, t.by, t.cy).forEach { y -> (y in 0.0..CANVAS_HEIGHT) shouldBe true }
            }
        }

        "project(ColoredMesh, ...) at all-NEUTRAL colors is exactly identical to project(TriangleMesh, ...)" {
            val merged =
                MeshComposition.merge(
                    listOf(
                        PlacedMesh(unitSquareMesh()),
                        PlacedMesh(unitSquareMesh(), Placement.translation(5.0, 0.0, 0.0)),
                    ),
                )
            val coloredMerged =
                MeshComposition.mergeColored(
                    listOf(
                        PlacedMesh(unitSquareMesh()),
                        PlacedMesh(unitSquareMesh(), Placement.translation(5.0, 0.0, 0.0)),
                    ),
                )
            val plain = MeshProjection.project(merged, CANVAS_WIDTH, CANVAS_HEIGHT)
            val colored = MeshProjection.project(coloredMerged, CANVAS_WIDTH, CANVAS_HEIGHT)
            plain.size shouldBe colored.size
            plain.zip(colored).forEach { (a, b) ->
                a.ax shouldBe b.ax
                a.ay shouldBe b.ay
                a.shade shouldBe b.shade
                a.color shouldBe MeshColor.NEUTRAL
                b.color shouldBe MeshColor.NEUTRAL
            }
        }

        "project(ColoredMesh, ...) carries each triangle's own color from its source part" {
            val redPart = PlacedMesh(unitSquareMesh(), color = MeshColor(0.9, 0.1, 0.1))
            val bluePart = PlacedMesh(unitSquareMesh(), Placement.translation(50.0, 0.0, 0.0), MeshColor(0.1, 0.1, 0.9))
            val coloredMerged = MeshComposition.mergeColored(listOf(redPart, bluePart))
            val projected = MeshProjection.project(coloredMerged, CANVAS_WIDTH, CANVAS_HEIGHT)

            val colorsUsed = projected.map { it.color }.toSet()
            colorsUsed shouldBe setOf(redPart.color, bluePart.color)
            projected.forEach { t -> t.color shouldNotBe MeshColor.NEUTRAL }
        }
    })
