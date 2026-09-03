package dev.kstep.constraints

/**
 * A perpendicularity constraint for [PlaneGcsSolver.solve]: the directed segment from
 * [lineAFrom] to [lineATo] ("leg A") is forced perpendicular to the directed segment from
 * [lineBFrom] to [lineBTo] ("leg B") -- all four indices into the accompanying `points` list.
 *
 * Mirrors `GCS::System::addConstraintPerpendicular(Point&, Point&, Point&, Point&)`
 * (`third_party/planegcs/GCS.h:298-303`, `GCS.cpp:730-742`), backed by PlaneGCS's own
 * `ConstraintPerpendicular` class (`Constraints.cpp:1249-1331`). Unlike [ParallelConstraint],
 * PlaneGCS offers a native four-`Point` overload for this constraint -- no `GCS::Line` adapter is
 * needed natively either.
 *
 * **Direction is irrelevant.** Swapping [lineAFrom]/[lineATo] (or [lineBFrom]/[lineBTo]) negates
 * that leg's `dx`/`dy`, which flips the sign of the residual but not its zero set.
 * "Perpendicular" is unsigned: a 90° and a -90° (i.e. 270°) relative orientation are both valid
 * solutions.
 *
 * **Leg length is not preserved.** The residual PlaneGCS drives to zero is the dot product
 * `dx1*dx2 + dy1*dy2`, which is zero regardless of either leg's length -- do not assume either leg
 * keeps its starting length after a solve; add a [DistanceConstraint] if a fixed length matters.
 *
 * **Shared endpoints between the two legs are supported and intended** -- this is the normal way
 * to express a rectangle corner or an L-shaped bracket: `PerpendicularConstraint(0, 1, 1, 2)` (leg
 * A = P0->P1, leg B = P1->P2, sharing P1) is verified to solve correctly (`dot == 0.0` exactly, see
 * `PlaneGcsConstraintTypesSmokeTest`'s "T18"), which is why [PlaneGcsSolver]'s validation
 * deliberately does *not* require all four indices pairwise distinct the way e.g.
 * [PointOnLineConstraint] does over its three indices -- a full pairwise-distinct rule would reject
 * the single most common real sketch shape this constraint exists for.
 *
 * **A parallel/perpendicular system can report [SolveStatus.CONVERGED] even when contradictory**,
 * unlike e.g. [CoincidenceConstraint]'s `ConstraintEqual` residual: because neither residual here
 * depends on leg length, an unsatisfiable combination (e.g. "A parallel to B" together with "B
 * perpendicular to A") can be driven arbitrarily close to zero simply by *shrinking* one leg toward
 * zero length, which is a legitimate least-squares minimum PlaneGCS's solver can and does reach --
 * it is not the same "leave the free point at its starting position and report `FAILED`" behavior
 * [CoincidenceConstraint]'s and [PointOnLineConstraint]'s own contradictory-system test cases
 * document. Do not assume a contradictory parallel/perpendicular system reports `FAILED`; measure
 * it.
 *
 * **Degenerate-leg hazard, rejected at validation time rather than left to fail mid-solve**:
 * `ConstraintPerpendicular::rescale()` (`Constraints.cpp:1283-1290`) computes the same
 * `scale = coef / sqrt((dx1^2+dy1^2) * (dx2^2+dy2^2))` as [ParallelConstraint], **once, in the
 * constructor**, from each leg's *initial* coordinates. A leg whose two points start at identical
 * coordinates makes `scale` infinite and the constraint permanently `NaN` for the whole solve --
 * see [ParallelConstraint]'s KDoc for the full reasoning, which applies identically here.
 *
 * @property lineAFrom index of leg A's first point.
 * @property lineATo index of leg A's second point; must differ from [lineAFrom], and the two
 *   points must not start at identical coordinates (see above).
 * @property lineBFrom index of leg B's first point.
 * @property lineBTo index of leg B's second point; must differ from [lineBFrom], and the two
 *   points must not start at identical coordinates (see above).
 *
 * The two legs, taken as unordered index pairs, must also not be identical -- `(a, b, a, b)` and
 * `(a, b, b, a)` are both rejected: `dx1==dx2 && dy1==dy2` (or the negation for the swapped case)
 * makes the dot product a constant `dx1^2+dy1^2 > 0`, so the constraint would be permanently
 * unsatisfiable except by collapsing the shared leg to zero length -- a degenerate encoding, not a
 * legitimate use.
 */
data class PerpendicularConstraint(
    val lineAFrom: Int,
    val lineATo: Int,
    val lineBFrom: Int,
    val lineBTo: Int,
) : SketchConstraint
