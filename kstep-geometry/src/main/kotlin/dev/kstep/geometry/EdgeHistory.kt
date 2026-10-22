package dev.kstep.geometry

/**
 * What a modelling operation did to one edge of one of its operands, expressed against the edges of
 * the operation's result (indices are the result's `OcctShape.edges` positions).
 *
 * Derived from OCCT's own `Modified()` / `IsDeleted()` bookkeeping (`BRepBuilderAPI_MakeShape`),
 * read inside the same native critical section as the operation itself.
 */
sealed interface EdgeFate {
    /** The edge survived unchanged and is now the result's edge [index]. */
    data class Kept(
        val index: Int,
    ) : EdgeFate

    /** The edge was modified (shortened, split, merged, moved); [images] are all its successors. */
    data class Modified(
        val images: List<Int>,
    ) : EdgeFate

    /** OCCT reports the edge as removed. */
    data object Deleted : EdgeFate

    /** The edge is neither deleted nor found in the result, and has no recorded successor. */
    data object Lost : EdgeFate

    /** The result edge indices this edge maps to; empty for [Deleted] and [Lost]. */
    val resultEdges: List<Int>
        get() =
            when (this) {
                is Kept -> listOf(index)
                is Modified -> images
                Deleted, Lost -> emptyList()
            }
}

/**
 * Per-operand edge fates of one operation. `operands[k][i]` is the fate of edge `i` of operand `k`
 * (operand order = the operation's argument order; a unary operation has one operand).
 */
data class EdgeHistory(
    val operands: List<List<EdgeFate>>,
)

/**
 * A freshly built shape together with the [EdgeHistory] of the operation that produced it.
 * [AutoCloseable] like [OcctShape]; closing releases [shape].
 */
class TrackedShape internal constructor(
    val shape: OcctShape,
    val history: EdgeHistory,
) : AutoCloseable {
    override fun close() = shape.close()
}

private const val FATE_KEPT = 0
private const val FATE_MODIFIED = 1
private const val FATE_DELETED = 2
private const val FATE_LOST = 3

/**
 * Decodes the native history payload `[operandCount, (edgeCount, {status, imageCount, images...}*)*]`.
 * Pure and OCCT-free; every length, count and index is validated before it is used.
 *
 * @param expectedOperandEdgeCounts when non-null, the operand count and each operand's edge count
 *   must match exactly.
 * @param resultEdgeCount every image index must lie in `[0, resultEdgeCount)`.
 * @throws IllegalStateException on any inconsistency.
 */
internal fun decodeEdgeHistory(
    raw: IntArray,
    expectedOperandEdgeCounts: List<Int>?,
    resultEdgeCount: Int,
): EdgeHistory {
    check(raw.isNotEmpty()) { "native edge history must carry an operand count" }
    check(raw.size <= OcctKernel.MAX_HISTORY_INTS) {
        "native edge history of ${raw.size} ints exceeds ${OcctKernel.MAX_HISTORY_INTS}"
    }
    var pos = 0

    fun next(what: String): Int {
        check(pos < raw.size) { "malformed edge history: truncated while reading $what" }
        return raw[pos++]
    }
    val operandCount = next("operand count")
    check(operandCount in 1..2) { "malformed edge history: operand count $operandCount outside [1, 2]" }
    if (expectedOperandEdgeCounts != null) {
        check(expectedOperandEdgeCounts.size == operandCount) {
            "edge history has $operandCount operands, expected ${expectedOperandEdgeCounts.size}"
        }
    }
    val operands = ArrayList<List<EdgeFate>>(operandCount)
    repeat(operandCount) { k ->
        val edgeCount = next("edge count")
        check(
            edgeCount in 0..OcctKernel.MAX_QUERY_EDGES,
        ) { "malformed edge history: edge count $edgeCount out of range" }
        if (expectedOperandEdgeCounts != null) {
            check(edgeCount == expectedOperandEdgeCounts[k]) {
                "edge history operand $k has $edgeCount edges, expected ${expectedOperandEdgeCounts[k]}"
            }
        }
        val fates = ArrayList<EdgeFate>(edgeCount)
        repeat(edgeCount) {
            val status = next("status")
            val imageCount = next("image count")
            check(imageCount in 0..resultEdgeCount) { "malformed edge history: image count $imageCount out of range" }
            val images = ArrayList<Int>(imageCount)
            repeat(imageCount) {
                val image = next("image")
                check(image in 0 until resultEdgeCount) {
                    "malformed edge history: image index $image outside [0, $resultEdgeCount)"
                }
                images.add(image)
            }
            fates.add(
                when (status) {
                    FATE_KEPT -> {
                        check(images.size == 1) { "malformed edge history: a kept edge must have exactly one image" }
                        EdgeFate.Kept(images[0])
                    }
                    FATE_MODIFIED -> {
                        check(
                            images.isNotEmpty(),
                        ) { "malformed edge history: a modified edge needs at least one image" }
                        EdgeFate.Modified(images)
                    }
                    FATE_DELETED -> {
                        check(images.isEmpty()) { "malformed edge history: a deleted edge cannot have images" }
                        EdgeFate.Deleted
                    }
                    FATE_LOST -> {
                        check(images.isEmpty()) { "malformed edge history: a lost edge cannot have images" }
                        EdgeFate.Lost
                    }
                    else -> error("malformed edge history: unknown status $status")
                },
            )
        }
        operands.add(fates)
    }
    check(pos == raw.size) { "malformed edge history: ${raw.size - pos} trailing ints" }
    return EdgeHistory(operands)
}
