package dev.kstep.constraints

import dev.kstep.constraints.planegcs.NativeConstraintKind
import dev.kstep.constraints.planegcs.PlaneGcsBridge
import dev.kstep.constraints.planegcs.PlaneGcsNativeLibrary

/**
 * Public entry point to the PlaneGCS 2D geometric constraint solver -- the only public type in this
 * module besides the plain data carriers ([SketchPoint], the [SketchConstraint] hierarchy,
 * [SolveResult], [SolveStatus]) and the two exception types.
 *
 * kSTEP vendors the PlaneGCS solver sources directly into this repository (unlike kstep-geometry's
 * OCCT bridge, which links against a system-installed library) and compiles them into a JNI shim --
 * see `kstep-constraints/src/main/cpp/third_party/planegcs/PROVENANCE.adoc` for exactly what is
 * vendored and from where, and `docs/adr/ADR-0006-planegcs-constraint-bridge.adoc` /
 * `docs/adr/ADR-0007-planegcs-additional-constraint-types.adoc` for the full rationale, license
 * analysis, and each wave's scope. When the Eigen/Boost dev headers or a C++ compiler were absent at
 * build time (or on any platform other than linux-x86-64, the only one built so far), the native
 * shim is never compiled, no `.so` classpath resource exists, and [availability] reports
 * [PlaneGcsAvailability.Unavailable] -- every function here then fails predictably with
 * [PlaneGcsUnavailableException] rather than the build, or this whole module, failing outright. See
 * README's "Building" section for the `-Pkstep.planegcs.require=true` flag that turns this into a
 * hard build failure instead, for environments that must guarantee the bridge is present.
 *
 * This object's solving API is deliberately a **one-shot, stateless** call: [solve] builds a fresh
 * native `GCS::System` for exactly the problem passed in, solves it, and returns. There is no
 * persistent solver handle to [AutoCloseable.close] and no way to add constraints incrementally
 * across multiple calls -- see `docs/adr/ADR-0006`'s "Decision" for why, and its "Folge-Wellen" for
 * the later, handle-based wave that will add that.
 */
