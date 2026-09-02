package dev.kstep.constraints

/**
 * A vertical-alignment constraint for [PlaneGcsSolver.solve]: the points at indices [pointA] and
 * [pointB] (into the accompanying `points` list) are forced onto a vertical line.
 *
 * Mirrors `GCS::System::addConstraintVertical(Point&, Point&)`, which is a single
 * `ConstraintEqual(p1.x, p2.x)` (verified against `third_party/planegcs/GCS.cpp:925-928`).
 *
 * "Vertical" constrains the pair to **equal X coordinates** -- see [HorizontalConstraint]'s KDoc
 * for the symmetric point about that type.
 *
 * @property pointA index into the accompanying points list.
 * @property pointB index into the accompanying points list; must differ from [pointA].
 */
data class VerticalConstraint(
    val pointA: Int,
    val pointB: Int,
) : SketchConstraint
