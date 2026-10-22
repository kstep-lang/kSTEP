package dev.kstep.constraints

/**
 * A scalar unknown of a sketch -- today a circle or arc radius -- see
 * `docs/adr/ADR-0032-sketch-extensions.adoc`. [value] is the start value and must be finite, positive
 * and at most [PlaneGcsSolver.MAX_DISTANCE]; a [fixed] scalar is a constant for the solver.
 */
data class SketchScalar(
    val value: Double,
    val fixed: Boolean = false,
)