object PlaneGcsSolver {
    /**
     * Upper bound on the number of points a single [solve] call may pass.
     *
     * This is an actual, empirically-measured wall-clock bound, not a placeholder value:
     * [solve] is a single, non-interruptible JNI call -- no timeout parameter exists, and
     * `Thread.interrupt()` has no effect on native code already inside PlaneGCS's solve loop (see
     * this object's class KDoc) -- so [MAX_POINTS] is the primary thing standing between a caller
     * and an unbounded native CPU burn on the calling thread.
     *
     * A previous value of 4096 was found, during ADR-0006's DoS review, to let a single call hang
     * past 10 minutes on a *chain* topology (`n` points / `n-1` distance constraints). A follow-up
     * value of 512 was then found -- in a **second** round of that same review -- to still be
     * unsafe: the chain/ring topologies measured for that value are not PlaneGCS's worst case.
     * Dense, randomly-connected, mutually inconsistent point sets (many points, each involved in
     * several conflicting distance constraints to other points, so no partial solution can locally
     * satisfy its neighborhood) are far more expensive per point than a chain or ring, because
     * PlaneGCS's dense-QR-based solver's per-iteration cost grows with the number of *unknowns
     * actually coupled together* in one subsystem, not just the point count along a single path.
     * At n=512 points / 1024 constraints (a random graph, target distances independent of the
     * randomly-placed start coordinates so the system is essentially never satisfiable), a direct
     * JNI timing run against the built `libkstep_planegcs_bridge.so` took **47s at the default
     * `maxIterations` of 100** and over three minutes at the previous [MAX_ITERATIONS] cap of 1000
     * -- roughly two orders of magnitude worse than the chain-topology measurement that value 512
     * was originally set from.
     *
     * [MAX_POINTS] is set here specifically from THAT topology, not the chain/ring ones: direct
     * JNI timing runs of a dense random inconsistent graph (`m = 2n` constraints, matching
     * [MAX_CONSTRAINTS]'s ratio to this constant) scale roughly cubically in `n` --
     * 103ms at n=32, 250ms at n=48, ~500ms at n=64, 870ms at n=80, 1.5s at n=96, 3.7s at n=128 (each
     * figure the largest of three independent random seeds, at the full [MAX_ITERATIONS] budget of
     * 1000, i.e. already the most expensive point on the iteration-count axis too -- see
     * [MAX_ITERATIONS]'s own KDoc). [MAX_POINTS] is set at n=64, the last point on that curve with
     * real headroom below one second: five distinct adversarial topologies (dense random
     * inconsistent graph, a fully-connected 46-point cluster all constrained to the same
     * unsatisfiable distance, a chain with both endpoints fixed to an impossible total length, a
     * "band" graph, and a chain with a huge coordinate scatter collapsing to a tiny target
     * distance), each run at exactly this cap's `n`/[MAX_CONSTRAINTS]/[MAX_ITERATIONS] combination
     * across ten independent random seeds, together produced a worst observed run of 557ms. See
     * `docs/adr/ADR-0006`'s "Security" table for the full measurement table.
     *
     * This constant was re-validated, not re-derived, when [SketchConstraint] grew beyond plain
     * distances (see `docs/adr/ADR-0007`'s DoS section): the point-count-driven cost above is what
     * bounds the (still exclusively distance-based) measurements this KDoc quotes, and the newer
     * constraint kinds were separately measured at this SAME cap rather than by re-running this
     * specific curve -- see [MAX_CONSTRAINTS]'s KDoc and ADR-0007 for those numbers.
     *
     * This is still an empirical bound on the topologies tried, not a mathematical proof of a worst
     * case -- some other adversarial shape not tried here could cost more at this same `n`.
     * [MAX_POINTS] is deliberately set with roughly 2x headroom below one second (rather than at the
     * exact edge of what was measured) to absorb that residual risk, but a caller that needs a hard
     * wall-clock guarantee should still enforce its own timeout around the calling thread (e.g.
     * running [solve] on a dedicated thread/executor and abandoning -- **not** interrupting, see
     * this object's class KDoc -- that thread past a deadline) rather than trusting this constant
     * alone. A future incremental/handle-based solver (ADR-0006, "Folge-Wellen") is the intended
     * path for sketches larger than this one-shot call can safely serve -- not raising this
     * constant.
     */
    const val MAX_POINTS: Int = 64

    /**
     * Upper bound on the number of [SketchConstraint]s a single [solve] call may pass. Kept at 2x
     * [MAX_POINTS], the same ratio used throughout this constant's history (both the original
     * pre-DoS-fix values and the still-unsafe 512/1024 pair from the first round of ADR-0006's DoS
     * fix) -- and, unlike that earlier round, the ratio this constant is actually *verified* at:
     * every dense-random-inconsistent-graph measurement behind [MAX_POINTS]'s KDoc uses exactly
     * `constraints = 2 * points`, including at the `n=64` cap itself. A separate check with `points`
     * held far below [MAX_POINTS] (as low as 4) and `constraints` pushed to this constant's full
     * 128 via a redundant multigraph on those few points ran in under 200ms -- confirming, as
     * before, that constraint count independent of point count is not this module's primary cost
     * driver; see [MAX_POINTS]'s KDoc for the measurements that are.
     *
     * This constant counts Kotlin-level [SketchConstraint]s, NOT native constraint rows --
     * [CoincidenceConstraint] alone lowers to *two* native `ConstraintEqual` rows per Kotlin
     * constraint (see its KDoc), so [MAX_CONSTRAINTS] worth of coincidences can produce up to twice
     * as many native rows as the same count of distance/H/V constraints. See `docs/adr/ADR-0007`'s
     * DoS section for whether that asymmetry required its own, separate cap.
     */
    const val MAX_CONSTRAINTS: Int = 128

