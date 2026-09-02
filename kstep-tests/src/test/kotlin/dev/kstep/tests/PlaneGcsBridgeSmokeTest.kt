package dev.kstep.tests

import dev.kstep.constraints.DistanceConstraint
import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.constraints.PlaneGcsUnavailableException
import dev.kstep.constraints.SketchPoint
import dev.kstep.constraints.SolveStatus
import dev.kstep.constraints.planegcs.PlaneGcsBridge
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.measureTime

private val logger = KotlinLogging.logger {}

/**
 * The end-to-end proof for kSTEP's Geometrie Welle 6 (pulled forward): PlaneGCS constraint solver
 * bridge (see docs/adr/ADR-0006-planegcs-constraint-bridge.adoc): a real two-point distance
 * constraint solved through PlaneGCS, a real 3-4-5 right triangle solved, and a real conflicting
 * (unsatisfiable) system that fails cleanly instead of crashing.
 *
 * Every PlaneGCS-dependent test case below is `.config(enabled = available)`, so this suite is
 * green on a machine without the Eigen/Boost dev packages installed
 * (`PlaneGcsSolver.availability()` reports [PlaneGcsAvailability.Unavailable] and those cases are
 * skipped, not failed) -- EXCEPT the very first test case, which is the deliberate guard against
 * the whole suite silently degrading into a no-op: when the build is run with
 * `-Pkstep.planegcs.require=true` (see `kstep-tests/build.gradle.kts` and README 'Building'), that
 * first case turns any [PlaneGcsAvailability.Unavailable] into a hard test failure instead of a
 * silent skip.
 *
 * Numeric expectations below (`5.0`, the triangle's `C=(~0, 4.0)`, the conflicting case's `status`)
 * were verified against a real PlaneGCS build during this wave's planning phase -- see
 * docs/adr/ADR-0006's "Environment note" for exactly what was run and what output was observed --
 * but this test suite is the wave's OWN independent verification, not a restatement of that
 * planning-phase run.
 */
