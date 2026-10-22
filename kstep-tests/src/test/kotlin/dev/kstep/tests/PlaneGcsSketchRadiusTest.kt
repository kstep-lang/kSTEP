package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.sketch.SketchErrorKind
import dev.kstep.sketch.SketchException
import dev.kstep.sketch.SketchPointRef
import dev.kstep.sketch.SketchRoundRef
import dev.kstep.sketch.sketch
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Welle M (ADR-0032): radii as unknowns, tangents and angles through `sketch { }`; PlaneGCS only. */
class PlaneGcsSketchRadiusTest :
    StringSpec({
        val available = PlaneGcsSolver.availability() is PlaneGcsAvailability.Available
        val tol = 1e-7

        "diameter and equalRadius determine both radii".config(enabled = available) {
            lateinit var c1: SketchRoundRef
            lateinit var c2: SketchRoundRef
            val solved =
                sketch {
                    val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                    val a = point(10.0, 10.0)
                    val b = point(30.0, 10.0)
                    offset(frame.bottomLeft, a, 10.0, 10.0)
                    offset(frame.bottomLeft, b, 30.0, 10.0)
                    c1 = circle(a, 5.0, fixedRadius = false)
                    c2 = circle(b, 5.0, fixedRadius = false)
                    diameter(c1, 6.0)
                    equalRadius(c2, c1)
                }.solve()
            solved.degreesOfFreedom shouldBe 0
            solved.radius(c1) shouldBe (3.0 plusOrMinus tol)
            solved.radius(c2) shouldBe (3.0 plusOrMinus tol)
            solved.toRegion().holes.size shouldBe 2
        }

        "an undetermined free radius is reported as under-constrained".config(enabled = available) {
            val e =
                shouldThrow<SketchException> {
                    sketch {
                        val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                        val a = point(10.0, 10.0)
                        offset(frame.bottomLeft, a, 10.0, 10.0)
                        circle(a, 5.0, fixedRadius = false)
                    }.solve()
                }
            e.kind shouldBe SketchErrorKind.UNDER_CONSTRAINED
            e.degreesOfFreedom shouldBe 1
            e.details.single() shouldContain "radius of"
        }

        "a radius on a fixed-radius circle is redundant and says how to fix it".config(enabled = available) {
            val e =
                shouldThrow<SketchException> {
                    sketch {
                        val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                        val a = point(10.0, 10.0)
                        offset(frame.bottomLeft, a, 10.0, 10.0)
                        val c = circle(a, 5.0)
                        radius(c, 3.0)
                    }.solve()
                }
            e.kind shouldBe SketchErrorKind.OVER_CONSTRAINED
            e.details.single() shouldContain "use fixedRadius = false"
        }

        "a line tangent to a circle with a free radius gives r = 6".config(enabled = available) {
            lateinit var c: SketchRoundRef
            val solved =
                sketch {
                    val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                    val a = point(5.0, 4.0)
                    val b = point(35.0, 4.0)
                    offset(frame.bottomLeft, a, 5.0, 4.0)
                    offset(frame.bottomLeft, b, 35.0, 4.0)
                    val guide = line(a, b, construction = true)
                    val m = point(20.0, 10.0)
                    offset(frame.bottomLeft, m, 20.0, 10.0)
                    c = circle(m, 5.0, fixedRadius = false)
                    tangent(guide, c)
                }.solve()
            solved.radius(c) shouldBe (6.0 plusOrMinus tol)
        }

        "an angle constraint between two lines solves to the exact end point".config(enabled = available) {
            lateinit var p: SketchPointRef
            val solved =
                sketch {
                    val o = point(0.0, 0.0, fixed = true)
                    val x = point(10.0, 0.0, fixed = true)
                    p = point(8.0, 4.0)
                    val l1 = line(o, x, construction = true)
                    val l2 = line(o, p, construction = true)
                    distance(o, p, 10.0)
                    angle(l1, l2, 30.0)
                }.solve()
            solved.point(p).x shouldBe (8.660254037844386 plusOrMinus tol)
            solved.point(p).y shouldBe (5.0 plusOrMinus tol)
        }

        "radius validation rejects non-positive values and out-of-range angles" {
            shouldThrow<SketchException> {
                sketch {
                    val a = point(0.0, 0.0)
                    val c = circle(a, 1.0, fixedRadius = false)
                    radius(c, -1.0)
                }
            }.kind shouldBe SketchErrorKind.INVALID_INPUT
            shouldThrow<SketchException> {
                sketch {
                    val a = point(0.0, 0.0)
                    angle(line(a, point(1.0, 0.0)), line(a, point(0.0, 1.0)), 270.0)
                }
            }.kind shouldBe SketchErrorKind.INVALID_INPUT
        }
    })