    /**
     * Upper bound on [solve]'s `maxIterations` parameter.
     *
     * Point count, not this constant, is this module's primary DoS lever -- see [MAX_POINTS]'s
     * KDoc, whose measurements are already taken at this constant's full 1000-iteration budget.
     * wall time does **not** stay flat across `maxIterations` for the topology that actually
     * matters here: on the dense random inconsistent graph at n=512 / m=1024, raising
     * `maxIterations` from the default 100 to this constant's 1000 roughly quadrupled wall time
     * (47s to over three minutes) rather than leaving it unchanged -- PlaneGCS's convergence-stall
     * detection does not give up early on this topology the way it does on the chain/ring shapes an
     * earlier draft of this KDoc was based on. This constant stays at 1_000 (10x PlaneGCS's own
     * default, [DEFAULT_MAX_ITERATIONS]) precisely because [MAX_POINTS] was lowered enough that
     * even this full iteration budget, combined with [MAX_CONSTRAINTS], measures safely under one
     * second on every topology tried -- see [MAX_POINTS]'s KDoc. Lowering [MAX_ITERATIONS] further
     * was considered and rejected: it would shrink the margin for legitimately slow-but-convergent
     * systems without addressing the actual cost driver.
     */
    const val MAX_ITERATIONS: Int = 1_000

    /** `GCS::System()`'s own default `maxIter` (verified against `third_party/planegcs/GCS.cpp:475`). */
    const val DEFAULT_MAX_ITERATIONS: Int = 100

    /** `GCS::System()`'s own default `convergence` (verified against `third_party/planegcs/GCS.cpp`). */
    const val DEFAULT_CONVERGENCE: Double = 1e-10

    /** Upper bound on the absolute value of any point coordinate accepted by [solve]. */
    const val MAX_ABS_COORDINATE: Double = 1e9

    /** Upper bound on any [DistanceConstraint.distance] accepted by [solve]. */
    const val MAX_DISTANCE: Double = 1e9

    /**
     * Whether the native bridge is available on this JVM, and diagnostic detail either way. Resolved
     * once per process and cached -- see [PlaneGcsNativeLibrary.availability].
     */
    fun availability(): PlaneGcsAvailability = PlaneGcsNativeLibrary.availability

    /**
     * The vendored PlaneGCS source commit the loaded native library was built from (see
     * `kstep-constraints/src/main/cpp/third_party/planegcs/PROVENANCE.adoc`).
     *
     * @throws PlaneGcsUnavailableException if the native bridge is not available -- see [availability].
     */
    fun planeGcsSourceCommit(): String =
        when (val a = availability()) {
            is PlaneGcsAvailability.Available -> a.planeGcsCommit
            is PlaneGcsAvailability.Unavailable -> throw PlaneGcsUnavailableException(a.reason, a.cause)
        }

