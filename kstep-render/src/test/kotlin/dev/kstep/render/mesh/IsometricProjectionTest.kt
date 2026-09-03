package dev.kstep.render.mesh

import dev.kstep.geometry.TriangleMesh
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val CANVAS_WIDTH = 800.0
private const val CANVAS_HEIGHT = 600.0

/**
 * Runs without OCCT -- always active -- against a hand-written, closed unit-cube mesh (12
 * triangles, correct outward winding, verified by hand per triangle -- see this file's own
 * comment on [unitCubeMesh]). Complements `RenderOcctPipelineTest`'s real-OCCT coverage by
 * pinning [MeshProjection]'s pure math independently of the native bridge.
 *
 * Moved from `kstep-viewer` in kSTEP's headless-preview-rendering wave (see
 * docs/adr/ADR-0011-headless-preview-rendering.adoc) -- content unchanged, only the package.
 * Class name kept as `IsometricProjectionTest` (not renamed alongside `MeshProjection` in the
 * later viewer-camera-interaction wave, see docs/adr/ADR-0012-viewer-camera-interaction.adoc):
 * every assertion here still exercises the default camera pose, [Camera.ISOMETRIC], so the name
 * remains accurate to what this file actually tests.
 */
class IsometricProjectionTest :
    StringSpec({
        // A unit cube, vertices at the eight corners of [0,1]^3, twelve triangles with outward
        // winding verified by hand (cross product of (v1-v0) x (v2-v0) checked against each
        // face's known outward normal direction before writing this array) -- see this
        // triangle's comment groups below.
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
                    // bottom (z=0), normal (0,0,-1)
                    Triple(p0, p3, p2),
                    Triple(p0, p2, p1),
                    // top (z=1), normal (0,0,1)
                    Triple(p4, p5, p6),
                    Triple(p4, p6, p7),
                    // left (x=0), normal (-1,0,0)
                    Triple(p0, p4, p7),
                    Triple(p0, p7, p3),
                    // right (x=1), normal (1,0,0)
                    Triple(p1, p2, p6),
                    Triple(p1, p6, p5),
                    // front (y=0), normal (0,-1,0)
                    Triple(p0, p1, p5),
                    Triple(p0, p5, p4),
                    // back (y=1), normal (0,1,0)
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

        "all projected coordinates lie within the canvas bounds" {
            val triangles = MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT)
            triangles.isNotEmpty() shouldBe true
            triangles.forEach { t ->
                listOf(t.ax, t.bx, t.cx).forEach { x -> (x in 0.0..CANVAS_WIDTH) shouldBe true }
                listOf(t.ay, t.by, t.cy).forEach { y -> (y in 0.0..CANVAS_HEIGHT) shouldBe true }
            }
        }

        // exactly three of the cube's six faces (top, right, back -- +Z/+X/+Y) face the fixed
        // isometric viewer; the other three (bottom, left, front) are culled.
        "exactly half the cube's triangles survive backface culling" {
            val triangles = MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT)
            triangles.size shouldBe 6
        }

        "the result is sorted descending by depth" {
            val triangles = MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT)
            val depths = triangles.map { it.depth }
            depths shouldBe depths.sortedDescending()
        }

        "shade values are within range and vary across the cube's three visible faces" {
            val triangles = MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT)
            triangles.forEach { t -> (t.shade in MeshProjection.AMBIENT..1.0) shouldBe true }
            val distinctShades = triangles.map { it.shade }.toSet()
            (distinctShades.size >= 3) shouldBe true
        }

        "flat shading genuinely varies with a triangle's normal" {
            val headOn = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0) // normal (1,1,1)
            val shallow = doubleArrayOf(2.0, 0.0, 0.0, 2.0, 1.0, 0.0, 2.0, 0.0, 1.0) // normal (1,0,0)
            val mesh = TriangleMesh(headOn + shallow)
            val triangles = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            triangles.size shouldBe 2
            val shades = triangles.map { it.shade }.toSet()
            shades.size shouldBe 2
        }

        "an empty mesh projects to an empty list" {
            MeshProjection.project(TriangleMesh(DoubleArray(0)), CANVAS_WIDTH, CANVAS_HEIGHT) shouldBe emptyList()
        }

        "a degenerate (zero-area) triangle is silently discarded" {
            val degenerate = TriangleMesh(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 0.0, 0.0))
            MeshProjection.project(degenerate, CANVAS_WIDTH, CANVAS_HEIGHT) shouldBe emptyList()
        }

        "a non-positive canvas dimension is rejected" {
            shouldThrow<IllegalArgumentException> { MeshProjection.project(unitCubeMesh(), 0.0, CANVAS_HEIGHT) }
            shouldThrow<IllegalArgumentException> { MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, 0.0) }
            shouldThrow<IllegalArgumentException> { MeshProjection.project(unitCubeMesh(), -1.0, CANVAS_HEIGHT) }
        }

        "fit-to-canvas fills most of at least one canvas axis" {
            val triangles = MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT)
            val xs = triangles.flatMap { listOf(it.ax, it.bx, it.cx) }
            val ys = triangles.flatMap { listOf(it.ay, it.by, it.cy) }
            val spanX = (xs.max() - xs.min()) / CANVAS_WIDTH
            val spanY = (ys.max() - ys.min()) / CANVAS_HEIGHT
            val margin = MeshProjection.MARGIN_FRACTION
            val fillsAnAxis = spanX >= (1.0 - 2.0 * margin - 0.01) || spanY >= (1.0 - 2.0 * margin - 0.01)
            fillsAnAxis shouldBe true
            spanX shouldNotBe 0.0
        }
    })
