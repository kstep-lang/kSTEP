package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class EdgeHistoryDecodeTest :
    StringSpec({
        // one operand with 4 edges: kept->0, modified->{1,2}, deleted, lost; result has 3 edges
        val payload = intArrayOf(1, 4, 0, 1, 0, 1, 2, 1, 2, 2, 0, 3, 0)

        "a well-formed single-operand history decodes to the four fates" {
            val h = decodeEdgeHistory(payload, listOf(4), 3)
            h.operands.size shouldBe 1
            h.operands[0] shouldBe
                listOf(EdgeFate.Kept(0), EdgeFate.Modified(listOf(1, 2)), EdgeFate.Deleted, EdgeFate.Lost)
            h.operands[0][1].resultEdges shouldBe listOf(1, 2)
            h.operands[0][2].resultEdges shouldBe emptyList()
        }

        "a two-operand history decodes both operands" {
            val two = intArrayOf(2, 1, 0, 1, 0, 1, 0, 1, 1)
            val h = decodeEdgeHistory(two, listOf(1, 1), 2)
            h.operands[1][0] shouldBe EdgeFate.Kept(1)
        }

        "an empty payload, a bad operand count and a truncated payload are rejected" {
            shouldThrow<IllegalStateException> { decodeEdgeHistory(IntArray(0), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(0), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(3), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(payload.copyOf(payload.size - 1), listOf(4), 3) }
        }

        "trailing ints are rejected" {
            shouldThrow<IllegalStateException> { decodeEdgeHistory(payload + 0, listOf(4), 3) }
        }

        "an image index outside the result's edge range is rejected, also a negative one" {
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 0, 1, 3), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 0, 1, -1), null, 3) }
        }

        "operand and edge counts must match the expectation" {
            shouldThrow<IllegalStateException> { decodeEdgeHistory(payload, listOf(5), 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(payload, listOf(4, 4), 3) }
        }

        "a kept edge must have exactly one image, a modified one at least one, a deleted one none" {
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 0, 0), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 0, 2, 0, 1), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 1, 0), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 2, 1, 0), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 3, 1, 0), null, 3) }
        }

        "an unknown status and an oversized image count are rejected" {
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 9, 0), null, 3) }
            shouldThrow<IllegalStateException> { decodeEdgeHistory(intArrayOf(1, 1, 1, 99, 0), null, 3) }
        }

        "a payload above the size limit is rejected before it is parsed" {
            shouldThrow<IllegalStateException> { decodeEdgeHistory(IntArray(OcctKernel.MAX_HISTORY_INTS + 1), null, 3) }
        }
    })
