package dev.kstep.constraints

/**
 * A horizontal-alignment constraint for [PlaneGcsSolver.solve]: the points at indices [pointA] and
 * [pointB] (into the accompanying `points` list) are forced onto a horizontal line.
 *
 * Mirrors `GCS::System::addConstraintHorizontal(Point&, Point&)`, which is a single
 * `ConstraintEqual(p1.y, p2.y)` (verified against `third_party/planegcs/GCS.cpp:915-918`).
 *
 * **Common point of confusion, called out explicitly**: "horizontal" constrains the pair to
 * **equal Y coordinates** (a horizontal segment runs left-to-right at a constant height), NOT
 * equal X. [VerticalConstraint] is the one that equalizes X.
 *
 * @property pointA index into the accompanying points list.
 * @property pointB index into the accompanying points list; must differ from [pointA].
 */
data class HorizontalConstraint(
    val pointA: Int,
    val pointB: Int,
) : SketchConstraint
