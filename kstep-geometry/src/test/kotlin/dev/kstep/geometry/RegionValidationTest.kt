package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs

class RegionValidationTest :
    StringSpec({
        fun p(
            x: Double,
            y: Double,
        ) = ProfilePoint(x, y)

        fun rect(
            x0: Double,
            y0: Double,
            x1: Double,
            y1: Double,
            ccw: Boolean = true,
        ): ProfileLoop {
            val pts = listOf(p(x0, y0), p(x1, y0), p(x1, y1), p(x0, y1))
            val ordered = if (ccw) pts else pts.reversed()
            return ProfileLoop(ordered.indices.map { i -> ProfileSegment.Line(ordered[i], ordered[(i + 1) % 4]) })
        }

        fun circle(
            cx: Double,
            cy: Double,
            r: Double,
        ) = ProfileLoop(listOf(ProfileSegment.Circle(p(cx, cy), r)))

        fun area(loop: ProfileLoop) = RegionValidation.signedArea(RegionValidation.polygon(loop))

        "a plate with a circular hole is valid" {
            RegionValidation.validate(ProfileRegion(rect(0.0, 0.0, 40.0, 20.0), listOf(circle(20.0, 10.0, 3.0))))
        }

        "an outer loop of two arcs and two lines (a slot) is valid" {
            val slot =
                ProfileLoop(
                    listOf(
                        ProfileSegment.Line(p(5.0, 0.0), p(15.0, 0.0)),
                        ProfileSegment.Arc(p(15.0, 0.0), p(20.0, 5.0), p(15.0, 10.0)),
                        ProfileSegment.Line(p(15.0, 10.0), p(5.0, 10.0)),
                        ProfileSegment.Arc(p(5.0, 10.0), p(0.0, 5.0), p(5.0, 0.0)),
                    ),
                )
            RegionValidation.validate(ProfileRegion(slot))
            area(slot) shouldBe (100.0 + Math.PI * 25.0).plusOrMinusAbs(1.0)
        }

        "an open loop is rejected" {
            val open =
                ProfileLoop(
                    listOf(
                        ProfileSegment.Line(p(0.0, 0.0), p(1.0, 0.0)),
                        ProfileSegment.Line(p(1.0, 0.0), p(1.0, 1.0)),
                        ProfileSegment.Line(p(1.0, 1.0), p(0.5, 0.5)),
                    ),
                )
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(
                    ProfileRegion(open),
                )
            }.message!! shouldContain
                "not closed"
        }

        "a circle sharing its loop with another segment is rejected" {
            val bad =
                ProfileLoop(
                    listOf(ProfileSegment.Circle(p(0.0, 0.0), 1.0), ProfileSegment.Line(p(1.0, 0.0), p(2.0, 0.0))),
                )
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(
                    ProfileRegion(bad),
                )
            }.message!! shouldContain
                "only segment"
        }

        "a collinear arc and a coincident arc are rejected" {
            val collinear =
                ProfileLoop(
                    listOf(
                        ProfileSegment.Arc(p(0.0, 0.0), p(1.0, 0.0), p(2.0, 0.0)),
                        ProfileSegment.Line(p(2.0, 0.0), p(0.0, 0.0)),
                    ),
                )
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(
                    ProfileRegion(collinear),
                )
            }.message!! shouldContain
                "collinear"
            val closedArc = ProfileLoop(listOf(ProfileSegment.Arc(p(0.0, 0.0), p(1.0, 1.0), p(0.0, 0.0))))
            shouldThrow<IllegalArgumentException> { RegionValidation.validate(ProfileRegion(closedArc)) }
        }

        "a hole outside the outer loop is rejected" {
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(ProfileRegion(rect(0.0, 0.0, 10.0, 10.0), listOf(circle(30.0, 30.0, 2.0))))
            }.message!! shouldContain "hole 0"
        }

        "a hole crossing the outer contour is rejected" {
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(ProfileRegion(rect(0.0, 0.0, 10.0, 10.0), listOf(circle(10.0, 5.0, 2.0))))
            }
        }

        "overlapping and nested holes are rejected" {
            val outer = rect(0.0, 0.0, 100.0, 100.0)
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(
                    ProfileRegion(outer, listOf(circle(40.0, 50.0, 10.0), circle(50.0, 50.0, 10.0))),
                )
            }
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(
                    ProfileRegion(outer, listOf(circle(50.0, 50.0, 20.0), circle(50.0, 50.0, 5.0))),
                )
            }
            RegionValidation.validate(ProfileRegion(outer, listOf(circle(20.0, 50.0, 10.0), circle(70.0, 50.0, 10.0))))
        }

        "radius, coordinate and finiteness bounds are enforced" {
            shouldThrow<IllegalArgumentException> { RegionValidation.validate(ProfileRegion(circle(0.0, 0.0, 0.0))) }
            shouldThrow<IllegalArgumentException> { RegionValidation.validate(ProfileRegion(circle(0.0, 0.0, 2e7))) }
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(
                    ProfileRegion(circle(Double.NaN, 0.0, 1.0)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(ProfileRegion(rect(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0)))
            }
            shouldThrow<IllegalArgumentException> { RegionValidation.validate(ProfileRegion(rect(0.0, 0.0, 2e7, 1.0))) }
        }

        "an empty loop is rejected" {
            shouldThrow<IllegalArgumentException> { RegionValidation.validate(ProfileRegion(ProfileLoop(emptyList()))) }
        }

        "more than 64 loops are rejected" {
            val holes = List(64) { i -> circle(2.0 + 3.0 * (i % 20), 2.0 + 3.0 * (i / 20), 0.5) }
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(ProfileRegion(rect(0.0, 0.0, 100.0, 100.0), holes))
            }.message!! shouldContain
                "loops"
        }

        "more than 512 segments are rejected" {
            val n = RegionValidation.MAX_REGION_SEGMENTS + 1
            val pts =
                List(n) { i ->
                    val a = 2.0 * Math.PI * i / n
                    p(100.0 * Math.cos(a), 100.0 * Math.sin(a))
                }
            val polygon = ProfileLoop(pts.indices.map { i -> ProfileSegment.Line(pts[i], pts[(i + 1) % n]) })
            shouldThrow<IllegalArgumentException> {
                RegionValidation.validate(
                    ProfileRegion(polygon),
                )
            }.message!! shouldContain
                "segments"
        }

        "a clockwise outer loop is reversed to counter-clockwise" {
            val region = ProfileRegion(rect(0.0, 0.0, 10.0, 10.0, ccw = false))
            (area(region.outer) < 0.0) shouldBe true
            (area(region.normalizedForOcct().outer) > 0.0) shouldBe true
        }

        "a counter-clockwise hole is reversed to clockwise, a correct one is left alone" {
            val region =
                ProfileRegion(
                    rect(0.0, 0.0, 100.0, 100.0),
                    listOf(rect(10.0, 10.0, 20.0, 20.0, ccw = true), rect(50.0, 50.0, 60.0, 60.0, ccw = false)),
                )
            val normalized = region.normalizedForOcct()
            normalized.holes.forEach { (area(it) < 0.0) shouldBe true }
            normalized.holes[1] shouldBe region.holes[1]
            abs(area(normalized.holes[0])) shouldBe 100.0.plusOrMinusAbs(1e-9)
        }

        "reversing an arc loop swaps the arc endpoints and keeps the area magnitude" {
            val slot =
                ProfileLoop(
                    listOf(
                        ProfileSegment.Line(p(5.0, 0.0), p(5.0, 10.0)),
                        ProfileSegment.Arc(p(5.0, 10.0), p(0.0, 5.0), p(5.0, 0.0)),
                    ),
                )
            val cw = ProfileRegion(slot)
            val flipped = cw.normalizedForOcct()
            (area(flipped.outer) > 0.0) shouldBe true
            abs(area(flipped.outer)) shouldBe abs(area(cw.outer)).plusOrMinusAbs(1e-9)
        }

        "encoding lists the outer loop first, then holes, six doubles per segment" {
            val encoded =
                RegionValidation.encode(
                    ProfileRegion(rect(0.0, 0.0, 4.0, 2.0), listOf(circle(2.0, 1.0, 0.5))),
                )
            encoded.loopSizes.toList() shouldBe listOf(4, 1)
            encoded.kinds.toList() shouldBe listOf(0, 0, 0, 0, 2)
            encoded.data.size shouldBe 30
            encoded.data.slice(24..26) shouldBe listOf(2.0, 1.0, 0.5)
        }

        "revolveRegion validates the region before any native call" {
            val open =
                ProfileLoop(
                    listOf(
                        ProfileSegment.Line(p(10.0, 0.0), p(20.0, 0.0)),
                        ProfileSegment.Line(p(20.0, 0.0), p(20.0, 5.0)),
                        ProfileSegment.Line(p(20.0, 5.0), p(15.0, 3.0)),
                    ),
                )
            shouldThrow<IllegalArgumentException> { OcctKernel.revolveRegion(ProfileRegion(open), 360.0) }
                .message!! shouldContain "not closed"
            val outside = ProfileRegion(rect(10.0, 0.0, 20.0, 5.0), listOf(rect(30.0, 1.0, 35.0, 4.0)))
            shouldThrow<IllegalArgumentException> { OcctKernel.revolveRegion(outside, 360.0) }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.revolveRegionNamed(outside, 360.0, sources = emptyList())
            }
        }
    })

private fun Double.plusOrMinusAbs(tolerance: Double) = this plusOrMinus tolerance
