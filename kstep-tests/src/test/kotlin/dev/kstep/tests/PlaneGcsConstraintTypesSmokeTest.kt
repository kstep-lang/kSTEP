package dev.kstep.tests

import dev.kstep.constraints.CoincidenceConstraint
import dev.kstep.constraints.ConstraintSolverException
import dev.kstep.constraints.DistanceConstraint
import dev.kstep.constraints.HorizontalConstraint
import dev.kstep.constraints.ParallelConstraint
import dev.kstep.constraints.PerpendicularConstraint
import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.constraints.PointOnLineConstraint
import dev.kstep.constraints.SketchPoint
import dev.kstep.constraints.SolveResult
import dev.kstep.constraints.SolveStatus
import dev.kstep.constraints.VerticalConstraint
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

/**
 * End-to-end proof for kSTEP's PlaneGCS constraint vocabulary beyond plain point-to-point
 * distances -- [CoincidenceConstraint], [HorizontalConstraint], [VerticalConstraint],
 * [PointOnLineConstraint] (see `docs/adr/ADR-0007-planegcs-additional-constraint-types.adoc`),
 * and [ParallelConstraint]/[PerpendicularConstraint] (T15 onward, see
 * `docs/adr/ADR-0014-planegcs-parallel-and-perpendicular.adoc`).
 *
 * Same `.config(enabled = available)` gating discipline as [PlaneGcsBridgeSmokeTest]: every
 * PlaneGCS-dependent case is skipped, not failed, on a machine without the native bridge built,
 * except the pure-Kotlin validation case at the bottom.
 *
 * Every numeric expectation below was verified against a real PlaneGCS build during this wave's
 * implementation -- not derived from the distance-only wave's numbers, and not guessed. In
 * particular: the "contradictory" cases ([T3], [T10]) assert [SolveStatus.FAILED] because that is
 * what a real run against these exact inputs produced (both leave the free point at its unmoved
 * starting position -- also observed, not assumed) -- `ConstraintEqual`'s own least-squares
 * character means a different contradictory construction could just as easily converge to a
 * degenerate minimum instead; this suite does not generalize beyond what these exact inputs were
 * seen to do. T20 is the [ParallelConstraint]/[PerpendicularConstraint] analogue of that same
 * caution -- see its own comment for why it does NOT assert `FAILED` the way T3/T10 do.
 */
