package dev.kstep.constraints

import dev.kstep.constraints.planegcs.PlaneGcsBridge
import dev.kstep.constraints.planegcs.PlaneGcsNativeLibrary

/**
 * Public entry point to the PlaneGCS 2D geometric constraint solver -- the only public type in this
 * wave besides the plain data carriers ([SketchPoint], [DistanceConstraint], [SolveResult],
 * [SolveStatus]) and the two exception types.
 *
 * kSTEP vendors the PlaneGCS solver sources directly into this repository (unlike kstep-geometry's
 * OCCT bridge, which links against a system-installed library) and compiles them into a JNI shim --
 * see `kstep-constraints/src/main/cpp/third_party/planegcs/PROVENANCE.adoc` for exactly what is
 * vendored and from where, and `docs/adr/ADR-0006-planegcs-constraint-bridge.adoc` for the full
 * rationale, license analysis, and this wave's scope. When the Eigen/Boost dev headers or a C++
 * compiler were absent at build time (or on any platform other than linux-x86-64, the only one
 * built in this wave), the native shim is never compiled, no `.so` classpath resource exists, and
 * [availability] reports [PlaneGcsAvailability.Unavailable] -- every function here then fails
 * predictably with [PlaneGcsUnavailableException] rather than the build, or this whole module,
 * failing outright. See README's "Building" section for the `-Pkstep.planegcs.require=true` flag
 * that turns this into a hard build failure instead, for environments that must guarantee the
 * bridge is present.
 *
 * This object's solving API is deliberately a **one-shot, stateless** call: [solveDistances] builds
 * a fresh native `GCS::System` for exactly the problem passed in, solves it, and returns. There is
 * no persistent solver handle to [AutoCloseable.close] and no way to add constraints incrementally
 * across multiple calls -- see `docs/adr/ADR-0006`'s "Decision" for why, and its "Folge-Wellen" for
 * the later, handle-based wave that will add that.
 */
object PlaneGcsSolver {
    /**
     * Upper bound on the number of points a single [solveDistances] call may pass.
     *
     * This is an actual, empirically-measured wall-clock bound, not a placeholder value:
     * [solveDistances] is a single, non-interruptible JNI call -- no timeout parameter exists, and
     * `Thread.interrupt()` has no effect on native code already inside PlaneGCS's solve loop (see
     * this object's class KDoc) -- so [MAX_POINTS] is the primary thing standing between a caller
     * and an unbounded native CPU burn on the calling thread.
     *
     * A previous value of 4096 was found, during this wave's DoS review, to let a single call hang
     * past 10 minutes on a *chain* topology (`n` points / `n-1` distance constraints). A follow-up
     * value of 512 was then found -- in a **second** round of this same review -- to still be
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
     * `docs/adr/ADR-0006`'s "Security" table for the full measurement table and the reproduction
     * harness referenced there.
     *
     * This is still an empirical bound on the topologies this wave constructed, not a mathematical
     * proof of a worst case -- some other adversarial shape not tried here could cost more at this
     * same `n`. [MAX_POINTS] is deliberately set with roughly 2x headroom below one second (rather
     * than at the exact edge of what was measured) to absorb that residual risk, but a caller that
     * needs a hard wall-clock guarantee should still enforce its own timeout around the calling
     * thread (e.g. running [solveDistances] on a dedicated thread/executor and abandoning --
     * **not** interrupting, see this object's class KDoc -- that thread past a deadline) rather
     * than trusting this constant alone. A future incremental/handle-based solver (ADR-0006,
     * "Folge-Wellen") is the intended path for sketches larger than this one-shot call can safely
     * serve -- not raising this constant.
     */
    const val MAX_POINTS: Int = 64

    /**
     * Upper bound on the number of constraints a single [solveDistances] call may pass. Kept at 2x
     * [MAX_POINTS], the same ratio used throughout this constant's history (both the original
     * pre-DoS-fix values and the still-unsafe 512/1024 pair from the first round of this wave's DoS
     * fix) -- and, unlike that earlier round, the ratio this constant is actually *verified* at:
     * every dense-random-inconsistent-graph measurement behind [MAX_POINTS]'s KDoc uses exactly
     * `constraints = 2 * points`, including at the `n=64` cap itself. A separate check with `points`
     * held far below [MAX_POINTS] (as low as 4) and `constraints` pushed to this constant's full
     * 128 via a redundant multigraph on those few points ran in under 200ms -- confirming, as
     * before, that constraint count independent of point count is not this module's primary cost
     * driver; see [MAX_POINTS]'s KDoc for the measurements that are.
     */
    const val MAX_CONSTRAINTS: Int = 128

