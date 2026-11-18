package dev.kstep.preview

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.Placement
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** ADR-0053: source-mesh part spans become GLB triangle ranges, after the writer dropped some triangles. */
class MapSpansToGlbTest :
    StringSpec({
        fun span(
            name: String,
            first: Int,
            count: Int,
        ) = PartSpan(name, first, count, Placement.IDENTITY, MeshColor.NEUTRAL)

        fun ranges(parts: List<RenderedPart>) = parts.map { Triple(it.name, it.firstTriangle, it.triangleCount) }

        "without dropped triangles the ranges stay as they are" {
            val spans = listOf(span("a", 0, 3), span("b", 3, 2))
            val parts = mapSpansToGlb(spans, IntArray(5) { it })
            ranges(parts) shouldBe listOf(Triple("a", 0, 3), Triple("b", 3, 2))
        }

        "dropped triangles shift the following ranges and shrink their own" {
            // source triangles 1 (in a) and 3 (in b) were dropped
            val spans = listOf(span("a", 0, 3), span("b", 3, 3))
            val parts = mapSpansToGlb(spans, intArrayOf(0, 2, 4, 5))
            ranges(parts) shouldBe listOf(Triple("a", 0, 2), Triple("b", 2, 2))
        }

        "a part whose triangles were all dropped disappears and the sum matches the GLB" {
            val spans = listOf(span("a", 0, 2), span("gone", 2, 2), span("c", 4, 1))
            val kept = intArrayOf(0, 1, 4)
            val parts = mapSpansToGlb(spans, kept)
            ranges(parts) shouldBe listOf(Triple("a", 0, 2), Triple("c", 2, 1))
            parts.sumOf { it.triangleCount } shouldBe kept.size
        }

        "no kept triangles gives no parts" {
            mapSpansToGlb(listOf(span("a", 0, 2)), IntArray(0)) shouldBe emptyList()
        }
    })