    /**
     * Solves a 2D point system under a set of [SketchConstraint]s, starting from each point's
     * initial position in [points].
     *
     * Every argument is validated *before* any native call is made, so a malformed request never
     * reaches the solver at all -- see the `@throws` list below. Availability is checked only
     * *after* validation succeeds, matching `dev.kstep.geometry.OcctKernel.makeBox`'s pattern, so
     * that pure input-validation tests pass even on a machine without the native bridge built.
     *
     * After a native solve that reports [SolveStatus.SUCCESS] or [SolveStatus.CONVERGED], every
     * returned coordinate is checked for [Double.isFinite] before this function returns a
     * [SolveResult] -- see [PointOnLineConstraint]'s KDoc for the concrete way a non-finite result
     * can arise (a division-by-near-zero inside PlaneGCS's own `ConstraintPointOnLine::error()`/
     * `grad()`). A NaN/Infinity coordinate is treated as a solver failure
     * ([ConstraintSolverException]), never as a "successful" [SolveResult] a caller could
     * unknowingly propagate.
     *
     * @param points the points to solve for; must be non-empty and contain at least one non-[SketchPoint.fixed]
     *   point (an all-fixed system has no unknowns to solve for -- reject it here rather than pass an
     *   empty unknowns list into the native solver, whose behavior for that input is not verified;
     *   see `docs/adr/ADR-0006`'s "Environment note").
     * @param constraints the constraints to satisfy; see [SketchConstraint] and its implementations.
     * @param maxIterations solver iteration budget; defaults to PlaneGCS's own default ([DEFAULT_MAX_ITERATIONS]).
     * @param convergence solver convergence threshold; defaults to PlaneGCS's own default ([DEFAULT_CONVERGENCE]).
     * @throws IllegalArgumentException if `points`/`constraints`/`maxIterations`/`convergence` violate
     *   any bound documented on this object's constants, if any constraint references an
     *   out-of-range or duplicate point index, or if every point is [SketchPoint.fixed].
     * @throws PlaneGcsUnavailableException if the native bridge is not available.
     * @throws ConstraintSolverException if the native bridge itself reports an unrecoverable error,
     *   or if the native result contains a non-finite coordinate (see above).
     */
    fun solve(
        points: List<SketchPoint>,
        constraints: List<SketchConstraint>,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        convergence: Double = DEFAULT_CONVERGENCE,
    ): SolveResult {
        validate(points, constraints, maxIterations, convergence)

        val a = availability()
        if (a !is PlaneGcsAvailability.Available) {
            val unavailable = a as PlaneGcsAvailability.Unavailable
            throw PlaneGcsUnavailableException(unavailable.reason, unavailable.cause)
        }

        val coords = DoubleArray(points.size * 2)
        val fixedFlags = IntArray(points.size)
        points.forEachIndexed { i, p ->
            coords[2 * i] = p.x
            coords[2 * i + 1] = p.y
            fixedFlags[i] = if (p.fixed) 1 else 0
        }
        val encoded = encodeConstraints(constraints)
        val outCoords = DoubleArray(points.size * 2)

        val nativeStatus =
            try {
                PlaneGcsBridge.nativeSolveConstraints(
                    coords,
                    fixedFlags,
                    encoded.kinds,
                    encoded.points,
                    encoded.params,
                    maxIterations,
                    convergence,
                    outCoords,
                )
            } catch (e: RuntimeException) {
                throw ConstraintSolverException("PlaneGCS failed to solve the constraint system", e)
            }
        if (nativeStatus < 0) {
            // The native bridge's own validation rejected the call (see kstep_planegcs_bridge.cpp) --
            // reachable in practice only via dev.kstep.constraints.planegcs.PlaneGcsBridge called
            // directly (this function's own validation above already excludes every input that would
            // trigger it), but handled defensively rather than assumed unreachable.
            throw ConstraintSolverException(
                "PlaneGCS native bridge rejected the solve request (native status $nativeStatus)",
            )
        }
        val status = SolveStatus.fromNative(nativeStatus)

        if (status == SolveStatus.SUCCESS || status == SolveStatus.CONVERGED) {
            // See this function's own KDoc and PointOnLineConstraint's KDoc: a non-finite coordinate
            // must never reach a caller labeled as a "successful" solve.
            for (i in outCoords.indices) {
                if (!outCoords[i].isFinite()) {
                    throw ConstraintSolverException(
                        "PlaneGCS reported status $status but produced a non-finite coordinate " +
                            "at outCoords[$i] (${outCoords[i]}) -- refusing to return a NaN-polluted result",
                    )
                }
            }
        }

        val resultPoints =
            points.indices.map { i ->
                SketchPoint(x = outCoords[2 * i], y = outCoords[2 * i + 1], fixed = points[i].fixed)
            }
        return SolveResult(status = status, points = resultPoints)
    }

    /**
     * Convenience overload for the common all-distance case, delegating entirely to [solve]. Kept
     * so every call site (and this module's own pre-existing distance-only test suite) written
     * against the original, distance-only wave stays source-compatible -- see `docs/adr/ADR-0007`'s
     * "Decision" for why `nativeSolveP2PDistances` itself was replaced rather than
     * kept alongside [PlaneGcsBridge.nativeSolveConstraints] as a second native entry point.
     */
    fun solveDistances(
        points: List<SketchPoint>,
        constraints: List<DistanceConstraint>,
        maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        convergence: Double = DEFAULT_CONVERGENCE,
    ): SolveResult = solve(points, constraints, maxIterations, convergence)