    /**
     * Upper bound on [solveDistances]'s `maxIterations` parameter.
     *
     * Point count, not this constant, is this module's primary DoS lever -- see [MAX_POINTS]'s
     * KDoc, whose measurements are already taken at this constant's full 1000-iteration budget.
     * Unlike an earlier draft of this KDoc (corrected in the same review round that lowered
     * [MAX_POINTS] from 512 to 64), wall time does **not** stay flat across `maxIterations` for the
     * topology that actually matters here: on the dense random inconsistent graph at n=512 /
     * m=1024, raising `maxIterations` from the default 100 to this constant's 1000 roughly
     * quadrupled wall time (47s to over three minutes) rather than leaving it unchanged --
     * PlaneGCS's convergence-stall detection does not give up early on this topology the way it
     * does on the chain/ring shapes this constant's previous KDoc was based on. This constant stays
     * at 1_000 (10x PlaneGCS's own default, [DEFAULT_MAX_ITERATIONS]) precisely because [MAX_POINTS]
     * was lowered enough that even this full iteration budget, combined with [MAX_CONSTRAINTS], measures
     * safely under one second on every topology tried -- see [MAX_POINTS]'s KDoc. Lowering
     * [MAX_ITERATIONS] further was considered and rejected: it would shrink the margin for
     * legitimately slow-but-convergent systems without addressing the actual cost driver.
     */
    const val MAX_ITERATIONS: Int = 1_000

    /** `GCS::System()`'s own default `maxIter` (verified against `third_party/planegcs/GCS.cpp:475`). */
    const val DEFAULT_MAX_ITERATIONS: Int = 100

    /** `GCS::System()`'s own default `convergence` (verified against `third_party/planegcs/GCS.cpp`). */
    const val DEFAULT_CONVERGENCE: Double = 1e-10

    /** Upper bound on the absolute value of any point coordinate accepted by [solveDistances]. */
    const val MAX_ABS_COORDINATE: Double = 1e9

    /** Upper bound on any [DistanceConstraint.distance] accepted by [solveDistances]. */
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
     * Solves a 2D point system under a set of point-to-point [constraints], starting from each
     * point's initial position in [points].
     *
     * Every argument is validated *before* any native call is made, so a malformed request never
     * reaches the solver at all -- see the `@throws` list below. Availability is checked only
     * *after* validation succeeds, matching `dev.kstep.geometry.OcctKernel.makeBox`'s pattern, so
     * that pure input-validation tests pass even on a machine without the native bridge built.
     *
     * @param points the points to solve for; must be non-empty and contain at least one non-[SketchPoint.fixed]
     *   point (an all-fixed system has no unknowns to solve for -- reject it here rather than pass an
     *   empty unknowns list into the native solver, whose behavior for that input this wave has not
     *   verified; see `docs/adr/ADR-0006`'s "Environment note").
     * @param constraints the distance constraints to satisfy.
     * @param maxIterations solver iteration budget; defaults to PlaneGCS's own default ([DEFAULT_MAX_ITERATIONS]).
     * @param convergence solver convergence threshold; defaults to PlaneGCS's own default ([DEFAULT_CONVERGENCE]).
     * @throws IllegalArgumentException if `points`/`constraints`/`maxIterations`/`convergence` violate
     *   any bound documented on this object's constants, if any [DistanceConstraint] references an
     *   out-of-range or duplicate point index, or if every point is [SketchPoint.fixed].
     * @throws PlaneGcsUnavailableException if the native bridge is not available.
     * @throws ConstraintSolverException if the native bridge itself reports an unrecoverable error.
     */
    fun solveDistances(
        points: List<SketchPoint>,
        constraints: List<DistanceConstraint>,
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
        val constraintA = IntArray(constraints.size)
        val constraintB = IntArray(constraints.size)
        val constraintDist = DoubleArray(constraints.size)
        constraints.forEachIndexed { i, c ->
            constraintA[i] = c.pointA
            constraintB[i] = c.pointB
            constraintDist[i] = c.distance
        }
        val outCoords = DoubleArray(points.size * 2)

        val nativeStatus =
            try {
                PlaneGcsBridge.nativeSolveP2PDistances(
                    coords,
                    fixedFlags,
                    constraintA,
                    constraintB,
                    constraintDist,
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

        val resultPoints =
            points.indices.map { i ->
                SketchPoint(x = outCoords[2 * i], y = outCoords[2 * i + 1], fixed = points[i].fixed)
            }
        return SolveResult(status = status, points = resultPoints)
    }

    private fun validate(
        points: List<SketchPoint>,
        constraints: List<DistanceConstraint>,
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
        constraints.forEachIndexed { i, c ->
            require(c.pointA in points.indices) {
                "constraints[$i].pointA (${c.pointA}) is out of range for ${points.size} points"
            }
            require(c.pointB in points.indices) {
                "constraints[$i].pointB (${c.pointB}) is out of range for ${points.size} points"
            }
            require(c.pointA != c.pointB) {
                "constraints[$i] references the same point twice (index ${c.pointA})"
            }
            require(c.distance.isFinite()) { "constraints[$i].distance must be finite, got ${c.distance}" }
            require(c.distance > 0.0) { "constraints[$i].distance must be positive, got ${c.distance}" }
            require(c.distance <= MAX_DISTANCE) {
                "constraints[$i].distance (${c.distance}) exceeds MAX_DISTANCE ($MAX_DISTANCE)"
            }
        }
        require(maxIterations in 1..MAX_ITERATIONS) {
            "maxIterations ($maxIterations) must be in 1..$MAX_ITERATIONS"
        }
        require(convergence.isFinite() && convergence > 0.0) {
            "convergence must be finite and positive, got $convergence"
        }
    }
}
