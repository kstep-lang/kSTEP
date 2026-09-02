package dev.kstep.constraints

/**
 * A point-to-point coincidence constraint for [PlaneGcsSolver.solve]: the points at indices
 * [pointA] and [pointB] (into the accompanying `points` list) must end up at the same location.
 *
 * Mirrors `GCS::System::addConstraintP2PCoincident(Point&, Point&)`, which is NOT itself a
 * primitive PlaneGCS constraint -- it is implemented as **two** separate `ConstraintEqual` rows
 * (`p1.x == p2.x` and `p1.y == p2.y`, verified against `third_party/planegcs/GCS.cpp:904-908`).
 * Any accounting of "how many native constraint rows does this system have" must count a single
 * [CoincidenceConstraint] as two, not one -- see `docs/adr/ADR-0007`'s DoS measurement notes.
 *
 * This is the type [DistanceConstraint]'s own KDoc referred to as "a different (and, as of a
 * previous wave, unsupported) constraint type" -- a zero-distance [DistanceConstraint] is rejected
 * by [PlaneGcsSolver]'s validation precisely because this type is the correct way to express it.
 *
 * @property pointA index into the accompanying points list.
 * @property pointB index into the accompanying points list; must differ from [pointA].
 */
data class CoincidenceConstraint(
    val pointA: Int,
    val pointB: Int,
) : SketchConstraint
