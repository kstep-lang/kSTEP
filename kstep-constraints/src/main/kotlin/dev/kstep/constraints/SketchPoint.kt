package dev.kstep.constraints

/**
 * A single 2D point in a [PlaneGcsSolver.solveDistances] problem, before or after solving.
 *
 * @property x the point's X coordinate.
 * @property y the point's Y coordinate.
 * @property fixed when `true`, this point is held constant during solving -- it is never added to
 *   the solver's unknowns, and its [x]/[y] are guaranteed unchanged in the returned [SolveResult].
 *   When `false`, the point is free to move; [x]/[y] here are only its *initial guess* for the
 *   solver's iteration, not its final position.
 */
data class SketchPoint(
    val x: Double,
    val y: Double,
    val fixed: Boolean = false,
)
