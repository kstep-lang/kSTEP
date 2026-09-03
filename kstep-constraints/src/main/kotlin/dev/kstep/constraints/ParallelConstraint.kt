package dev.kstep.constraints

/**
 * A parallelism constraint for [PlaneGcsSolver.solve]: the directed segment from [lineAFrom] to
 * [lineATo] ("leg A") is forced parallel to the directed segment from [lineBFrom] to [lineBTo]
 * ("leg B") -- all four indices into the accompanying `points` list.
 *
 * Mirrors `GCS::System::addConstraintParallel(Line&, Line&)`, backed by PlaneGCS's own
 * `ConstraintParallel` class (`third_party/planegcs/Constraints.cpp:1178-1244`).
 *
 * **No `GCS::Line` concept exists on the Kotlin side** (see `docs/adr/ADR-0014-planegcs-parallel-
 * and-perpendicular.adoc`'s "Decision"): PlaneGCS offers no four-`Point` overload of
 * `addConstraintParallel` the way it does for [PerpendicularConstraint], so the native bridge
 * builds two `GCS::Line` values purely as **stack-local, immediately-discarded** adapters to that
 * one signature. This is safe, not merely assumed: `ConstraintParallel`'s constructor
 * (`Constraints.cpp:1178-1190`) copies only the eight raw `double*` out of the two `Line&`
 * arguments into its own `pvec` and retains no reference to the `Line` object itself, and
 * `System::addConstraint` (`GCS.cpp:557-573`) likewise stores only the `Constraint*` and those same
 * `double*`s -- never the `Line`. Regression-guarded by
 * `PlaneGcsConstraintTypesSmokeTest`'s "T15"/"T16" cases, which solve correctly with the native
 * `Line` long since out of scope by the time `solve()` runs.
 *
 * **Direction is irrelevant.** Swapping [lineAFrom]/[lineATo] (or [lineBFrom]/[lineBTo]) negates
 * that leg's `dx`/`dy`, which flips the sign of the residual but not its zero set -- "parallel"
 * here means *collinear directions*, not *same direction*. In particular, **an anti-parallel
 * (180°) solution satisfies this constraint just as well as a same-direction (0°) one**; a caller
 * that needs a specific orientation must add a further constraint of its own.
 *
 * **Leg length is not preserved.** The residual PlaneGCS drives to zero is the cross product
 * `dx1*dy2 - dy1*dx2`, which is zero for legs of any length (including very different lengths from
 * each other) -- do not assume either leg keeps its starting length after a solve; add a
 * [DistanceConstraint] if a fixed length matters.
 *
 * **Shared endpoints between the two legs are supported and intended.** `ParallelConstraint(0, 1,
 * 1, 2)` (leg A = P0->P1, leg B = P1->P2) is the normal way to express e.g. a chamfer or a
 * three-point collinearity-adjacent shape, and is verified to solve correctly -- see
 * [PerpendicularConstraint]'s KDoc for the analogous, more common rectangle-corner case (T18) and
 * why the validation below deliberately does *not* require all four indices pairwise distinct.
 *
 * **Degenerate-leg hazard, rejected at validation time rather than left to fail mid-solve**:
 * `ConstraintParallel::rescale()` (`Constraints.cpp:1198-1204`) computes
 * `scale = coef / sqrt((dx1^2+dy1^2) * (dx2^2+dy2^2))` **once, in the constructor**, from each
 * leg's *initial* coordinates -- unlike [PointOnLineConstraint]'s division, which recomputes every
 * iteration and can therefore blow up only *mid-solve* even from distinct starting coordinates,
 * this one is entirely determined by the starting configuration. A leg whose two points start at
 * identical coordinates makes `scale` infinite and the constraint permanently `NaN` for the whole
 * solve, silently inert rather than loudly failing -- so [PlaneGcsSolver] rejects that case up
 * front as an `IllegalArgumentException` instead. This also means the hazard is *not* the same
 * "two distinct indices can still converge onto the same point mid-solve" risk
 * [PointOnLineConstraint] documents: because `rescale()` never runs again after construction, only
 * the *initial* coordinates matter here, and input validation genuinely closes the gap rather than
 * merely reducing its likelihood.
 *
 * @property lineAFrom index of leg A's first point.
 * @property lineATo index of leg A's second point; must differ from [lineAFrom], and the two
 *   points must not start at identical coordinates (see above).
 * @property lineBFrom index of leg B's first point.
 * @property lineBTo index of leg B's second point; must differ from [lineBFrom], and the two
 *   points must not start at identical coordinates (see above).
 *
 * The two legs, taken as unordered index pairs, must also not be identical -- `(a, b, a, b)` and
 * `(a, b, b, a)` are both rejected: the constraint would then be trivially and permanently
 * satisfied (a constant-zero residual), which is a degenerate encoding of "no constraint at all"
 * rather than a legitimate use.
 */
data class ParallelConstraint(
    val lineAFrom: Int,
    val lineATo: Int,
    val lineBFrom: Int,
    val lineBTo: Int,
) : SketchConstraint
