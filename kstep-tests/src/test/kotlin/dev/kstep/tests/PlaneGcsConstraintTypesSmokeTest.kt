package dev.kstep.tests

import dev.kstep.constraints.CoincidenceConstraint
import dev.kstep.constraints.ConstraintSolverException
import dev.kstep.constraints.DistanceConstraint
import dev.kstep.constraints.HorizontalConstraint
import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.constraints.PointOnLineConstraint
import dev.kstep.constraints.SketchPoint
import dev.kstep.constraints.SolveStatus
import dev.kstep.constraints.VerticalConstraint
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

/**
 * End-to-end proof for kSTEP's PlaneGCS constraint vocabulary beyond plain point-to-point
 * distances -- [CoincidenceConstraint], [HorizontalConstraint], [VerticalConstraint], and
 * [PointOnLineConstraint] -- see `docs/adr/ADR-0007-planegcs-additional-constraint-types.adoc`.
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
 * seen to do.
 */
class PlaneGcsConstraintTypesSmokeTest :
    StringSpec({
        val availability = PlaneGcsSolver.availability()
        val available = availability is PlaneGcsAvailability.Available

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
    })
