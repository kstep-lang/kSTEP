package dev.kstep.geometry.select

/**
 * Raised when a vertex selector does not resolve to exactly what the caller expects (never guesses). Scripts report
 * it as `KSTEP-S-011`.
 *
 * @property matched the indices the selector did match (empty for "matched nothing").
 * @property candidates the vertices the caller should look at: the matches when the count was ambiguous, all
 *   vertices when nothing matched (the message lists at most [VertexSelection.MAX_LISTED_CANDIDATES]).
 */
class VertexSelectionException(
    message: String,
    val selector: VertexSelector,
    val expected: Int?,
    val matched: List<Int>,
    val candidates: List<VertexInfo>,
) : TopologySelectionException(message)

/** Strict resolution of [VertexSelector]s (ADR-0037, D2). */
object VertexSelection {
    const val MAX_LISTED_CANDIDATES: Int = EdgeSelection.MAX_LISTED_CANDIDATES

    /**
     * Resolves [selector] and returns the matching vertex indices, ascending.
     *
     * @throws IllegalArgumentException if the selector tree is too large/deep, or [expected] is below 1.
     * @throws VertexSelectionException if nothing matches or the count differs from [expected].
     */
    internal fun resolve(
        ctx: SelectionContext,
        selector: VertexSelector,
        expected: Int?,
        tolerance: SelectionTolerance,
    ): List<Int> {
        SelectorTree.validate(selector)
        require(expected == null || expected >= 1) { "expected must be at least 1, got $expected" }
        val matched = SelectorEvaluator(ctx, tolerance).vertexSet(selector).sorted()
        val name = EdgeSelection.describe(selector)
        if (matched.isEmpty()) {
            throw VertexSelectionException(
                "vertex selection $name matched no vertex. Candidates:\n" +
                    EdgeSelection.vertexCandidateLines(ctx.vertices),
                selector,
                expected,
                matched,
                ctx.vertices,
            )
        }
        if (expected != null && matched.size != expected) {
            val chosen = matched.map { ctx.vertices[it] }
            throw VertexSelectionException(
                "vertex selection $name is ambiguous: expected $expected vertex(es) but matched ${matched.size}. " +
                    "Matched:\n" + EdgeSelection.vertexCandidateLines(chosen),
                selector,
                expected,
                matched,
                chosen,
            )
        }
        return matched
    }
}