    private fun validate(
        points: List<SketchPoint>,
        constraints: List<SketchConstraint>,
        maxIterations: Int,
        convergence: Double,
    ) {
        require(points.isNotEmpty()) { "points must not be empty" }
        require(points.size <= MAX_POINTS) { "points.size (${points.size}) exceeds MAX_POINTS ($MAX_POINTS)" }
        require(constraints.size <= MAX_CONSTRAINTS) {
            "constraints.size (${constraints.size}) exceeds MAX_CONSTRAINTS ($MAX_CONSTRAINTS)"
        }
        points.forEachIndexed { i, p ->
            require(p.x.isFinite()) { "points[$i].x must be finite, got ${p.x}" }
            require(p.y.isFinite()) { "points[$i].y must be finite, got ${p.y}" }
            require(kotlin.math.abs(p.x) <= MAX_ABS_COORDINATE) {
                "points[$i].x (${p.x}) exceeds MAX_ABS_COORDINATE ($MAX_ABS_COORDINATE)"
            }
            require(kotlin.math.abs(p.y) <= MAX_ABS_COORDINATE) {
                "points[$i].y (${p.y}) exceeds MAX_ABS_COORDINATE ($MAX_ABS_COORDINATE)"
            }
        }
        require(points.any { !it.fixed }) {
            "points must contain at least one non-fixed point (an all-fixed system has no unknowns)"
        }
        constraints.forEachIndexed { i, c -> validateConstraint(i, c, points) }
        require(maxIterations in 1..MAX_ITERATIONS) {
            "maxIterations ($maxIterations) must be in 1..$MAX_ITERATIONS"
        }
        require(convergence.isFinite() && convergence > 0.0) {
            "convergence must be finite and positive, got $convergence"
        }
    }

    private fun requireInRange(
        index: Int,
        pointIndex: Int,
        fieldName: String,
        points: List<SketchPoint>,
    ) {
        require(pointIndex in points.indices) {
            "constraints[$index].$fieldName ($pointIndex) is out of range for ${points.size} points"
        }
    }

    private fun validateConstraint(
        index: Int,
        constraint: SketchConstraint,
        points: List<SketchPoint>,
    ) {
        when (constraint) {
            is DistanceConstraint -> {
                requireInRange(index, constraint.pointA, "pointA", points)
                requireInRange(index, constraint.pointB, "pointB", points)
                require(constraint.pointA != constraint.pointB) {
                    "constraints[$index] references the same point twice (index ${constraint.pointA})"
                }
                require(constraint.distance.isFinite()) {
                    "constraints[$index].distance must be finite, got ${constraint.distance}"
                }
                require(constraint.distance > 0.0) {
                    "constraints[$index].distance must be positive, got ${constraint.distance}"
                }
                require(constraint.distance <= MAX_DISTANCE) {
                    "constraints[$index].distance (${constraint.distance}) exceeds MAX_DISTANCE ($MAX_DISTANCE)"
                }
            }
            is CoincidenceConstraint -> {
                requireInRange(index, constraint.pointA, "pointA", points)
                requireInRange(index, constraint.pointB, "pointB", points)
                require(constraint.pointA != constraint.pointB) {
                    "constraints[$index] references the same point twice (index ${constraint.pointA})"
                }
            }
            is HorizontalConstraint -> {
                requireInRange(index, constraint.pointA, "pointA", points)
                requireInRange(index, constraint.pointB, "pointB", points)
                require(constraint.pointA != constraint.pointB) {
                    "constraints[$index] references the same point twice (index ${constraint.pointA})"
                }
            }
            is VerticalConstraint -> {
                requireInRange(index, constraint.pointA, "pointA", points)
                requireInRange(index, constraint.pointB, "pointB", points)
                require(constraint.pointA != constraint.pointB) {
                    "constraints[$index] references the same point twice (index ${constraint.pointA})"
                }
            }
            is PointOnLineConstraint -> {
                requireInRange(index, constraint.point, "point", points)
                requireInRange(index, constraint.lineFrom, "lineFrom", points)
                requireInRange(index, constraint.lineTo, "lineTo", points)
                // Pairwise-distinct: see PointOnLineConstraint's KDoc for why point == lineFrom/lineTo
                // and lineFrom == lineTo are both rejected, not just the degenerate line case alone.
                require(constraint.point != constraint.lineFrom) {
                    "constraints[$index].point must differ from lineFrom (index ${constraint.point})"
                }
                require(constraint.point != constraint.lineTo) {
                    "constraints[$index].point must differ from lineTo (index ${constraint.point})"
                }
                require(constraint.lineFrom != constraint.lineTo) {
                    "constraints[$index].lineFrom must differ from lineTo (index ${constraint.lineFrom})"
                }
            }
            is ParallelConstraint -> {
                validateFourPointLegs(
                    index = index,
                    kindName = "ParallelConstraint",
                    aFrom = constraint.lineAFrom,
                    aTo = constraint.lineATo,
                    bFrom = constraint.lineBFrom,
                    bTo = constraint.lineBTo,
                    points = points,
                )
            }
            is PerpendicularConstraint -> {
                validateFourPointLegs(
                    index = index,
                    kindName = "PerpendicularConstraint",
                    aFrom = constraint.lineAFrom,
                    aTo = constraint.lineATo,
                    bFrom = constraint.lineBFrom,
                    bTo = constraint.lineBTo,
                    points = points,
                )
            }
        }
    }

