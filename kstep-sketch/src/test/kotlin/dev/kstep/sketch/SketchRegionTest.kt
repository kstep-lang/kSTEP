package dev.kstep.sketch

import dev.kstep.geometry.ProfileSegment
import dev.kstep.geometry.approximateSignedArea
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Profile extraction needs no solver: sketches whose points are all fixed solve without PlaneGCS. */
class SketchRegionTest :
    StringSpec({
        "four fixed points joined by lines form one outer loop" {
            val region =
                sketch {
                    val p =
                        listOf(
                            point(0.0, 0.0, true),
                            point(4.0, 0.0, true),
                            point(4.0, 2.0, true),
                            point(0.0, 2.0, true),
                        )
                    p.indices.forEach { line(p[it], p[(it + 1) % 4]) }
                }.solve().toRegion()
            region.holes shouldBe emptyList()
            region.outer.segments.size shouldBe 4
            Math.abs(region.outer.approximateSignedArea()) shouldBe (8.0 plusOrMinus 1e-9)
        }

        "a circle inside a rectangle becomes a hole, whatever the creation order" {
            val region =
                sketch {
                    val c = point(2.0, 1.0, true)
                    circle(c, 0.5)
                    val p =
                        listOf(
                            point(0.0, 0.0, true),
                            point(4.0, 0.0, true),
                            point(4.0, 2.0, true),
                            point(0.0, 2.0, true),
                        )
                    p.indices.forEach { line(p[it], p[(it + 1) % 4]) }
                }.solve().toRegion()
            region.outer.segments.size shouldBe 4
            region.holes.size shouldBe 1
            region.holes[0]
                .segments
                .single()
                .shouldBeInstanceOf<ProfileSegment.Circle>()
        }

        "construction lines are ignored by the profile" {
            val region =
                sketch {
                    val p =
                        listOf(
                            point(0.0, 0.0, true),
                            point(4.0, 0.0, true),
                            point(4.0, 2.0, true),
                            point(0.0, 2.0, true),
                        )
                    p.indices.forEach { line(p[it], p[(it + 1) % 4]) }
                    line(p[0], p[2], construction = true)
                }.solve().toRegion()
            region.outer.segments.size shouldBe 4
        }

        "a point shared by three profile segments is reported as NOT_CLOSED with its name" {
            val solved =
                sketch {
                    val a = point(0.0, 0.0, true, name = "hub")
                    val b = point(4.0, 0.0, true)
                    val c = point(4.0, 4.0, true)
                    val d = point(0.0, 4.0, true)
                    line(a, b)
                    line(b, c)
                    line(c, d)
                    line(d, a)
                    line(a, c)
                }.solve()
            val e = shouldThrow<SketchException> { solved.toRegion() }
            e.kind shouldBe SketchErrorKind.NOT_CLOSED
            e.details.any { it.startsWith("hub") } shouldBe true
        }

        "an open chain is NOT_CLOSED" {
            val solved =
                sketch {
                    val a = point(0.0, 0.0, true)
                    val b = point(4.0, 0.0, true)
                    val c = point(4.0, 4.0, true)
                    line(a, b)
                    line(b, c)
                }.solve()
            shouldThrow<SketchException> { solved.toRegion() }.kind shouldBe SketchErrorKind.NOT_CLOSED
        }

        "a sketch with no profile geometry is INVALID_PROFILE" {
            val solved = sketch { point(0.0, 0.0, true) }.solve()
            shouldThrow<SketchException> { solved.toRegion() }.kind shouldBe SketchErrorKind.INVALID_PROFILE
        }

        "a circle outside the outer loop is INVALID_PROFILE" {
            val solved =
                sketch {
                    val p =
                        listOf(
                            point(0.0, 0.0, true),
                            point(4.0, 0.0, true),
                            point(4.0, 2.0, true),
                            point(0.0, 2.0, true),
                        )
                    p.indices.forEach { line(p[it], p[(it + 1) % 4]) }
                    circle(point(10.0, 10.0, true), 1.0)
                }.solve()
            shouldThrow<SketchException> { solved.toRegion() }.kind shouldBe SketchErrorKind.INVALID_PROFILE
        }
    })
