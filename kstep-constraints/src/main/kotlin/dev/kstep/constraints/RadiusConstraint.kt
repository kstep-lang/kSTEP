package dev.kstep.constraints

/** Forces the scalar [radiusScalar] (index into the solver's scalar list) to equal [radius]. */
data class RadiusConstraint(
    val radiusScalar: Int,
    val radius: Double,
) : SketchConstraint

/** Forces the scalars [a] and [b] (indices into the solver's scalar list) to be equal. */
data class EqualScalarConstraint(
    val a: Int,
    val b: Int,
) : SketchConstraint

/**
 * Forces [point] onto the circle around [center] whose radius is the scalar [radiusScalar]
 * (`|point - center| = r`). [point] and [center] must start at different coordinates.
 */
data class PointOnCircleConstraint(
    val point: Int,
    val center: Int,
    val radiusScalar: Int,
) : SketchConstraint

/**
 * Makes the (infinite) line through [lineFrom]/[lineTo] tangent to the circle around [center]
 * with radius scalar [radiusScalar]. PlaneGCS measures an unsigned distance, so the side of the line
 * the circle ends up on is decided by the start values.
 */
data class TangentLineCircleConstraint(
    val lineFrom: Int,
    val lineTo: Int,
    val center: Int,
    val radiusScalar: Int,
) : SketchConstraint

/**
 * Makes two circles tangent: externally (`|c1 - c2| = r1 + r2`) or [internal]ly
 * (`|c1 - c2| = |r1 - r2|`). The centres must start at different coordinates.
 */
data class TangentCircleCircleConstraint(
    val centerA: Int,
    val radiusA: Int,
    val centerB: Int,
    val radiusB: Int,
    val internal: Boolean,
) : SketchConstraint

/**
 * Fixes the angle from the direction `lineAFrom -> lineATo` to `lineBFrom -> lineBTo` to [radians]
 * (in `[-pi, pi]`). Both legs must be non-degenerate; they may share an endpoint.
 */
data class AngleConstraint(
    val lineAFrom: Int,
    val lineATo: Int,
    val lineBFrom: Int,
    val lineBTo: Int,
    val radians: Double,
) : SketchConstraint
