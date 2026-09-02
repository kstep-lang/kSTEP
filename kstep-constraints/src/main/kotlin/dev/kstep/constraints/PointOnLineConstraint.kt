package dev.kstep.constraints

/**
 * A collinearity constraint for [PlaneGcsSolver.solve]: the point at index [point] is forced onto
 * the **infinite line** through [lineFrom] and [lineTo] (all indices into the accompanying
 * `points` list) -- not the segment between them, and [point] is free to land anywhere along that
 * line's full extent, including outside the `[lineFrom, lineTo]` span.
 *
 * Mirrors `GCS::System::addConstraintPointOnLine(Point&, Point&, Point&)`, backed by PlaneGCS's own
 * `ConstraintPointOnLine` class (`third_party/planegcs/Constraints.cpp:1027-1058`) -- the only
 * genuinely nonlinear constraint type added in this wave (a signed-area-over-length residual, not
 * a plain coordinate equality).
 *
 * **Division-by-zero hazard (verified against the vendored source, not theoretical)**:
 * `ConstraintPointOnLine::error()` computes `area / d` where `d = |lineTo - lineFrom|`, and
 * `grad()` additionally divides by `d^2` -- both blow up to `inf`/`NaN` if [lineFrom] and [lineTo]
 * become numerically coincident *at any point during the solver's iteration*, not just at the
 * initial guess. Index distinctness (enforced by [PlaneGcsSolver]'s validation and mirrored
 * natively) does NOT prevent this: two distinct point indices can still converge onto the same
 * coordinates mid-solve, e.g. via a simultaneous [CoincidenceConstraint] on the same two points.
 * [PlaneGcsSolver.solve] defends against this by rejecting any non-finite coordinate in the
 * native result with [ConstraintSolverException] rather than ever returning a NaN-polluted
 * [SolveResult] -- see that function's KDoc.
 *
 * @property point index of the point being constrained onto the line.
 * @property lineFrom first point defining the line.
 * @property lineTo second point defining the line.
 *
 * All three indices must be pairwise distinct: `lineFrom == lineTo` degenerates the line itself
 * (zero-length direction vector, the division-by-zero hazard above triggers immediately), and
 * `point == lineFrom` (or `lineTo`) makes the constraint trivially and permanently satisfied -- a
 * constant zero row in the solver's Jacobian with no legitimate use case this wave could construct,
 * so it is rejected alongside the genuinely degenerate case rather than special-cased as "harmless".
 */
data class PointOnLineConstraint(
    val point: Int,
    val lineFrom: Int,
    val lineTo: Int,
) : SketchConstraint