class PlaneGcsConstraintTypesSmokeTest :
    StringSpec({
        val availability = PlaneGcsSolver.availability()
        val available = availability is PlaneGcsAvailability.Available

        // Test-local, not added to SolveResult itself: this angle helper maps 1:1 onto neither
        // ParallelConstraint nor PerpendicularConstraint as cleanly as distanceBetween(...) maps
        // onto DistanceConstraint (atan2 differences amplify near-degenerate legs -- see the
        // numerical-tolerance note this suite's cases below follow: prefer the raw cross/dot
        // residual over a derived angle wherever the assertion reads just as well either way).
        fun SolveResult.legAngleDeg(
            from: Int,
            to: Int,
        ): Double {
            val a = points[from]
            val b = points[to]
            return Math.toDegrees(atan2(b.y - a.y, b.x - a.x))
        }

        "coincidence pulls a free point exactly onto a fixed one".config(enabled = available) {
            // T1
            val result =
                PlaneGcsSolver.solve(
                    points = listOf(SketchPoint(3.0, 4.0, fixed = true), SketchPoint(0.0, 0.0)),
                    constraints = listOf(CoincidenceConstraint(0, 1)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            result.points[1].x shouldBe (3.0 plusOrMinus 1e-9)
            result.points[1].y shouldBe (4.0 plusOrMinus 1e-9)
        }

        "coincidence and distance combine correctly".config(enabled = available) {
            // T2: P1 and P2 are forced coincident, and P0-P1 must be 5.0 apart. Direction is NOT
            // asserted (mirror-image solutions are equally valid, see PlaneGcsBridgeSmokeTest's
            // "Stolperfallen" note) -- only the invariants a caller actually depends on.
            val result =
                PlaneGcsSolver.solve(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 1.0), SketchPoint(7.0, 3.0)),
                    constraints = listOf(CoincidenceConstraint(1, 2), DistanceConstraint(0, 1, 5.0)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            result.points[1].x shouldBe (result.points[2].x plusOrMinus 1e-9)
            result.points[1].y shouldBe (result.points[2].y plusOrMinus 1e-9)
            result.distanceBetween(0, 1) shouldBe (5.0 plusOrMinus 1e-6)
        }

        "contradictory coincidences fail cleanly".config(enabled = available) {
            // T3: P2 cannot be coincident with both P0=(0,0) and P1=(5,0), two DIFFERENT fixed
            // points -- verified to produce FAILED with the free point left at its starting
            // position, not e.g. a least-squares midpoint compromise (unlike the DistanceConstraint
            // contradiction case in PlaneGcsBridgeSmokeTest, whose ConstraintP2PDistance residual
            // does not admit that particular degenerate minimum either, but was not re-verified
            // here for coincidence specifically until this run).
            val result =
                PlaneGcsSolver.solve(
                    points =
                        listOf(
                            SketchPoint(0.0, 0.0, fixed = true),
                            SketchPoint(5.0, 0.0, fixed = true),
                            SketchPoint(2.0, 1.0),
                        ),
                    constraints = listOf(CoincidenceConstraint(0, 2), CoincidenceConstraint(1, 2)),
                )
            result.status shouldBe SolveStatus.FAILED
            result.solved shouldBe false
        }

        "horizontal equalizes Y, not X".config(enabled = available) {
            // T4: only Y is constrained -- X is free to drift, and with no other constraint on it,
            // a real run leaves it at its starting guess (verified, not assumed).
            val result =
                PlaneGcsSolver.solve(
                    points = listOf(SketchPoint(0.0, 7.0, fixed = true), SketchPoint(4.0, 1.0)),
                    constraints = listOf(HorizontalConstraint(0, 1)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            result.points[1].y shouldBe (7.0 plusOrMinus 1e-9)
        }

        "vertical equalizes X, not Y".config(enabled = available) {
            // T5
            val result =
                PlaneGcsSolver.solve(
                    points = listOf(SketchPoint(7.0, 0.0, fixed = true), SketchPoint(1.0, 4.0)),
                    constraints = listOf(VerticalConstraint(0, 1)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            result.points[1].x shouldBe (7.0 plusOrMinus 1e-9)
        }

        "an axis-aligned rectangle solves from horizontal/vertical/distance constraints together".config(
            enabled = available,
        ) {
            // T6 (flagship): four corners forced into a 10x4 axis-aligned rectangle purely from
            // Horizontal/Vertical/Distance constraints, no coordinates asserted a priori beyond the
            // one fixed corner -- verified against a real run to land exactly on the expected
            // rectangle (not a mirror or rotated variant) from this starting guess.
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(9.0, 1.0),
                    SketchPoint(8.0, 6.0),
                    SketchPoint(1.0, 5.0),
                )
            val constraints =
                listOf(
                    HorizontalConstraint(0, 1),
                    VerticalConstraint(1, 2),
                    HorizontalConstraint(3, 2),
                    VerticalConstraint(0, 3),
                    DistanceConstraint(0, 1, 10.0),
                    DistanceConstraint(1, 2, 4.0),
                )
            val result = PlaneGcsSolver.solve(points, constraints)
            result.status shouldBe SolveStatus.SUCCESS
            val p0 = result.points[0]
            val p1 = result.points[1]
            val p2 = result.points[2]
            val p3 = result.points[3]
            p1.y shouldBe (p0.y plusOrMinus 1e-6)
            abs(p1.x - p0.x) shouldBe (10.0 plusOrMinus 1e-6)
            p2.x shouldBe (p1.x plusOrMinus 1e-6)
            abs(p2.y - p1.y) shouldBe (4.0 plusOrMinus 1e-6)
            p3.x shouldBe (p0.x plusOrMinus 1e-6)
            p3.y shouldBe (p2.y plusOrMinus 1e-6)
        }

        "a point lands on the infinite line through two fixed points (axis-aligned)".config(enabled = available) {
            // T7
            val result =
                PlaneGcsSolver.solve(
                    points =
                        listOf(
                            SketchPoint(0.0, 0.0, fixed = true),
                            SketchPoint(10.0, 0.0, fixed = true),
                            SketchPoint(4.0, 3.0),
                        ),
                    constraints = listOf(PointOnLineConstraint(2, 0, 1)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            result.points[2].y shouldBe (0.0 plusOrMinus 1e-6)
        }

        "a point lands on the infinite line through two fixed points (diagonal)".config(enabled = available) {
            // T8: collinearity checked via the signed cross-product residual (twice the signed
            // triangle area), not by asserting a specific projected coordinate -- PlaneGCS's DogLeg
            // step does not guarantee an orthogonal projection from the starting guess (verified: a
            // real run lands at (0.585, 0.78), not the orthogonal projection (1.08, 1.44)).
            val result =
                PlaneGcsSolver.solve(
                    points =
                        listOf(
                            SketchPoint(0.0, 0.0, fixed = true),
                            SketchPoint(3.0, 4.0, fixed = true),
                            SketchPoint(3.0, 0.0),
                        ),
                    constraints = listOf(PointOnLineConstraint(2, 0, 1)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            val p0 = result.points[0]
            val p1 = result.points[1]
            val p2 = result.points[2]
            val cross = (p1.x - p0.x) * (p2.y - p0.y) - (p1.y - p0.y) * (p2.x - p0.x)
            cross shouldBe (0.0 plusOrMinus 1e-6)
        }

        "point-on-line combined with a distance constraint pins down an exact coordinate".config(enabled = available) {
            // T9: P2 must be collinear with the X axis (P0=(0,0), P1=(1,0)) AND 7.0 from P0 -- the
            // only two points satisfying both are (7,0) and (-7,0); a real run from (0.5, 0.5)
            // converges to the positive one (verified, not assumed -- direction is otherwise a
            // mirror-solution risk per PlaneGcsBridgeSmokeTest's "Stolperfallen").
            val result =
                PlaneGcsSolver.solve(
                    points =
                        listOf(
                            SketchPoint(0.0, 0.0, fixed = true),
                            SketchPoint(1.0, 0.0, fixed = true),
                            SketchPoint(0.5, 0.5),
                        ),
                    constraints = listOf(PointOnLineConstraint(2, 0, 1), DistanceConstraint(0, 2, 7.0)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            result.points[2].x shouldBe (7.0 plusOrMinus 1e-6)
            result.points[2].y shouldBe (0.0 plusOrMinus 1e-6)
        }

        "a contradictory point-on-line system fails cleanly".config(enabled = available) {
            // T10: P2 must be collinear with the X axis AND simultaneously 5.0 from P0=(0,0) and
            // 3.0 from P1=(10,0) -- the two distance circles intersect the X axis only at x=5±... /
            // x=... values that do not coincide (5 != 10-3=7 and 5 != -(10-3)), so no point
            // satisfies all three simultaneously. Verified FAILED, free point left unmoved.
            val result =
                PlaneGcsSolver.solve(
                    points =
                        listOf(
                            SketchPoint(0.0, 0.0, fixed = true),
                            SketchPoint(10.0, 0.0, fixed = true),
                            SketchPoint(4.0, 3.0),
                        ),
                    constraints =
                        listOf(
                            PointOnLineConstraint(2, 0, 1),
                            DistanceConstraint(0, 2, 5.0),
                            DistanceConstraint(1, 2, 3.0),
                        ),
                )
            result.status shouldBe SolveStatus.FAILED
            result.solved shouldBe false
        }

        "all four new-wave constraint kinds combine in one system".config(enabled = available) {
            // T11: the T6 rectangle, plus a fifth point pinned onto the rectangle's third corner via
            // coincidence, plus a sixth point pinned onto the bottom edge's infinite line via
            // point-on-line.
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(9.0, 1.0),
                    SketchPoint(8.0, 6.0),
                    SketchPoint(1.0, 5.0),
                    SketchPoint(6.0, 4.0),
                    SketchPoint(2.0, 1.0),
                )
            val constraints =
                listOf(
                    HorizontalConstraint(0, 1),
                    VerticalConstraint(1, 2),
                    HorizontalConstraint(3, 2),
                    VerticalConstraint(0, 3),
                    DistanceConstraint(0, 1, 10.0),
                    DistanceConstraint(1, 2, 4.0),
                    CoincidenceConstraint(4, 2),
                    PointOnLineConstraint(5, 0, 1),
                )
            val result = PlaneGcsSolver.solve(points, constraints)
            result.status shouldBe SolveStatus.SUCCESS
            val p0 = result.points[0]
            val p1 = result.points[1]
            val p2 = result.points[2]
            val p4 = result.points[4]
            val p5 = result.points[5]
            p4.x shouldBe (p2.x plusOrMinus 1e-6)
            p4.y shouldBe (p2.y plusOrMinus 1e-6)
            // p5 collinear with the p0-p1 edge (structurally always true, unlike a coordinate-range
            // check -- PointOnLineConstraint constrains the INFINITE line, not the segment).
            val cross = (p1.x - p0.x) * (p5.y - p0.y) - (p1.y - p0.y) * (p5.x - p0.x)
            cross shouldBe (0.0 plusOrMinus 1e-6)
        }

        "solve(...) and solveDistances(...) agree exactly on the same distance-only problem".config(
            enabled = available,
        ) {
            // T12: regression guard for replacing the sole native entry point (ADR-0007's
            // "Decision") -- the two call shapes must be indistinguishable to a caller.
            val points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0))
            val distanceConstraints = listOf(DistanceConstraint(0, 1, 5.0))
            val viaSolve = PlaneGcsSolver.solve(points, distanceConstraints)
            val viaSolveDistances = PlaneGcsSolver.solveDistances(points, distanceConstraints)
            viaSolve.status shouldBe viaSolveDistances.status
            viaSolve.points shouldBe viaSolveDistances.points
        }

        "8 threads solving 50 mixed-constraint-type systems each all converge correctly".config(enabled = available) {
            // T14: same family of regression guard as PlaneGcsBridgeSmokeTest's "8 threads..." case,
            // extended to exercise every new constraint kind concurrently, not just distances.
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads =
                (1..8).map { threadIndex ->
                    kotlin.concurrent.thread(start = true, name = "planegcs-mixed-concurrency-$threadIndex") {
                        repeat(50) {
                            try {
                                val result =
                                    PlaneGcsSolver.solve(
                                        points =
                                            listOf(
                                                SketchPoint(0.0, 0.0, fixed = true),
                                                SketchPoint(10.0, 0.0, fixed = true),
                                                SketchPoint(1.0, 1.0),
                                                SketchPoint(4.0, 3.0),
                                            ),
                                        constraints =
                                            listOf(
                                                DistanceConstraint(0, 1, 10.0),
                                                CoincidenceConstraint(2, 0),
                                                PointOnLineConstraint(3, 0, 1),
                                            ),
                                    )
                                if (result.status != SolveStatus.SUCCESS) {
                                    errors.add(AssertionError("unexpected status ${result.status}"))
                                }
                                if (abs(result.points[2].x) > 1e-6 || abs(result.points[2].y) > 1e-6) {
                                    errors.add(AssertionError("coincidence not satisfied: ${result.points[2]}"))
                                }
                                if (abs(result.points[3].y) > 1e-6) {
                                    errors.add(AssertionError("point-on-line not satisfied: ${result.points[3]}"))
                                }
                            } catch (t: Throwable) {
                                errors.add(t)
                            }
                        }
                    }
                }
            threads.forEach { it.join() }
            errors.isEmpty() shouldBe true
        }

        "new constraint kinds are validated before reaching native code" {
            // Deliberately NOT gated on `available`: pure Kotlin-side validation.
            val points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0), SketchPoint(2.0, 0.0))

            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(CoincidenceConstraint(0, 0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(HorizontalConstraint(1, 1)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(VerticalConstraint(0, 0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(CoincidenceConstraint(-1, 1)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(HorizontalConstraint(0, points.size)))
            }

            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PointOnLineConstraint(0, 0, 1)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PointOnLineConstraint(0, 1, 1)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PointOnLineConstraint(0, 1, 0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PointOnLineConstraint(-1, 0, 1)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PointOnLineConstraint(0, 1, points.size)))
            }

            val tooManyConstraints =
                (1..(PlaneGcsSolver.MAX_CONSTRAINTS + 1)).map { CoincidenceConstraint(0, 1) }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, tooManyConstraints)
            }
        }

        (
            "a solve at MAX_POINTS/MAX_CONSTRAINTS with a dense coincidence-only graph -- the topology " +
                "that produces twice as many native ConstraintEqual rows as Kotlin constraints -- " +
                "completes within a defensive wall-clock bound"
        ).config(enabled = available) {
            // Regression guard for the asymmetry CoincidenceConstraint's KDoc and MAX_CONSTRAINTS's
            // KDoc both call out: addConstraintP2PCoincident lowers to TWO native ConstraintEqual
            // rows, so MAX_CONSTRAINTS coincidences produce up to 2x the native row count of the
            // same count of distance/H/V constraints. Deterministic (java.util.Random(1)), same
            // pattern as PlaneGcsBridgeSmokeTest's dense-inconsistent guard.
            val random = java.util.Random(1)
            val points =
                List(PlaneGcsSolver.MAX_POINTS) { i ->
                    SketchPoint(random.nextDouble() * 100.0, random.nextDouble() * 100.0, fixed = i == 0)
                }
            val constraints =
                List(PlaneGcsSolver.MAX_CONSTRAINTS) {
                    val a = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    var b = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    while (b == a) b = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    CoincidenceConstraint(a, b)
                }
            val duration =
                measureTime {
                    val result =
                        PlaneGcsSolver.solve(
                            points,
                            constraints,
                            maxIterations = PlaneGcsSolver.MAX_ITERATIONS,
                        )
                    logger.info { "dense coincidence-graph solve status: ${result.status}" }
                }
            val elapsedMs = duration.inWholeMilliseconds
            logger.info { "dense coincidence MAX_POINTS/MAX_CONSTRAINTS/MAX_ITERATIONS solve took ${elapsedMs}ms" }
            // See docs/adr/ADR-0007's DoS section for the measurement this bound is based on. 5s
            // mirrors PlaneGcsBridgeSmokeTest's own bound for the equivalent distance-only case.
            (elapsedMs <= 5_000L) shouldBe true
        }

        (
            "a solve at MAX_POINTS/MAX_CONSTRAINTS with a dense point-on-line graph -- PlaneGCS's only " +
                "nonlinear constraint kind added in this wave -- completes within a defensive " +
                "wall-clock bound"
        ).config(enabled = available) {
            // Regression guard for the nonlinear PointOnLineConstraint specifically -- the
            // constraint kind ADR-0007's own division-by-near-zero finding (see
            // PointOnLineConstraint's KDoc) makes the most plausible worst-case candidate among this
            // wave's additions.
            val random = java.util.Random(2)
            val points =
                List(PlaneGcsSolver.MAX_POINTS) { i ->
                    SketchPoint(random.nextDouble() * 100.0, random.nextDouble() * 100.0, fixed = i == 0)
                }

            fun distinctTriple(): Triple<Int, Int, Int> {
                var p: Int
                var f: Int
                var t: Int
                do {
                    p = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    f = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    t = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                } while (p == f || p == t || f == t)
                return Triple(p, f, t)
            }
            val constraints =
                List(PlaneGcsSolver.MAX_CONSTRAINTS) {
                    val (p, f, t) = distinctTriple()
                    PointOnLineConstraint(p, f, t)
                }
            var observedStatus: SolveStatus? = null
            var nonFiniteRejected = false
            val duration =
                measureTime {
                    try {
                        val result =
                            PlaneGcsSolver.solve(points, constraints, maxIterations = PlaneGcsSolver.MAX_ITERATIONS)
                        observedStatus = result.status
                    } catch (e: ConstraintSolverException) {
                        // A non-finite native result is treated as this function's own defended
                        // failure mode (see PlaneGcsSolver.solve's KDoc) -- still bounded in time,
                        // which is what this guard actually checks.
                        nonFiniteRejected = true
                        logger.info { "dense point-on-line solve was rejected as non-finite: ${e.message}" }
                    }
                }
            val elapsedMs = duration.inWholeMilliseconds
            logger.info {
                "dense point-on-line MAX_POINTS/MAX_CONSTRAINTS/MAX_ITERATIONS solve took ${elapsedMs}ms, " +
                    "status=$observedStatus, nonFiniteRejected=$nonFiniteRejected"
            }
            (elapsedMs <= 5_000L) shouldBe true
        }

        "a free leg's endpoint converges to parallel with a fixed 30 degree leg".config(enabled = available) {
            // T15: leg A is fully fixed at 30 degrees (P0=(0,0), P1 on a 10-unit ray at 30 degrees).
            // Leg B has a fixed anchor (P2) and one free endpoint (P3) starting off-parallel --
            // verified against a real run to converge to the SAME direction (not anti-parallel) from
            // this particular starting guess: angleB lands at 29.999999999999996 degrees, matching
            // angleA's 29.999999999999993 -- sin() of the difference is used (not the raw angle
            // difference) precisely because ParallelConstraint's KDoc documents anti-parallel (180
            // degree) solutions as equally valid; this assertion does not depend on which one a
            // given run happens to find.
            val a1x = 10.0 * cos(Math.toRadians(30.0))
            val a1y = 10.0 * sin(Math.toRadians(30.0))
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(a1x, a1y, fixed = true),
                    SketchPoint(20.0, 0.0, fixed = true),
                    SketchPoint(25.0, 3.0),
                )
            val result = PlaneGcsSolver.solve(points, listOf(ParallelConstraint(0, 1, 2, 3)))
            result.status shouldBe SolveStatus.SUCCESS
            val angleDiffRad = Math.toRadians(result.legAngleDeg(0, 1) - result.legAngleDeg(2, 3))
            sin(angleDiffRad) shouldBe (0.0 plusOrMinus 1e-9)
        }

        "parallel via the cross-product residual, direction-agnostic, with both endpoints of leg B free".config(
            enabled = available,
        ) {
            // T16: leg A fixed horizontal (angle 0). Leg B has BOTH endpoints free (P2, P3), no
            // anchor at all -- an intentionally underdetermined system (1 equation, 4 unknowns) to
            // exercise the cross-product residual itself rather than a specific converged
            // coordinate. Verified against a real run: the solver left both x-coordinates
            // completely unchanged (5.0 and 0.0) and adjusted only the y-coordinates to make the
            // leg horizontal (cross == 0.0 exactly) -- structural evidence that the residual, not a
            // specific direction, is what is actually driven to zero.
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(10.0, 0.0, fixed = true),
                    SketchPoint(5.0, 5.0),
                    SketchPoint(0.0, 8.0),
                )
            val result = PlaneGcsSolver.solve(points, listOf(ParallelConstraint(0, 1, 2, 3)))
            result.status shouldBe SolveStatus.SUCCESS
            val p0 = result.points[0]
            val p1 = result.points[1]
            val p2 = result.points[2]
            val p3 = result.points[3]
            val cross = (p1.x - p0.x) * (p3.y - p2.y) - (p1.y - p0.y) * (p3.x - p2.x)
            cross shouldBe (0.0 plusOrMinus 1e-9)
        }

        "perpendicular rotates a free leg exactly 90 degrees from a fixed 30 degree leg".config(enabled = available) {
            // T17: same fixed leg A as T15 (30 degrees); leg B has a fixed anchor and one free
            // endpoint. Verified against a real run: converges with dot == -4.44e-15 (effectively
            // zero) and angleB == 120.00000000000001 degrees -- exactly angleA + 90.
            val a1x = 10.0 * cos(Math.toRadians(30.0))
            val a1y = 10.0 * sin(Math.toRadians(30.0))
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(a1x, a1y, fixed = true),
                    SketchPoint(20.0, 0.0, fixed = true),
                    SketchPoint(25.0, 3.0),
                )
            val result = PlaneGcsSolver.solve(points, listOf(PerpendicularConstraint(0, 1, 2, 3)))
            result.status shouldBe SolveStatus.SUCCESS
            val p0 = result.points[0]
            val p1 = result.points[1]
            val p2 = result.points[2]
            val p3 = result.points[3]
            val dot = (p1.x - p0.x) * (p3.x - p2.x) + (p1.y - p0.y) * (p3.y - p2.y)
            dot shouldBe (0.0 plusOrMinus 1e-6)
            var diff = (result.legAngleDeg(2, 3) - result.legAngleDeg(0, 1)) % 180.0
            if (diff < 0) diff += 180.0
            diff shouldBe (90.0 plusOrMinus 1e-6)
        }

        "perpendicular accepts a shared endpoint -- the rectangle-corner case".config(enabled = available) {
            // T18: leg A = P0->P1 (both fixed, horizontal), leg B = P1->P2 (SHARES P1 with leg A,
            // P2 free) -- the normal way to express a right-angle corner. This is the regression
            // guard for the kind-aware distinctness rule in kstep_planegcs_bridge.cpp: if a future
            // change restores full pairwise distinctness over all four slots, this case starts
            // throwing IllegalArgumentException instead of solving. Verified against a real run:
            // dot == 0.0 exactly, P2 converges to (10.0, 4.0) -- directly above P1, as expected for
            // a leg forced perpendicular to a horizontal one.
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(10.0, 0.0, fixed = true),
                    SketchPoint(7.0, 4.0),
                )
            val result = PlaneGcsSolver.solve(points, listOf(PerpendicularConstraint(0, 1, 1, 2)))
            result.status shouldBe SolveStatus.SUCCESS
            val p0 = result.points[0]
            val p1 = result.points[1]
            val p2 = result.points[2]
            val dot = (p1.x - p0.x) * (p2.x - p1.x) + (p1.y - p0.y) * (p2.y - p1.y)
            dot shouldBe (0.0 plusOrMinus 1e-9)
            p2.x shouldBe (10.0 plusOrMinus 1e-6)
            p2.y shouldBe (4.0 plusOrMinus 1e-6)
        }

        (
            "a rotated rectangle solves from Perpendicular and Distance alone -- no Horizontal/" +
                "Vertical -- complementing T6's axis-aligned flagship"
        ).config(enabled = available) {
            // T19 (flagship): P0 fixed at the origin, P1 fixed on a 10-unit, 30-degree ray from P0
            // (this pair alone fixes both the rectangle's scale and its rotation). Two right angles
            // are chained at P1 and P2 (a rectangle's remaining two corners, P3 and P0, are then
            // implied, not separately constrained) together with the two independent side lengths.
            // Verified against a real run: all four corner dot products land at ~1e-14 (effectively
            // zero) and P3 converges to exactly (-2.0, 3.4641...), the hand-computed closure point
            // for this exact rectangle (P3 = P0 + (P2 - P1)).
            val a1x = 10.0 * cos(Math.toRadians(30.0))
            val a1y = 10.0 * sin(Math.toRadians(30.0))
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(a1x, a1y, fixed = true),
                    SketchPoint(a1x - 2.0, a1y + 3.0),
                    SketchPoint(-3.0, 3.0),
                )
            val constraints =
                listOf(
                    PerpendicularConstraint(0, 1, 1, 2),
                    DistanceConstraint(1, 2, 4.0),
                    PerpendicularConstraint(1, 2, 2, 3),
                    DistanceConstraint(2, 3, 10.0),
                )
            val result = PlaneGcsSolver.solve(points, constraints)
            result.status shouldBe SolveStatus.SUCCESS
            val p0 = result.points[0]
            val p1 = result.points[1]
            val p2 = result.points[2]
            val p3 = result.points[3]
            val dotP1 = (p1.x - p0.x) * (p2.x - p1.x) + (p1.y - p0.y) * (p2.y - p1.y)
            val dotP2 = (p2.x - p1.x) * (p3.x - p2.x) + (p2.y - p1.y) * (p3.y - p2.y)
            val dotP3 = (p3.x - p2.x) * (p0.x - p3.x) + (p3.y - p2.y) * (p0.y - p3.y)
            val dotP0 = (p0.x - p3.x) * (p1.x - p0.x) + (p0.y - p3.y) * (p1.y - p0.y)
            dotP1 shouldBe (0.0 plusOrMinus 1e-6)
            dotP2 shouldBe (0.0 plusOrMinus 1e-6)
            dotP3 shouldBe (0.0 plusOrMinus 1e-6)
            dotP0 shouldBe (0.0 plusOrMinus 1e-6)
            val crossOppositeSides = (p1.x - p0.x) * (p2.y - p3.y) - (p1.y - p0.y) * (p2.x - p3.x)
            crossOppositeSides shouldBe (0.0 plusOrMinus 1e-6)
            p3.x shouldBe (-2.0 plusOrMinus 1e-6)
            p3.y shouldBe (3.4641016151 plusOrMinus 1e-6)
        }

        (
            "a contradictory parallel+perpendicular system resolves by collapsing the shared leg, " +
                "NOT by reporting FAILED"
        ).config(enabled = available) {
            // T20: leg A (P0->P1, fixed, horizontal) is constrained BOTH parallel AND perpendicular
            // to leg B (P2->P3, both free) -- structurally unsatisfiable for a leg B of any nonzero
            // length. Unlike T3/T10's contradictory coincidence/point-on-line systems (which leave
            // the free point at its starting position and report FAILED), a real run of THIS
            // contradiction reports SUCCESS, with P2 and P3 having collapsed onto the exact same
            // coordinates: the least-squares minimum here is reachable by shrinking leg B to zero
            // length, which makes both residuals (cross and dot) simultaneously zero. This is
            // exactly the behavior ParallelConstraint/PerpendicularConstraint's KDoc warns about --
            // do NOT copy T3/T10's FAILED expectation onto a parallel/perpendicular contradiction.
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(10.0, 0.0, fixed = true),
                    SketchPoint(3.0, 3.0),
                    SketchPoint(8.0, 5.0),
                )
            val constraints =
                listOf(
                    ParallelConstraint(0, 1, 2, 3),
                    PerpendicularConstraint(2, 3, 0, 1),
                )
            val result = PlaneGcsSolver.solve(points, constraints)
            result.status shouldBe SolveStatus.SUCCESS
            val p2 = result.points[2]
            val p3 = result.points[3]
            p2.x shouldBe (p3.x plusOrMinus 1e-6)
            p2.y shouldBe (p3.y plusOrMinus 1e-6)
        }

        "Parallel and Perpendicular layer onto the existing T11 combined system without disturbing it".config(
            enabled = available,
        ) {
            // T21: the exact T11 system (rectangle + coincidence + point-on-line), with two
            // additional, REDUNDANT constraints layered on top: the rectangle's opposite sides are
            // also asserted Parallel, and one corner is also asserted Perpendicular (both already
            // implied by the existing Horizontal/Vertical constraints). Verified against a real
            // run: every one of T11's own invariants still holds (status changes from SUCCESS to
            // CONVERGED with the extra, redundant equations, but the geometry is identical), plus
            // the two new residuals are zero.
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(9.0, 1.0),
                    SketchPoint(8.0, 6.0),
                    SketchPoint(1.0, 5.0),
                    SketchPoint(6.0, 4.0),
                    SketchPoint(2.0, 1.0),
                )
            val constraints =
                listOf(
                    HorizontalConstraint(0, 1),
                    VerticalConstraint(1, 2),
                    HorizontalConstraint(3, 2),
                    VerticalConstraint(0, 3),
                    DistanceConstraint(0, 1, 10.0),
                    DistanceConstraint(1, 2, 4.0),
                    CoincidenceConstraint(4, 2),
                    PointOnLineConstraint(5, 0, 1),
                    ParallelConstraint(0, 1, 3, 2),
                    PerpendicularConstraint(0, 1, 1, 2),
                )
            val result = PlaneGcsSolver.solve(points, constraints)
            result.solved shouldBe true
            val p0 = result.points[0]
            val p1 = result.points[1]
            val p2 = result.points[2]
            val p3 = result.points[3]
            val p4 = result.points[4]
            val p5 = result.points[5]
            p1.y shouldBe (p0.y plusOrMinus 1e-6)
            abs(p1.x - p0.x) shouldBe (10.0 plusOrMinus 1e-6)
            p2.x shouldBe (p1.x plusOrMinus 1e-6)
            abs(p2.y - p1.y) shouldBe (4.0 plusOrMinus 1e-6)
            p3.x shouldBe (p0.x plusOrMinus 1e-6)
            p3.y shouldBe (p2.y plusOrMinus 1e-6)
            p4.x shouldBe (p2.x plusOrMinus 1e-6)
            p4.y shouldBe (p2.y plusOrMinus 1e-6)
            val collinearCross = (p1.x - p0.x) * (p5.y - p0.y) - (p1.y - p0.y) * (p5.x - p0.x)
            collinearCross shouldBe (0.0 plusOrMinus 1e-6)
            val parallelCross = (p1.x - p0.x) * (p2.y - p3.y) - (p1.y - p0.y) * (p2.x - p3.x)
            parallelCross shouldBe (0.0 plusOrMinus 1e-6)
            val perpendicularDot = (p1.x - p0.x) * (p2.x - p1.x) + (p1.y - p0.y) * (p2.y - p1.y)
            perpendicularDot shouldBe (0.0 plusOrMinus 1e-6)
        }

        "8 threads solving 50 systems mixing Parallel/Perpendicular with the full constraint vocabulary".config(
            enabled = available,
        ) {
            // T22: same family of regression guard as T14, extended with the T21 system (which
            // itself layers Parallel/Perpendicular onto every earlier constraint kind).
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads =
                (1..8).map { threadIndex ->
                    kotlin.concurrent.thread(start = true, name = "planegcs-parperp-concurrency-$threadIndex") {
                        repeat(50) {
                            try {
                                val points =
                                    listOf(
                                        SketchPoint(0.0, 0.0, fixed = true),
                                        SketchPoint(9.0, 1.0),
                                        SketchPoint(8.0, 6.0),
                                        SketchPoint(1.0, 5.0),
                                        SketchPoint(6.0, 4.0),
                                        SketchPoint(2.0, 1.0),
                                    )
                                val constraints =
                                    listOf(
                                        HorizontalConstraint(0, 1),
                                        VerticalConstraint(1, 2),
                                        HorizontalConstraint(3, 2),
                                        VerticalConstraint(0, 3),
                                        DistanceConstraint(0, 1, 10.0),
                                        DistanceConstraint(1, 2, 4.0),
                                        CoincidenceConstraint(4, 2),
                                        PointOnLineConstraint(5, 0, 1),
                                        ParallelConstraint(0, 1, 3, 2),
                                        PerpendicularConstraint(0, 1, 1, 2),
                                    )
                                val result = PlaneGcsSolver.solve(points, constraints)
                                if (!result.solved) {
                                    errors.add(AssertionError("unexpected status ${result.status}"))
                                }
                                val p1 = result.points[1]
                                val p2 = result.points[2]
                                if (abs(p1.y - result.points[0].y) > 1e-6 || abs(p2.x - p1.x) > 1e-6) {
                                    errors.add(AssertionError("rectangle invariant not satisfied: $result"))
                                }
                            } catch (t: Throwable) {
                                errors.add(t)
                            }
                        }
                    }
                }
            threads.forEach { it.join() }
            errors.isEmpty() shouldBe true
        }

        "new Parallel/Perpendicular constraints are validated before reaching native code" {
            // T23: deliberately NOT gated on `available` -- pure Kotlin-side validation.
            val points =
                listOf(
                    SketchPoint(0.0, 0.0, fixed = true),
                    SketchPoint(1.0, 0.0),
                    SketchPoint(2.0, 0.0),
                    SketchPoint(2.0, 0.0),
                )

            // Degenerate leg A (lineAFrom == lineATo).
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(ParallelConstraint(0, 0, 1, 2)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PerpendicularConstraint(0, 0, 1, 2)))
            }
            // Degenerate leg B (lineBFrom == lineBTo).
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(ParallelConstraint(0, 1, 2, 2)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PerpendicularConstraint(0, 1, 2, 2)))
            }
            // Identical legs, both index orderings.
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(ParallelConstraint(0, 1, 0, 1)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(ParallelConstraint(0, 1, 1, 0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PerpendicularConstraint(0, 1, 0, 1)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PerpendicularConstraint(0, 1, 1, 0)))
            }
            // Out of range.
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(ParallelConstraint(-1, 1, 2, 3)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(ParallelConstraint(0, 1, 2, points.size)))
            }
            // Leg B's two points (indices 2, 3) are coincident-coordinate but distinct indices --
            // rejected by the coordinate-level check (rule 5), not the index-level checks above.
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(ParallelConstraint(0, 1, 2, 3)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(points, listOf(PerpendicularConstraint(0, 1, 2, 3)))
            }

            // Positive case: a shared endpoint between the two legs must NOT be rejected by
            // validateConstraint -- see PerpendicularConstraint's KDoc. Only run through an actual
            // solve() when the native bridge is available (this whole test case is otherwise
            // deliberately unguarded, so it must not itself require the bridge); T18 above is the
            // unconditional, `.config(enabled = available)`-gated version of this same case.
            if (available) {
                val sharedEndpointPoints =
                    listOf(
                        SketchPoint(0.0, 0.0, fixed = true),
                        SketchPoint(10.0, 0.0, fixed = true),
                        SketchPoint(7.0, 4.0),
                    )
                PlaneGcsSolver
                    .solve(sharedEndpointPoints, listOf(PerpendicularConstraint(0, 1, 1, 2)))
                    .status shouldBe SolveStatus.SUCCESS
            }
        }

        "a solve at MAX_POINTS/MAX_CONSTRAINTS with a dense random Parallel-only graph completes within a defensive wall-clock bound"
            .config(
                enabled = available,
            ) {
                // T24a: mirrors PlaneGcsBridgeSmokeTest's/this suite's own dense-graph DoS guards,
                // extended to Parallel specifically. Deterministic (java.util.Random(3)). Measured
                // through the real JNI path during this wave's implementation: CONVERGED in 14ms --
                // comfortably under this guard's 5s bound, and see docs/adr/ADR-0014's Security table
                // for why the existing caps were left unchanged rather than re-derived.
                val random = java.util.Random(3)
                val points =
                    List(PlaneGcsSolver.MAX_POINTS) { i ->
                        SketchPoint(random.nextDouble() * 100.0, random.nextDouble() * 100.0, fixed = i == 0)
                    }

                fun distinctQuad(): IntArray {
                    var a: Int
                    var b: Int
                    var c: Int
                    var d: Int
                    do {
                        a = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                        b = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                        c = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                        d = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    } while (
                        a == b ||
                        c == d ||
                        (a == c && b == d) ||
                        (a == d && b == c) ||
                        points[a] == points[b] ||
                        points[c] == points[d]
                    )
                    return intArrayOf(a, b, c, d)
                }
                val constraints =
                    List(PlaneGcsSolver.MAX_CONSTRAINTS) {
                        val q = distinctQuad()
                        ParallelConstraint(q[0], q[1], q[2], q[3])
                    }
                var observedStatus: SolveStatus? = null
                val duration =
                    measureTime {
                        val result =
                            PlaneGcsSolver.solve(
                                points,
                                constraints,
                                maxIterations = PlaneGcsSolver.MAX_ITERATIONS,
                            )
                        observedStatus = result.status
                    }
                val elapsedMs = duration.inWholeMilliseconds
                logger.info {
                    "dense Parallel-only MAX_POINTS/MAX_CONSTRAINTS/MAX_ITERATIONS solve took ${elapsedMs}ms, status=$observedStatus"
                }
                (elapsedMs <= 5_000L) shouldBe true
            }

        "a solve at MAX_POINTS/MAX_CONSTRAINTS with a dense random Perpendicular-only graph completes within a defensive wall-clock bound"
            .config(
                enabled = available,
            ) {
                // T24b: same shape as T24a, Perpendicular specifically, seed 4. Measured through the
                // real JNI path: CONVERGED in 32ms.
                val random = java.util.Random(4)
                val points =
                    List(PlaneGcsSolver.MAX_POINTS) { i ->
                        SketchPoint(random.nextDouble() * 100.0, random.nextDouble() * 100.0, fixed = i == 0)
                    }

                fun distinctQuad(): IntArray {
                    var a: Int
                    var b: Int
                    var c: Int
                    var d: Int
                    do {
                        a = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                        b = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                        c = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                        d = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    } while (
                        a == b ||
                        c == d ||
                        (a == c && b == d) ||
                        (a == d && b == c) ||
                        points[a] == points[b] ||
                        points[c] == points[d]
                    )
                    return intArrayOf(a, b, c, d)
                }
                val constraints =
                    List(PlaneGcsSolver.MAX_CONSTRAINTS) {
                        val q = distinctQuad()
                        PerpendicularConstraint(q[0], q[1], q[2], q[3])
                    }
                var observedStatus: SolveStatus? = null
                val duration =
                    measureTime {
                        val result =
                            PlaneGcsSolver.solve(
                                points,
                                constraints,
                                maxIterations = PlaneGcsSolver.MAX_ITERATIONS,
                            )
                        observedStatus = result.status
                    }
                val elapsedMs = duration.inWholeMilliseconds
                logger.info {
                    "dense Perpendicular-only MAX_POINTS/MAX_CONSTRAINTS/MAX_ITERATIONS solve took ${elapsedMs}ms, status=$observedStatus"
                }
                (elapsedMs <= 5_000L) shouldBe true
            }

        (
            "a solve at MAX_POINTS/MAX_CONSTRAINTS blending Parallel/Perpendicular into the dense, " +
                "mutually inconsistent distance graph -- the actual worst-case topology behind " +
                "MAX_POINTS -- completes within a defensive wall-clock bound"
        ).config(enabled = available) {
            // T24c: the one DoS guard with the least headroom, and the one that actually justifies
            // leaving MAX_POINTS/MAX_CONSTRAINTS/MAX_ITERATIONS unchanged for this wave -- see
            // docs/adr/ADR-0014's Security table. Half the constraint budget is the same dense,
            // mutually-inconsistent DistanceConstraint graph PlaneGcsBridgeSmokeTest's own worst-
            // case guard uses (java.util.Random(5) here); the other half alternates Parallel and
            // Perpendicular over random point quads. Measured through the real JNI path: FAILED in
            // 502ms -- close to, but still comfortably under, this guard's 5s bound and this
            // module's one-second design budget.
            val random = java.util.Random(5)
            val points =
                List(PlaneGcsSolver.MAX_POINTS) { i ->
                    SketchPoint(random.nextDouble() * 100.0, random.nextDouble() * 100.0, fixed = i == 0)
                }
            val half = PlaneGcsSolver.MAX_CONSTRAINTS / 2

            fun distinctPair(): IntArray {
                var a: Int
                var b: Int
                do {
                    a = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    b = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                } while (a == b)
                return intArrayOf(a, b)
            }

            fun distinctQuad(): IntArray {
                var a: Int
                var b: Int
                var c: Int
                var d: Int
                do {
                    a = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    b = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    c = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                    d = random.nextInt(PlaneGcsSolver.MAX_POINTS)
                } while (
                    a == b ||
                    c == d ||
                    (a == c && b == d) ||
                    (a == d && b == c) ||
                    points[a] == points[b] ||
                    points[c] == points[d]
                )
                return intArrayOf(a, b, c, d)
            }
            val distanceConstraints =
                List(half) {
                    val p = distinctPair()
                    DistanceConstraint(p[0], p[1], 1.0 + random.nextDouble() * 10.0)
                }
            val parPerpConstraints =
                List(PlaneGcsSolver.MAX_CONSTRAINTS - half) {
                    val q = distinctQuad()
                    if (it % 2 == 0) {
                        ParallelConstraint(q[0], q[1], q[2], q[3])
                    } else {
                        PerpendicularConstraint(q[0], q[1], q[2], q[3])
                    }
                }
            val constraints = distanceConstraints + parPerpConstraints
            var observedStatus: SolveStatus? = null
            val duration =
                measureTime {
                    val result =
                        PlaneGcsSolver.solve(
                            points,
                            constraints,
                            maxIterations = PlaneGcsSolver.MAX_ITERATIONS,
                        )
                    observedStatus = result.status
                }
            val elapsedMs = duration.inWholeMilliseconds
            logger.info {
                "dense Parallel/Perpendicular-into-inconsistent-distance MAX_POINTS/MAX_CONSTRAINTS/" +
                    "MAX_ITERATIONS solve took ${elapsedMs}ms, status=$observedStatus"
            }
            (elapsedMs <= 5_000L) shouldBe true
        }
    })
