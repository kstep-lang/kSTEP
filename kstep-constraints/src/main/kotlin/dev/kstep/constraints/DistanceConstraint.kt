package dev.kstep.constraints

/**
 * A point-to-point distance constraint for [PlaneGcsSolver.solveDistances]: the Euclidean distance
 * between the points at indices [pointA] and [pointB] (into the `points` list passed alongside this
 * constraint) must equal [distance].
 *
 * @property pointA index into the accompanying points list.
 * @property pointB index into the accompanying points list; must differ from [pointA].
 * @property distance the required distance, in the same (unitless) coordinate space as the points.
 *   Must be strictly positive -- a zero-distance "constraint" is a coincidence constraint, a
 *   different (and, as of this wave, unsupported) constraint type PlaneGCS models separately; see
 *   `docs/adr/ADR-0006-planegcs-constraint-bridge.adoc`'s "Folge-Wellen".
 */
data class DistanceConstraint(
    val pointA: Int,
    val pointB: Int,
    val distance: Double,
)