    /**
     * Shared validation for [ParallelConstraint] and [PerpendicularConstraint] -- both are
     * structurally identical (two legs, four point indices), and both carry the exact same
     * degeneracy hazards; see either type's KDoc for the full reasoning behind each rule below.
     *
     * Order of checks matters: range first (so later checks can safely index into [points]),
     * then index-level degeneracy (leg self-distinctness, then cross-leg identical-pair
     * rejection), then finally the coordinate-level check (rule 5) -- which is the module's first
     * constraint validation that inspects point *coordinates* rather than only indices, and
     * therefore must run after every earlier check already guarantees valid, in-range indices.
     */
    private fun validateFourPointLegs(
        index: Int,
        kindName: String,
        aFrom: Int,
        aTo: Int,
        bFrom: Int,
        bTo: Int,
        points: List<SketchPoint>,
    ) {
        requireInRange(index, aFrom, "lineAFrom", points)
        requireInRange(index, aTo, "lineATo", points)
        requireInRange(index, bFrom, "lineBFrom", points)
        requireInRange(index, bTo, "lineBTo", points)

        // Rule 2: each leg needs two distinct points -- a self-referencing leg (aFrom == aTo) is
        // degenerate regardless of the other leg.
        require(aFrom != aTo) {
            "constraints[$index] ($kindName).lineAFrom must differ from lineATo (index $aFrom)"
        }
        require(bFrom != bTo) {
            "constraints[$index] ($kindName).lineBFrom must differ from lineBTo (index $bFrom)"
        }

        // Rule 3 (deliberately NOT full pairwise distinctness): a shared endpoint between the two
        // legs (e.g. aTo == bFrom) is explicitly PERMITTED -- it is the normal way to express a
        // rectangle corner or a chamfer (see ParallelConstraint/PerpendicularConstraint's KDoc and
        // the T18/T23 regression tests). Do not "fix" this into requiring all four indices
        // pairwise distinct -- that would reject the single most common real sketch shape this
        // constraint type exists for.

        // Rule 4: the two legs, as unordered index pairs, must not be identical -- (a,b,a,b) and
        // (a,b,b,a) both make the residual a constant (see each type's KDoc for why), a degenerate
        // encoding of "no constraint at all" rather than a legitimate use.
        val sameOrder = aFrom == bFrom && aTo == bTo
        val swappedOrder = aFrom == bTo && aTo == bFrom
        require(!sameOrder && !swappedOrder) {
            "constraints[$index] ($kindName) references the same leg twice as lineA and lineB " +
                "(indices [$aFrom, $aTo])"
        }

        // Rule 5: neither leg may start at zero length -- PlaneGCS's Constraint::rescale() runs
        // exactly once, in the constructor, from these INITIAL coordinates (see
        // ParallelConstraint's KDoc for the full citation and reasoning); a zero-length leg here
        // makes the constraint permanently NaN for the whole solve rather than merely failing to
        // converge, so it is rejected loudly here instead. Exact equality only, deliberately no
        // epsilon threshold -- see docs/adr/ADR-0014's "measured, bounded decision" section for why.
        val pa = points[aFrom]
        val pb = points[aTo]
        require(pa.x != pb.x || pa.y != pb.y) {
            "constraints[$index] ($kindName).lineAFrom/lineATo (indices $aFrom/$aTo) start at " +
                "identical coordinates ($pa) -- PlaneGCS computes this constraint's scale factor " +
                "once, from these initial coordinates, and a zero-length leg makes it infinite"
        }
        val pc = points[bFrom]
        val pd = points[bTo]
        require(pc.x != pd.x || pc.y != pd.y) {
            "constraints[$index] ($kindName).lineBFrom/lineBTo (indices $bFrom/$bTo) start at " +
                "identical coordinates ($pc) -- PlaneGCS computes this constraint's scale factor " +
                "once, from these initial coordinates, and a zero-length leg makes it infinite"
        }
    }

