package dev.kstep.constraints

import kotlin.math.hypot

/**
 * The outcome of [PlaneGcsSolver.solveDistances]: the solve [status], and every point's position
 * after solving -- in the same order, and the same length, as the `points` list passed in (fixed
 * points are guaranteed present unchanged; free points carry the solver's final position, or its
 * last-attempted position when [status] is [SolveStatus.FAILED] -- see
 * `docs/adr/ADR-0006-planegcs-constraint-bridge.adoc`'s "Environment note" for the verified
 * behavior this reflects).
 */
data class SolveResult(
    val status: SolveStatus,
    val points: List<SketchPoint>,
) {
    /** `true` when [status] is [SolveStatus.SUCCESS] or [SolveStatus.CONVERGED]. */
    val solved: Boolean
        get() = status == SolveStatus.SUCCESS || status == SolveStatus.CONVERGED

    /**
     * The Euclidean distance between [points]`[a]` and [points]`[b]` in this result -- a small
     * convenience for callers (and this module's own tests) checking whether a requested
     * [DistanceConstraint] was actually satisfied, without repeating the `hypot` arithmetic at every
     * call site.
     *
     * @throws IndexOutOfBoundsException if `a` or `b` is not a valid index into [points].
     */
    fun distanceBetween(
        a: Int,
        b: Int,
    ): Double {
        val pa = points[a]
        val pb = points[b]
        return hypot(pb.x - pa.x, pb.y - pa.y)
    }
}
