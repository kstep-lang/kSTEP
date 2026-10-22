package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileSegment
import dev.kstep.sketch.SketchErrorKind
import dev.kstep.sketch.SketchException
import dev.kstep.sketch.SketchSolverUnavailableException
import dev.kstep.sketch.sketch
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Welle L (ADR-0031): sketches solved through the real PlaneGCS bridge. Needs PlaneGCS only (no
 * OCCT), so it runs in the CI job where OCCT is deliberately absent.
 */
class PlaneGcsSketchSolveTest :
    StringSpec({
        val available = PlaneGcsSolver.availability() is PlaneGcsAvailability.Available
        val tol = 1e-9

        fun assertPoint(
            actual: ProfilePoint,
            x: Double,
            y: Double,
        ) {
            actual.x shouldBe (x plusOrMinus tol)
            actual.y shouldBe (y plusOrMinus tol)
        }

        "a rectangle with an offset hole solves to the exact coordinates".config(enabled = available) {
            var corner: dev.kstep.sketch.SketchRectangle? = null
            var centre: dev.kstep.sketch.SketchPointRef? = null
            val solved =
                sketch {
                    val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                    // start values deliberately off: the solver must move them to the dimensioned positions
                    val c = point(17.0, 12.0)
                    offset(frame.bottomLeft, c, dx = 20.0, dy = 10.0)
                    circle(c, 3.0)
                    corner = frame
                    centre = c
                }.let { s ->
                    s.solve()
                }
            solved.degreesOfFreedom shouldBe 0
            val frame = corner!!
            assertPoint(solved.point(frame.bottomLeft), 0.0, 0.0)
            assertPoint(solved.point(frame.bottomRight), 40.0, 0.0)
            assertPoint(solved.point(frame.topRight), 40.0, 20.0)
            assertPoint(solved.point(frame.topLeft), 0.0, 20.0)
            assertPoint(solved.point(centre!!), 20.0, 10.0)
            val region = solved.toRegion()
            region.outer.segments.size shouldBe 4
            region.holes.size shouldBe 1
            val hole =
                region.holes[0]
                    .segments
                    .single()
                    .shouldBeInstanceOf<ProfileSegment.Circle>()
            hole.radius shouldBe 3.0
            assertPoint(hole.center, 20.0, 10.0)
        }

        "start values choose the side: a point started to the left of its anchor stays on the left".config(
            enabled = available,
        ) {
            var left: dev.kstep.sketch.SketchPointRef? = null
            val solved =
                sketch {
                    val anchor = point(0.0, 0.0, fixed = true)
                    val p = point(-4.0, 0.5)
                    offset(anchor, p, dx = 5.0, dy = 0.0)
                    left = p
                }.solve()
            assertPoint(solved.point(left!!), -5.0, 0.0)
        }

        "an arc with a fixed radius solves and becomes a region arc through the computed midpoint".config(
            enabled = available,
        ) {
            var centre: dev.kstep.sketch.SketchPointRef? = null
            val solved =
                sketch {
                    // half disc of radius 5: a diameter line and a counter-clockwise arc
                    val c = point(0.0, 0.0, fixed = true)
                    val right = point(4.8, 0.3)
                    val left = point(-4.9, -0.2)
                    horizontal(c, right)
                    horizontal(c, left)
                    line(left, right)
                    arc(c, right, left, 5.0, ccw = true)
                    centre = c
                }.solve()
            solved.degreesOfFreedom shouldBe 0
            assertPoint(solved.point(centre!!), 0.0, 0.0)
            val region = solved.toRegion()
            region.outer.segments.size shouldBe 2
            val arc =
                region.outer.segments
                    .filterIsInstance<ProfileSegment.Arc>()
                    .single()
            // a counter-clockwise arc from (5,0) to (-5,0) passes over the top of the circle
            kotlin.math.abs(arc.mid.x) shouldBe (0.0 plusOrMinus 1e-6)
            arc.mid.y shouldBe (5.0 plusOrMinus 1e-6)
            val reversed =
                sketch {
                    val c = point(0.0, 0.0, fixed = true)
                    val right = point(4.8, 0.3)
                    val left = point(-4.9, -0.2)
                    horizontal(c, right)
                    horizontal(c, left)
                    line(left, right)
                    arc(c, right, left, 5.0, ccw = false)
                }.solve().toRegion()
            reversed.outer.segments
                .filterIsInstance<ProfileSegment.Arc>()
                .single()
                .mid.y shouldBe
                (-5.0 plusOrMinus 1e-6)
        }

        "perpendicular and parallel lines solve to a 3-4-5 right triangle's axis-aligned frame".config(
            enabled = available,
        ) {
            var d: dev.kstep.sketch.SketchPointRef? = null
            val solved =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0, fixed = true)
                    val c = point(0.3, 0.2, fixed = true)
                    val p = point(0.4, 6.0)
                    perpendicular(line(a, b), line(c, p))
                    distance(c, p, 6.0)
                    d = p
                }.solve()
            solved.degreesOfFreedom shouldBe 0
            assertPoint(solved.point(d!!), 0.3, 6.2)
        }

        "an unsatisfiable system that slips past the rank check fails cleanly with SOLVER_FAILED or OVER_CONSTRAINED"
            .config(
                enabled = available,
            ) {
                // three mutually inconsistent distances in a triangle (3 + 4 < 10) -- rank is fine, geometry is impossible
                val e =
                    shouldThrow<SketchException> {
                        sketch {
                            val a = point(0.0, 0.0, fixed = true)
                            val b = point(3.0, 0.0)
                            val c = point(0.0, 4.0)
                            distance(a, b, 3.0)
                            distance(a, c, 4.0)
                            distance(b, c, 10.0)
                            horizontal(a, b)
                        }.solve()
                    }
                (e.kind == SketchErrorKind.SOLVER_FAILED || e.kind == SketchErrorKind.OVER_CONSTRAINED) shouldBe true
            }

        "an under-constrained sketch with requireFullyConstrained = false solves and reports its freedom".config(
            enabled = available,
        ) {
            val solved =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0)
                    distance(a, b, 10.0)
                }.solve(requireFullyConstrained = false)
            solved.degreesOfFreedom shouldBe 1
        }

        "an under-constrained sketch fails by default with UNDER_CONSTRAINED".config(enabled = available) {
            val e =
                shouldThrow<SketchException> {
                    sketch {
                        val a = point(0.0, 0.0, fixed = true)
                        val b = point(10.0, 0.0, name = "slider")
                        distance(a, b, 10.0)
                    }.solve()
                }
            e.kind shouldBe SketchErrorKind.UNDER_CONSTRAINED
            e.degreesOfFreedom shouldBe 1
            e.details shouldBe listOf("slider")
        }

        "sketch solve fails predictably when the native bridge is unavailable".config(enabled = !available) {
            shouldThrow<SketchSolverUnavailableException> {
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0)
                    horizontal(a, b)
                    distance(a, b, 10.0)
                }.solve()
            }
        }
    })
