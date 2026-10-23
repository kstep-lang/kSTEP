package dev.kstep.geometry.select

/**
 * Raised when a face selector does not resolve to exactly what the caller expects (never guesses). Like
 * [EdgeSelectionException], scripts report it as `KSTEP-S-011`.
 *
 * @property matched the indices the selector did match (empty for "matched nothing").
 * @property candidates the faces the caller should look at: the matches when the count was ambiguous, all faces
 *   when nothing matched. Complete (the message lists at most [FaceSelection.MAX_LISTED_CANDIDATES]).
 */
class FaceSelectionException(
    message: String,
    val selector: FaceSelector,
    val expected: Int?,
    val matched: List<Int>,
    val candidates: List<FaceInfo>,
) : TopologySelectionException(message)

/** Strict resolution of [FaceSelector]s (ADR-0037, D2). */
object FaceSelection {
    const val MAX_LISTED_CANDIDATES: Int = EdgeSelection.MAX_LISTED_CANDIDATES

    /**
     * Resolves [selector] and returns the matching face indices, ascending.
     *
     * @param expected if non-null, the result must have exactly this many faces.
     * @throws IllegalArgumentException if the selector tree is too large/deep, or [expected] is below 1.
     * @throws FaceSelectionException if nothing matches or the count differs from [expected].
     */
    internal fun resolve(
        ctx: SelectionContext,
        selector: FaceSelector,
        expected: Int?,
        tolerance: SelectionTolerance,
    ): List<Int> {
        SelectorTree.validate(selector)
        require(expected == null || expected >= 1) { "expected must be at least 1, got $expected" }
        val matched = SelectorEvaluator(ctx, tolerance).faceSet(selector).sorted()
        val name = EdgeSelection.describe(selector)
        if (matched.isEmpty()) {
            throw FaceSelectionException(
                "face selection $name matched no face. Candidates:\n" + EdgeSelection.faceCandidateLines(ctx.faces),
                selector,
                expected,
                matched,
                ctx.faces,
            )
        }
        if (expected != null && matched.size != expected) {
            val chosen = matched.map { ctx.faces[it] }
            throw FaceSelectionException(
                "face selection $name is ambiguous: expected $expected face(s) but matched ${matched.size}. " +
                    "Matched:\n" + EdgeSelection.faceCandidateLines(chosen),
                selector,
                expected,
                matched,
                chosen,
            )
        }
        return matched
    }
}