class PlaneGcsBridgeSmokeTest :
    StringSpec({
        val availability = PlaneGcsSolver.availability()
        val available = availability is PlaneGcsAvailability.Available
        val required = System.getProperty("kstep.planegcs.require") == "true"

        "the PlaneGCS bridge is available when the build requires it" {
            if (required) {
                availability.shouldBeInstanceOf<PlaneGcsAvailability.Available>()
            } else {
                val detail =
                    when (availability) {
                        is PlaneGcsAvailability.Available ->
                            "available: PlaneGCS commit ${availability.planeGcsCommit} (${availability.librarySource})"
                        is PlaneGcsAvailability.Unavailable ->
                            "unavailable: ${availability.reason}"
                    }
                logger.info { "PlaneGCS bridge status: $detail (kstep.planegcs.require=false, not enforced)" }
            }
        }

        "the loaded library reports the documented provenance commit".config(enabled = available) {
            // Must match kstep-constraints/build.gradle.kts's planeGcsCommit val, this module's
            // build.gradle.kts is untouched by that value directly, and
            // third_party/planegcs/PROVENANCE.adoc -- see docs/adr/ADR-0006. Breaks loudly if
            // anyone swaps the vendored sources without updating all three.
            PlaneGcsSolver.planeGcsSourceCommit() shouldBe "ee9b156da9827a91a56a888a53520f63d5cffaa6"
        }

        "two points solve to the requested distance".config(enabled = available) {
            val result =
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    constraints = listOf(DistanceConstraint(pointA = 0, pointB = 1, distance = 5.0)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            result.solved shouldBe true
            result.points[0].x shouldBe (0.0 plusOrMinus 1e-9)
            result.points[0].y shouldBe (0.0 plusOrMinus 1e-9)
            result.points[1].x shouldBe (5.0 plusOrMinus 1e-9)
            result.points[1].y shouldBe (0.0 plusOrMinus 1e-9)
            result.distanceBetween(0, 1) shouldBe (5.0 plusOrMinus 1e-9)
        }

        "a 3-4-5 right triangle solves to the expected free vertex".config(enabled = available) {
            // A and B fixed 3 apart on the X axis; C free, starting near (0.5, 0.5), constrained to
            // |AC|=4 and |BC|=5 -- the classic 3-4-5 right triangle, right angle at A.
            val result =
                PlaneGcsSolver.solveDistances(
                    points =
                        listOf(
                            SketchPoint(0.0, 0.0, fixed = true),
                            SketchPoint(3.0, 0.0, fixed = true),
                            SketchPoint(0.5, 0.5),
                        ),
                    constraints =
                        listOf(
                            DistanceConstraint(0, 1, 3.0),
                            DistanceConstraint(0, 2, 4.0),
                            DistanceConstraint(1, 2, 5.0),
                        ),
                )
            result.status shouldBe SolveStatus.SUCCESS
            val c = result.points[2]
            // abs(c.y), NOT c.y directly: the solver's converged sign for the free vertex depends
            // on its starting guess and is not itself part of this constraint system's
            // specification -- the mirror-image solution at y=-4 is equally valid. Asserting the
            // raw signed value would make this test fail on a legitimate solver behavior change,
            // not a regression. See docs/adr/ADR-0006's "Stolperfallen".
            abs(c.x) shouldBe (0.0 plusOrMinus 1e-6)
            abs(c.y) shouldBe (4.0 plusOrMinus 1e-6)
            // Tolerance 1e-6, not 1e-9: this wave's planning-phase run observed a real residual of
            // roughly 6e-11 against convergence=1e-10 (PlaneGcsSolver.DEFAULT_CONVERGENCE) for this
            // exact problem -- 1e-9 would be too tight relative to that. The two-point case above
            // converges tighter in practice, hence its stricter 1e-9 tolerance.
            result.distanceBetween(0, 2) shouldBe (4.0 plusOrMinus 1e-6)
            result.distanceBetween(1, 2) shouldBe (5.0 plusOrMinus 1e-6)
        }

        "conflicting constraints fail cleanly, without corrupting later solves".config(enabled = available) {
            val conflicting =
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    constraints =
                        listOf(
                            DistanceConstraint(0, 1, 5.0),
                            DistanceConstraint(0, 1, 2.0),
                        ),
                )
            conflicting.status shouldBe SolveStatus.FAILED
            conflicting.solved shouldBe false

            // The same problem solved on its own, in the same test, right afterward: proves the
            // conflicting solve above left no state behind that would corrupt a later, unrelated
            // call -- consistent with this bridge's one-shot, stateless design (docs/adr/ADR-0006,
            // "Decision").
            val clean =
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                )
            clean.status shouldBe SolveStatus.SUCCESS
            clean.distanceBetween(0, 1) shouldBe (5.0 plusOrMinus 1e-9)
        }

        "an already-satisfied constraint leaves the free point unchanged".config(enabled = available) {
            val result =
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(5.0, 0.0)),
                    constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                )
            result.status shouldBe SolveStatus.SUCCESS
            result.points[1].x shouldBe (5.0 plusOrMinus 1e-9)
            result.points[1].y shouldBe (0.0 plusOrMinus 1e-9)
        }

        "8 threads solving 50 independent systems each all converge correctly".config(enabled = available) {
            // Belongs to the same family of regression guard as OcctBridgeSmokeTest's UAF-race
            // test, but far cheaper to state here: this bridge's one-shot design (docs/adr/ADR-0006,
            // "Decision") has no persistent native state at all to race over, so this test's job is
            // simply to demonstrate that many concurrent, fully independent solves on different
            // threads all still converge correctly and the JVM stays alive -- not to hunt for a
            // specific race the design already rules out structurally.
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads =
                (1..8).map { threadIndex ->
                    thread(start = true, name = "planegcs-concurrency-$threadIndex") {
                        repeat(50) {
                            try {
                                val result =
                                    PlaneGcsSolver.solveDistances(
                                        points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                                        constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                                    )
                                if (result.status != SolveStatus.SUCCESS) {
                                    errors.add(AssertionError("unexpected status ${result.status}"))
                                }
                                val dist = result.distanceBetween(0, 1)
                                if (abs(dist - 5.0) > 1e-9) {
                                    errors.add(AssertionError("distance $dist not within tolerance of 5.0"))
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

        (
            "the raw native method rejects null arrays with a Java NullPointerException instead of " +
                "crashing the JVM"
        ).config(enabled = available) {
            // Regression test mirroring OcctBridgeSmokeTest's identically-motivated case: PlaneGcsBridge
            // is a public object precisely so out-of-module callers (this test included) can reach the
            // raw native method directly with hostile input PlaneGcsSolver's own validation could
            // never construct on its own -- see PlaneGcsBridge.kt's KDoc. A native method has no
            // Kotlin-generated bytecode body, so Kotlin's compile-time non-null checks on the
            // *declared* parameter types do NOT run here; only kstep_planegcs_bridge.cpp's own
            // explicit null checks stand between a null jarray and undefined behavior.
            // java.lang.reflect.Method sidesteps Kotlin's own null-safety entirely and calls the JVM
            // method directly with a real null argument.
            val method =
                PlaneGcsBridge::class.java.getDeclaredMethod(
                    "nativeSolveP2PDistances",
                    DoubleArray::class.java,
                    IntArray::class.java,
                    IntArray::class.java,
                    IntArray::class.java,
                    DoubleArray::class.java,
                    Int::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType,
                    DoubleArray::class.java,
                )
            val coords = doubleArrayOf(0.0, 0.0, 1.0, 0.0)
            val fixedFlags = intArrayOf(1, 0)
            val ca = intArrayOf(0)
            val cb = intArrayOf(1)
            val cd = doubleArrayOf(5.0)
            val out = DoubleArray(4)

            fun invokeWith(vararg args: Any?): InvocationTargetException =
                shouldThrow<InvocationTargetException> {
                    method.invoke(PlaneGcsBridge, *args)
                }

            fun causeIsNpe(target: InvocationTargetException) {
                target.cause.shouldBeInstanceOf<NullPointerException>()
            }
            causeIsNpe(invokeWith(null, fixedFlags, ca, cb, cd, 100, 1e-10, out))
            causeIsNpe(invokeWith(coords, null, ca, cb, cd, 100, 1e-10, out))
            causeIsNpe(invokeWith(coords, fixedFlags, null, cb, cd, 100, 1e-10, out))
            causeIsNpe(invokeWith(coords, fixedFlags, ca, null, cd, 100, 1e-10, out))
            causeIsNpe(invokeWith(coords, fixedFlags, ca, cb, null, 100, 1e-10, out))
            causeIsNpe(invokeWith(coords, fixedFlags, ca, cb, cd, 100, 1e-10, null))

            // The JVM is still alive to run this: PlaneGCS still answers a completely normal
            // request right afterward, in the same test, on the same thread.
            val afterward =
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                )
            afterward.status shouldBe SolveStatus.SUCCESS
        }

        (
            "the raw native method rejects malformed array lengths and out-of-range indices with a " +
                "Java exception instead of crashing the JVM"
        ).config(enabled = available) {
            fun solveRaw(
                coords: DoubleArray,
                fixedFlags: IntArray,
                ca: IntArray,
                cb: IntArray,
                cd: DoubleArray,
                out: DoubleArray,
            ): Int = PlaneGcsBridge.nativeSolveP2PDistances(coords, fixedFlags, ca, cb, cd, 100, 1e-10, out)

            // outCoords too short.
            shouldThrow<IllegalArgumentException> {
                solveRaw(
                    doubleArrayOf(0.0, 0.0, 1.0, 0.0),
                    intArrayOf(1, 0),
                    intArrayOf(0),
                    intArrayOf(1),
                    doubleArrayOf(5.0),
                    DoubleArray(2),
                )
            }
            // fixedFlags length != pointCount.
            shouldThrow<IllegalArgumentException> {
                solveRaw(
                    doubleArrayOf(0.0, 0.0, 1.0, 0.0),
                    intArrayOf(1),
                    intArrayOf(0),
                    intArrayOf(1),
                    doubleArrayOf(5.0),
                    DoubleArray(4),
                )
            }
            // constraint arrays of unequal length.
            shouldThrow<IllegalArgumentException> {
                solveRaw(
                    doubleArrayOf(0.0, 0.0, 1.0, 0.0),
                    intArrayOf(1, 0),
                    intArrayOf(0),
                    intArrayOf(1, 0),
                    doubleArrayOf(5.0),
                    DoubleArray(4),
                )
            }
            // point index out of range.
            shouldThrow<IllegalArgumentException> {
                solveRaw(
                    doubleArrayOf(0.0, 0.0, 1.0, 0.0),
                    intArrayOf(1, 0),
                    intArrayOf(0),
                    intArrayOf(99),
                    doubleArrayOf(5.0),
                    DoubleArray(4),
                )
            }
            // The JVM is still alive to run this right afterward.
            val afterward =
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                )
            afterward.status shouldBe SolveStatus.SUCCESS
        }

        (
            "the raw native method content-validates coords/constraintDist/fixedFlags and rejects " +
                "non-finite or out-of-range values with a Java exception instead of silently " +
                "producing a NaN-polluted result"
        ).config(enabled = available) {
            // Regression test for this wave's fix to kstep_planegcs_bridge.cpp's own file-header
            // invariant claim: before this fix, a NaN/Infinity coordinate silently propagated into
            // `out` (status=2, out=[NaN, 0.0, 1.0, 0.0], observed during this wave's review) instead
            // of being rejected, and a negative/non-finite constraintDist or a fixedFlags value
            // outside {0, 1} was likewise accepted without native-side complaint -- see
            // kstep_planegcs_bridge.cpp's invariant 2 and docs/adr/ADR-0006's "JNI robustness" row.
            // PlaneGcsSolver's own Kotlin-side validate() already rejects all of these before ever
            // reaching here (see "invalid solve requests..." below) -- this test goes around that,
            // straight at PlaneGcsBridge, exactly like the null/length/index tests above.
            fun solveRaw(
                coords: DoubleArray,
                fixedFlags: IntArray,
                cd: DoubleArray = doubleArrayOf(5.0),
            ): Int =
                PlaneGcsBridge.nativeSolveP2PDistances(
                    coords,
                    fixedFlags,
                    intArrayOf(0),
                    intArrayOf(1),
                    cd,
                    100,
                    1e-10,
                    DoubleArray(coords.size),
                )

            shouldThrow<IllegalArgumentException> {
                solveRaw(doubleArrayOf(Double.NaN, 0.0, 1.0, 0.0), intArrayOf(1, 0))
            }
            shouldThrow<IllegalArgumentException> {
                solveRaw(doubleArrayOf(0.0, 0.0, Double.POSITIVE_INFINITY, 0.0), intArrayOf(1, 0))
            }
            shouldThrow<IllegalArgumentException> {
                solveRaw(doubleArrayOf(0.0, 0.0, 1.0, 0.0), intArrayOf(1, 0), cd = doubleArrayOf(-2.0))
            }
            shouldThrow<IllegalArgumentException> {
                solveRaw(doubleArrayOf(0.0, 0.0, 1.0, 0.0), intArrayOf(1, 0), cd = doubleArrayOf(0.0))
            }
            shouldThrow<IllegalArgumentException> {
                solveRaw(doubleArrayOf(0.0, 0.0, 1.0, 0.0), intArrayOf(1, 0), cd = doubleArrayOf(Double.NaN))
            }
            shouldThrow<IllegalArgumentException> {
                solveRaw(doubleArrayOf(0.0, 0.0, 1.0, 0.0), intArrayOf(7, 0))
            }

            // The JVM is still alive to run this right afterward.
            val afterward =
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                )
            afterward.status shouldBe SolveStatus.SUCCESS
        }

        "invalid solve requests are rejected before reaching native code" {
            // Deliberately NOT gated on `available`: this is pure Kotlin-side validation in
            // PlaneGcsSolver.solveDistances, so it must hold even when the native bridge itself is
            // absent.
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(points = emptyList(), constraints = emptyList())
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true)),
                    constraints = emptyList(),
                )
            }
            val twoPoints = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0))
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(twoPoints, listOf(DistanceConstraint(0, 1, 0.0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(twoPoints, listOf(DistanceConstraint(0, 1, -1.0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(twoPoints, listOf(DistanceConstraint(0, 1, Double.NaN)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    twoPoints,
                    listOf(DistanceConstraint(0, 1, Double.POSITIVE_INFINITY)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    twoPoints,
                    listOf(DistanceConstraint(0, 1, PlaneGcsSolver.MAX_DISTANCE + 1.0)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    listOf(SketchPoint(Double.NaN, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    listOf(DistanceConstraint(0, 1, 5.0)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    listOf(SketchPoint(Double.POSITIVE_INFINITY, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    listOf(DistanceConstraint(0, 1, 5.0)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                val outOfRange = SketchPoint(PlaneGcsSolver.MAX_ABS_COORDINATE + 1.0, 0.0, fixed = true)
                PlaneGcsSolver.solveDistances(
                    listOf(outOfRange, SketchPoint(1.0, 0.0)),
                    listOf(DistanceConstraint(0, 1, 5.0)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(twoPoints, listOf(DistanceConstraint(0, 0, 5.0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(twoPoints, listOf(DistanceConstraint(-1, 1, 5.0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(twoPoints, listOf(DistanceConstraint(0, 2, 5.0)))
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    twoPoints,
                    listOf(DistanceConstraint(0, 1, 5.0)),
                    maxIterations = 0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    twoPoints,
                    listOf(DistanceConstraint(0, 1, 5.0)),
                    maxIterations = PlaneGcsSolver.MAX_ITERATIONS + 1,
                )
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    twoPoints,
                    listOf(DistanceConstraint(0, 1, 5.0)),
                    convergence = 0.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    twoPoints,
                    listOf(DistanceConstraint(0, 1, 5.0)),
                    convergence = -1e-10,
                )
            }
            // An all-fixed point set has no unknowns for the solver -- rejected here rather than
            // exercising unverified native behavior (see docs/adr/ADR-0006's "Environment note" and
            // PlaneGcsSolver.solveDistances's own KDoc).
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(5.0, 0.0, fixed = true)),
                    constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                )
            }
            val tooManyPoints = List(PlaneGcsSolver.MAX_POINTS + 1) { SketchPoint(it.toDouble(), 0.0, fixed = it == 0) }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(tooManyPoints, emptyList())
            }
            val manyPointsForConstraints = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0))
            val tooManyConstraints =
                List(PlaneGcsSolver.MAX_CONSTRAINTS + 1) { DistanceConstraint(0, 1, 1.0 + it * 0.0001) }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solveDistances(manyPointsForConstraints, tooManyConstraints)
            }
        }

        (
            "a solve at MAX_POINTS/MAX_CONSTRAINTS with a dense, mutually inconsistent point graph -- " +
                "the actual worst-case topology behind this cap's size, not a chain or ring -- " +
                "completes within a defensive wall-clock bound"
        ).config(enabled = available) {
            // The regression guard a second review round found missing from this suite's first
            // attempt at this test (see PlaneGcsSolver.MAX_POINTS's KDoc and docs/adr/ADR-0006's
            // "Security" table, both revised in the same round): a chain of exactly MAX_POINTS
            // points -- what this test originally used -- is NOT the topology PlaneGCS is
            // expensive on. A densely, randomly interconnected, mutually inconsistent point set
            // (every point tangled into several conflicting distance constraints with other
            // points, so no local partial solution satisfies its neighborhood) measured roughly
            // two orders of magnitude more expensive than a chain at the same point count during
            // this wave's DoS review, and is the actual topology [PlaneGcsSolver.MAX_POINTS] was
            // sized from. This test reconstructs that topology deterministically
            // (`java.util.Random(1)`, matching this wave's own measurement harness exactly: all
            // point coordinates drawn first, then each constraint's two endpoint indices and its
            // target distance) at exactly MAX_POINTS points, MAX_CONSTRAINTS constraints, and the
            // full MAX_ITERATIONS budget -- every one of this cap's three dimensions at its
            // documented maximum simultaneously, not just point count in isolation.
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
                    DistanceConstraint(a, b, 1.0 + random.nextDouble() * 10.0)
                }
            val duration =
                measureTime {
                    // Target distances are independent of the randomly-placed start coordinates, so
                    // this system is essentially never satisfiable -- verified deterministic (this
                    // exact seed reproducibly returns FAILED, not e.g. an occasional CONVERGED) both
                    // during this fix and by this assertion on every future run; a status change here
                    // is itself a signal worth investigating, not just noise to relax the assertion
                    // away from.
                    val result =
                        PlaneGcsSolver.solveDistances(
                            points,
                            constraints,
                            maxIterations = PlaneGcsSolver.MAX_ITERATIONS,
                        )
                    result.status shouldBe SolveStatus.FAILED
                }
            val elapsedMs = duration.inWholeMilliseconds
            logger.info { "dense inconsistent MAX_POINTS/MAX_CONSTRAINTS/MAX_ITERATIONS solve took ${elapsedMs}ms" }
            // This wave's own measurements at these caps (three independent seeds, several distinct
            // adversarial topologies -- see PlaneGcsSolver.MAX_POINTS's KDoc) topped out at 557ms; 5
            // seconds is a deliberately generous bound to avoid CI flakiness while still being an
            // order-of-magnitude-tighter guard than "did not return", which is what a bound this
            // loose would nonetheless have caught against either of this constant's two previous,
            // insufficiently low values.
            (elapsedMs <= 5_000L) shouldBe true
        }

        (
            "a solve at MAX_POINTS with a consistent chain still succeeds correctly and quickly at " +
                "the new, much lower cap"
        ).config(enabled = available) {
            // Complements the dense/inconsistent case above with the ordinary-usage side of the same
            // boundary: MAX_POINTS was lowered by 8x in this wave's second DoS-fix round (512 -> 64,
            // see PlaneGcsSolver.MAX_POINTS's KDoc), so re-verify that a real, solvable sketch at
            // exactly the new cap still solves correctly, not just quickly.
            val points =
                List(PlaneGcsSolver.MAX_POINTS) { i -> SketchPoint(i.toDouble(), 0.0, fixed = i == 0) }
            val constraints =
                List(PlaneGcsSolver.MAX_POINTS - 1) { i -> DistanceConstraint(i, i + 1, 1.0) }
            val duration =
                measureTime {
                    val result = PlaneGcsSolver.solveDistances(points, constraints)
                    result.status shouldBe SolveStatus.SUCCESS
                }
            val elapsedMs = duration.inWholeMilliseconds
            logger.info { "MAX_POINTS chain solve took ${elapsedMs}ms" }
            (elapsedMs <= 2_000L) shouldBe true
        }

        (
            "a solve at MAX_CONSTRAINTS (small point count) completes well inside a defensive " +
                "wall-clock bound"
        ).config(enabled = available) {
            // Complements the two cases above: constraint count is capped independently of point
            // count (PlaneGcsSolver.MAX_CONSTRAINTS's KDoc), so exercise it independently too -- a
            // small ring of points carrying MAX_CONSTRAINTS redundant, mutually-consistent distance
            // constraints around it. This remains a weak-cost-driver check, not this suite's DoS
            // guard (see the dense/inconsistent case above for that) -- but it must still assert on
            // the actual result, not merely discard it and measure wall time, or a solver silently
            // returning FAILED on a legitimately solvable ring would go unnoticed.
            val ringSize = 64
            val angleStep = 2.0 * Math.PI / ringSize
            val chordLength = 2.0 * sin(Math.PI / ringSize)
            val points =
                List(ringSize) { i ->
                    SketchPoint(cos(i * angleStep), sin(i * angleStep), fixed = i == 0)
                }
            val constraints =
                List(PlaneGcsSolver.MAX_CONSTRAINTS) { i ->
                    val a = i % ringSize
                    val b = (i + 1) % ringSize
                    DistanceConstraint(a, b, chordLength)
                }
            val duration =
                measureTime {
                    val result = PlaneGcsSolver.solveDistances(points, constraints)
                    result.status shouldBe SolveStatus.SUCCESS
                }
            val elapsedMs = duration.inWholeMilliseconds
            logger.info { "MAX_CONSTRAINTS ring solve took ${elapsedMs}ms" }
            (elapsedMs <= 10_000L) shouldBe true
        }

        "solving fails predictably when the native bridge is unavailable".config(enabled = !available) {
            // The only test case in this suite with real signal on a machine without the Eigen/Boost
            // dev packages installed (i.e. this one, absent `-Pkstep.planegcs.require=true`) -- every
            // other PlaneGCS-dependent case above is skipped here via `enabled = available`. Without
            // this case, replacing PlaneGcsSolver.solveDistances's `throw
            // PlaneGcsUnavailableException(...)` guard with e.g. a raw UnsatisfiedLinkError, or
            // removing the guard altogether, would leave `./gradlew check` fully green on exactly
            // this kind of machine.
            shouldThrow<PlaneGcsUnavailableException> {
                PlaneGcsSolver.solveDistances(
                    points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                    constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                )
            }
        }

        "planeGcsSourceCommit fails predictably when the native bridge is unavailable".config(enabled = !available) {
            // Same rationale as "solving fails predictably..." above, for
            // PlaneGcsSolver.planeGcsSourceCommit's own `is PlaneGcsAvailability.Unavailable -> throw
            // PlaneGcsUnavailableException(...)` branch -- a distinct code path from
            // solveDistances's guard, so the case above does not exercise it.
            shouldThrow<PlaneGcsUnavailableException> { PlaneGcsSolver.planeGcsSourceCommit() }
        }

        (
            "concurrent solves never observe a torn/partial result from another thread's in-flight " +
                "solve -- the JVM never crashes, and each thread's own result is internally consistent"
        ).config(enabled = available) {
            // Complements the "8 threads..." test above with a tighter race: many threads hammering
            // the SAME PlaneGcsSolver.solveDistances call concurrently, checking not just that every
            // call succeeds but that no thread ever observes a result mixing another thread's
            // in-flight computation with its own (which this bridge's stack-only, no-shared-state
            // design -- docs/adr/ADR-0006, "Decision" -- should make structurally impossible, since
            // every call builds its own independent GCS::System and its own independent
            // std::vector<double> parameter storage with no cross-thread sharing at all).
            val readyLatch = CountDownLatch(16)
            val goLatch = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val threads =
                (1..16).map { threadIndex ->
                    thread(start = true, name = "planegcs-torn-read-$threadIndex") {
                        readyLatch.countDown()
                        goLatch.await()
                        try {
                            repeat(20) {
                                val result =
                                    PlaneGcsSolver.solveDistances(
                                        points = listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(1.0, 0.0)),
                                        constraints = listOf(DistanceConstraint(0, 1, 5.0)),
                                    )
                                if (result.status != SolveStatus.SUCCESS ||
                                    abs(result.distanceBetween(0, 1) - 5.0) > 1e-9
                                ) {
                                    failure.compareAndSet(
                                        null,
                                        AssertionError("thread $threadIndex saw a torn/incorrect result: $result"),
                                    )
                                }
                            }
                        } catch (t: Throwable) {
                            failure.compareAndSet(null, t)
                        }
                    }
                }
            readyLatch.await()
            goLatch.countDown()
            threads.forEach { it.join() }
            failure.get() shouldBe null
        }
    })
