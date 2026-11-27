package dev.kstep.viewer.ui

import dev.kstep.render.mesh.ProjectedTriangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The selection silhouette (ADR-0093): pure logic on projected triangles, no Compose. */
class SelectionOutlineTest :
    StringSpec({
        val accent = doubleArrayOf(0.0, 0.0, 1.0)

        fun tri(
            ax: Double,
            ay: Double,
            bx: Double,
            by: Double,
            cx: Double,
            cy: Double,
            source: Int,
        ) = ProjectedTriangle(ax, ay, bx, by, cx, cy, shade = 1.0, depth = 0.0, sourceIndex = source)

        /** A fan of [n] triangles around (cx, cy) with radius 100, source indices from [first]. */
        fun fan(
            n: Int,
            first: Int = 0,
            cx: Double = 0.0,
            cy: Double = 0.0,
        ): List<ProjectedTriangle> =
            (0 until n).map { i ->
                val a0 = 2 * PI * i / n
                val a1 = 2 * PI * (i + 1) / n
                tri(cx, cy, cx + 100 * cos(a0), cy + 100 * sin(a0), cx + 100 * cos(a1), cy + 100 * sin(a1), first + i)
            }

        fun highlight(
            map: IntArray,
            selected: Int = 0,
            hovered: Int = -1,
            mask: BooleanArray? = null,
        ) = CanvasHighlight(map, selected, hovered, accent, selectedMask = mask)

        fun segments(r: OutlineResult): List<List<Float>> {
            val s = r.shouldBeInstanceOf<OutlineResult.Segments>()
            return (0 until s.count).map { i -> (0 until 4).map { s.points[i * 4 + it] } }
        }

        fun run(
            tris: List<ProjectedTriangle>,
            h: CanvasHighlight,
            v: CanvasVisibility? = null,
            scratch: OutlineScratch = OutlineScratch(),
        ) = SelectionOutline.compute(tris, h, v, scratch)

        "a hexagon of six triangles gives its six outer edges and no spoke" {
            val r = run(fan(6), highlight(IntArray(6)))
            val segs = segments(r)
            segs.size shouldBe 6
            segs.none { it[0] == 0f && it[1] == 0f || it[2] == 0f && it[3] == 0f } shouldBe true
        }

        "only the selected part is outlined" {
            val tris = fan(6) + fan(6, first = 6, cx = 500.0)
            val r = run(tris, highlight(IntArray(12) { if (it < 6) 0 else 1 }, selected = 1))
            val segs = segments(r)
            segs.size shouldBe 6
            segs.all { it[0] > 300f && it[2] > 300f } shouldBe true
        }

        "two selected neighbours each keep the shared edge, once per part" {
            val a = tri(0.0, 0.0, 100.0, 0.0, 0.0, 100.0, 0)
            val b = tri(100.0, 0.0, 100.0, 100.0, 0.0, 100.0, 1)
            val map = intArrayOf(0, 1)
            val both = segments(run(listOf(a, b), highlight(map, selected = -1, mask = booleanArrayOf(true, true))))
            both.size shouldBe 6
            val shared = both.count { it == listOf(100f, 0f, 0f, 100f) || it == listOf(0f, 100f, 100f, 0f) }
            shared shouldBe 2
        }

        "hover alone gives no outline" {
            run(fan(6), highlight(IntArray(6), selected = -1, hovered = 0)) shouldBe OutlineResult.None
        }

        "nothing selected gives none" {
            run(fan(6), highlight(IntArray(6), selected = 3)) shouldBe OutlineResult.None
        }

        "a hidden selected part gets no outline, a transparent one does" {
            val h = highlight(IntArray(6))
            val hidden = CanvasVisibility(IntArray(6), byteArrayOf(CanvasVisibility.HIDDEN.toByte()), 0.25f)
            val ghost = CanvasVisibility(IntArray(6), byteArrayOf(CanvasVisibility.TRANSPARENT.toByte()), 0.25f)
            run(fan(6), h, hidden) shouldBe OutlineResult.None
            segments(run(fan(6), h, ghost)).size shouldBe 6
        }

        "NaN, infinite and huge triangles are skipped without failing" {
            val bad =
                listOf(
                    tri(Double.NaN, 0.0, 1.0, 0.0, 0.0, 1.0, 0),
                    tri(0.0, 0.0, Double.POSITIVE_INFINITY, 0.0, 0.0, 1.0, 1),
                    tri(0.0, 0.0, 1e12, 0.0, 0.0, 1.0, 2),
                    tri(0.0, 0.0, Double.NEGATIVE_INFINITY, 5.0, 0.0, 1.0, 3),
                )
            run(bad, highlight(IntArray(4))) shouldBe OutlineResult.None
            // A good triangle next to the bad ones is still outlined.
            segments(run(bad + tri(0.0, 0.0, 10.0, 0.0, 0.0, 10.0, 4), highlight(IntArray(5)))).size shouldBe 3
        }

        "degenerate triangles never fail" {
            val collinear = tri(0.0, 0.0, 5.0, 5.0, 10.0, 10.0, 0)
            val point = tri(3.0, 3.0, 3.0, 3.0, 3.0, 3.0, 1)
            run(listOf(collinear, point), highlight(IntArray(2))).shouldBeInstanceOf<OutlineResult.Segments>()
        }

        "a source index outside the table or below zero is not selected" {
            val tris = listOf(tri(0.0, 0.0, 10.0, 0.0, 0.0, 10.0, -1), tri(0.0, 0.0, 10.0, 0.0, 0.0, 10.0, 99))
            run(tris, highlight(IntArray(2))) shouldBe OutlineResult.None
        }

        "a part index below zero is not selected" {
            val h = CanvasHighlight(intArrayOf(-1), -1, -1, accent, selectedLast = 5)
            run(listOf(tri(0.0, 0.0, 10.0, 0.0, 0.0, 10.0, 0)), h) shouldBe OutlineResult.None
        }

        "a selection over the triangle budget falls back" {
            val n = SelectionOutline.MAX_SELECTED_TRIANGLES + 1
            val tris = (0 until n).map { tri(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, it) }
            val r = run(tris, highlight(IntArray(n)))
            r.shouldBeInstanceOf<OutlineResult.Fallback>().reason shouldBe OutlineResult.Reason.OVER_TRIANGLE_BUDGET
        }

        "more open edges than the cap fall back" {
            val n = 6000
            val tris = (0 until n).map { tri(it * 3.0, 0.0, it * 3.0 + 1, 0.0, it * 3.0, 1.0, it) }
            val r = run(tris, highlight(IntArray(n)))
            r.shouldBeInstanceOf<OutlineResult.Fallback>().reason shouldBe OutlineResult.Reason.OVER_EDGE_CAP
        }

        "a probe limit of one falls back once two edges collide" {
            val n = 4000
            val tris = (0 until n).map { tri(it * 3.0, 0.0, it * 3.0 + 1, 0.0, it * 3.0, 1.0, it) }
            val r = SelectionOutline.compute(tris, highlight(IntArray(n)), null, OutlineScratch(), maxProbes = 1)
            r.shouldBeInstanceOf<OutlineResult.Fallback>().reason shouldBe OutlineResult.Reason.PROBE_LIMIT
        }

        "a reused scratch gives the same result every time" {
            val scratch = OutlineScratch()
            val tris = fan(6)
            val h = highlight(IntArray(6))
            val first = segments(run(tris, h, scratch = scratch))
            repeat(100) { segments(run(tris, h, scratch = scratch)) shouldBe first }
            // A smaller run afterwards sees nothing of the bigger one.
            segments(run(tris.take(1), highlight(IntArray(1)), scratch = scratch)).size shouldBe 3
        }

        "a sub-pixel gap between neighbours does not make a false silhouette" {
            val a = tri(0.0, 0.0, 100.0, 0.0, 0.0, 100.0, 0)
            val b = tri(100.0 + 0.01, 0.0 - 0.01, 100.0, 100.0, 0.0, 100.0, 0)
            // Same part: the shared edge cancels although one corner is off by 0.01 px (quantised to 1/16 px).
            val segs = segments(run(listOf(a, b), highlight(IntArray(1))))
            segs.size shouldBe 4
        }

        "the outline lowers the selection tint, the fallback keeps it" {
            val h = highlight(IntArray(6))
            h.tintFor(0, h.outlineSelectT) shouldBe CanvasHighlight.DEFAULT_OUTLINE_SELECT_T
            h.tintFor(0) shouldBe CanvasHighlight.DEFAULT_SELECT_T
            val hover = highlight(IntArray(6), selected = -1, hovered = 0)
            hover.tintFor(0, 0.3) shouldBe CanvasHighlight.DEFAULT_HOVER_T
        }

        "the selected triangle count follows range and mask" {
            highlight(intArrayOf(0, 0, 1, 2, -1)).selectedTriangleCount shouldBe 2
            highlight(intArrayOf(0, 0, 1, 2, -1), selected = -1, mask = booleanArrayOf(false, true, true))
                .selectedTriangleCount shouldBe 2
        }
    })