    /** Result of [encodeConstraints]: the three parallel arrays [PlaneGcsBridge.nativeSolveConstraints] expects. */
    private data class EncodedConstraints(
        val kinds: IntArray,
        val points: IntArray,
        val params: DoubleArray,
    )

    private fun SketchConstraint.kind(): NativeConstraintKind =
        when (this) {
            is DistanceConstraint -> NativeConstraintKind.P2P_DISTANCE
            is CoincidenceConstraint -> NativeConstraintKind.P2P_COINCIDENT
            is HorizontalConstraint -> NativeConstraintKind.HORIZONTAL
            is VerticalConstraint -> NativeConstraintKind.VERTICAL
            is PointOnLineConstraint -> NativeConstraintKind.POINT_ON_LINE
            is ParallelConstraint -> NativeConstraintKind.PARALLEL
            is PerpendicularConstraint -> NativeConstraintKind.PERPENDICULAR
        }

    private fun SketchConstraint.pointIndices(): IntArray =
        when (this) {
            is DistanceConstraint -> intArrayOf(pointA, pointB)
            is CoincidenceConstraint -> intArrayOf(pointA, pointB)
            is HorizontalConstraint -> intArrayOf(pointA, pointB)
            is VerticalConstraint -> intArrayOf(pointA, pointB)
            is PointOnLineConstraint -> intArrayOf(point, lineFrom, lineTo)
            is ParallelConstraint -> intArrayOf(lineAFrom, lineATo, lineBFrom, lineBTo)
            is PerpendicularConstraint -> intArrayOf(lineAFrom, lineATo, lineBFrom, lineBTo)
        }

    private fun SketchConstraint.parameter(): Double =
        when (this) {
            is DistanceConstraint -> distance
            is CoincidenceConstraint -> NativeConstraintKind.UNUSED_PARAM
            is HorizontalConstraint -> NativeConstraintKind.UNUSED_PARAM
            is VerticalConstraint -> NativeConstraintKind.UNUSED_PARAM
            is PointOnLineConstraint -> NativeConstraintKind.UNUSED_PARAM
            is ParallelConstraint -> NativeConstraintKind.UNUSED_PARAM
            is PerpendicularConstraint -> NativeConstraintKind.UNUSED_PARAM
        }

    private fun encodeConstraints(constraints: List<SketchConstraint>): EncodedConstraints {
        val kinds = IntArray(constraints.size)
        val pointSlots = IntArray(constraints.size * NativeConstraintKind.POINT_SLOTS)
        val params = DoubleArray(constraints.size)
        constraints.forEachIndexed { i, c ->
            val kind = c.kind()
            kinds[i] = kind.nativeValue
            val indices = c.pointIndices()
            require(indices.size == kind.arity) {
                "${kind.name} constraint at index $i encoded ${indices.size} point indices, " +
                    "but its arity is ${kind.arity}"
            }
            val base = i * NativeConstraintKind.POINT_SLOTS
            for (slot in 0 until NativeConstraintKind.POINT_SLOTS) {
                pointSlots[base + slot] =
                    if (slot < kind.arity) indices[slot] else NativeConstraintKind.UNUSED_POINT_SLOT
            }
            params[i] = c.parameter()
        }
        return EncodedConstraints(kinds = kinds, points = pointSlots, params = params)
    }
}
