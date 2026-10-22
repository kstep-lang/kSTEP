package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfileLoop
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileRegion
import dev.kstep.geometry.ProfileSegment
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.feature.ExtrudeRegionFeature
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.RevolveRegionFeature
import dev.kstep.geometry.occt.OcctBridge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Welle L (ADR-0031): regions with holes through the real kernel. Hand-computed values:
 *  - 40x20 plate, hole r=3, height 10: V = 8000 - 90*pi = 7717.257, 7 faces
 *  - slot (10x10 + two semicircle ends r=5) area = 100 + 25*pi, height 10: V = 1785.398, 6 faces
 *  - revolve x in [10,20], y in [0,10] with a hole r=2 at (15,5): Pappus V = 2*pi*15*(100 - 4*pi) = 8240.43, 5 faces
 */
class OcctRegionExtrudeTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

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
            val o = if (ccw) pts else pts.reversed()
            return ProfileLoop(o.indices.map { ProfileSegment.Line(o[it], o[(it + 1) % 4]) })
        }

        fun circle(
            cx: Double,
            cy: Double,
            r: Double,
        ) = ProfileLoop(listOf(ProfileSegment.Circle(p(cx, cy), r)))

        fun slot(
            x0: Double,
            y0: Double,
        ) = ProfileLoop(
            listOf(
                ProfileSegment.Line(p(x0 + 5.0, y0), p(x0 + 15.0, y0)),
                ProfileSegment.Arc(p(x0 + 15.0, y0), p(x0 + 20.0, y0 + 5.0), p(x0 + 15.0, y0 + 10.0)),
                ProfileSegment.Line(p(x0 + 15.0, y0 + 10.0), p(x0 + 5.0, y0 + 10.0)),
                ProfileSegment.Arc(p(x0 + 5.0, y0 + 10.0), p(x0, y0 + 5.0), p(x0 + 5.0, y0)),
            ),
        )

        val plate = ProfileRegion(rect(0.0, 0.0, 40.0, 20.0), listOf(circle(20.0, 10.0, 3.0)))

        "a 40x20 plate with a r=3 hole extrudes to V = 7717.257 with 7 faces".config(enabled = available) {
            OcctKernel.extrudeRegion(plate, 10.0).use { s ->
                s.volume shouldBe (8000.0 - 90.0 * Math.PI plusOrMinus 1e-6)
                s.volume shouldBe (7717.257 plusOrMinus 1e-3)
                s.topology.faces shouldBe 7
                s.topology.solids shouldBe 1
            }
        }

        "orientation is normalised: a clockwise outer loop gives the same positive solid".config(enabled = available) {
            val cwPlate = ProfileRegion(rect(0.0, 0.0, 40.0, 20.0, ccw = false), listOf(circle(20.0, 10.0, 3.0)))
            OcctKernel.extrudeRegion(cwPlate, 10.0).use { it.volume shouldBe (7717.257 plusOrMinus 1e-3) }
        }

        "a counter-clockwise rectangular hole is flipped to a clockwise one".config(enabled = available) {
            val region = ProfileRegion(rect(0.0, 0.0, 40.0, 20.0), listOf(rect(10.0, 5.0, 20.0, 15.0, ccw = true)))
            OcctKernel.extrudeRegion(region, 10.0).use {
                it.volume shouldBe (8000.0 - 1000.0 plusOrMinus 1e-6)
                it.topology.faces shouldBe 10
            }
        }

        "a slot with two arcs extrudes to V = 1785.398 with 6 faces".config(enabled = available) {
            OcctKernel.extrudeRegion(ProfileRegion(slot(0.0, 0.0)), 10.0).use { s ->
                s.volume shouldBe ((100.0 + 25.0 * Math.PI) * 10.0 plusOrMinus 1e-6)
                s.topology.faces shouldBe 6
            }
        }

        "a slot-shaped hole in a plate cuts exactly the slot area".config(enabled = available) {
            val region = ProfileRegion(rect(-10.0, -10.0, 40.0, 20.0), listOf(slot(0.0, 0.0)))
            OcctKernel.extrudeRegion(region, 10.0).use {
                it.volume shouldBe (50.0 * 30.0 * 10.0 - (100.0 + 25.0 * Math.PI) * 10.0 plusOrMinus 1e-6)
            }
        }

        "a negative height extrudes downwards with the same volume".config(enabled = available) {
            OcctKernel.extrudeRegion(plate, -10.0).use { it.volume shouldBe (7717.257 plusOrMinus 1e-3) }
        }

        "revolving a ring-with-hole region about Y matches the Pappus volume 8240.43".config(enabled = available) {
            val region = ProfileRegion(rect(10.0, 0.0, 20.0, 10.0), listOf(circle(15.0, 5.0, 2.0)))
            OcctKernel.revolveRegion(region, 360.0, RevolveAxis.Y).use { s ->
                s.volume shouldBe (2.0 * Math.PI * 15.0 * (100.0 - 4.0 * Math.PI) plusOrMinus 1e-6)
                s.volume shouldBe (8240.43 plusOrMinus 1e-2)
                s.topology.faces shouldBe 5
            }
        }

        "a half revolution has half the volume".config(enabled = available) {
            val region = ProfileRegion(rect(10.0, 0.0, 20.0, 10.0), listOf(circle(15.0, 5.0, 2.0)))
            OcctKernel.revolveRegion(region, 180.0).use { it.volume shouldBe (4120.215 plusOrMinus 1e-2) }
        }

        "a region crossing the rotation axis is rejected before native code runs" {
            val region = ProfileRegion(rect(-5.0, 0.0, 5.0, 10.0))
            shouldThrow<IllegalArgumentException> { OcctKernel.revolveRegion(region, 360.0, RevolveAxis.Y) }
        }

        "region features replay through the feature history".config(enabled = available) {
            val sequence =
                FeatureSequence(
                    listOf(
                        ExtrudeRegionFeature(plate, 10.0),
                        RevolveRegionFeature(ProfileRegion(rect(10.0, 0.0, 20.0, 10.0)), 360.0),
                    ),
                )
            FeatureRebuilder.rebuild(sequence).use { r ->
                r.shapes[0].volume shouldBe (7717.257 plusOrMinus 1e-3)
                r.shapes[1].volume shouldBe (Math.PI * (400.0 - 100.0) * 10.0 plusOrMinus 1e-6)
            }
        }

        "native hostile region input yields documented exceptions, never a crash".config(enabled = available) {
            val one = intArrayOf(1)
            val circleKind = intArrayOf(2)
            val circleData = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
            OcctBridge.nativeReleaseShape(OcctBridge.nativeExtrudeRegion(one, circleKind, circleData, 1.0))
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(
                    IntArray(0),
                    circleKind,
                    circleData,
                    1.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(
                    one,
                    circleKind,
                    DoubleArray(5),
                    1.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(
                    intArrayOf(2),
                    circleKind,
                    circleData,
                    1.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(
                    one,
                    intArrayOf(7),
                    circleData,
                    1.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(one, circleKind, doubleArrayOf(Double.NaN, 0.0, 1.0, 0.0, 0.0, 0.0), 1.0)
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(one, circleKind, doubleArrayOf(0.0, 0.0, -1.0, 0.0, 0.0, 0.0), 1.0)
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(one, circleKind, circleData, Double.POSITIVE_INFINITY)
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(
                    IntArray(65) { 1 },
                    IntArray(65) { 2 },
                    DoubleArray(
                        65 * 6,
                    ),
                    1.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(
                    intArrayOf(513),
                    IntArray(513),
                    DoubleArray(
                        513 * 6,
                    ),
                    1.0,
                )
            }
            // discontinuous triangle: second segment does not start where the first ended
            val broken =
                doubleArrayOf(
                    0.0,
                    0.0,
                    10.0,
                    0.0,
                    0.0,
                    0.0,
                    10.0,
                    5.0,
                    10.0,
                    10.0,
                    0.0,
                    0.0,
                    10.0,
                    10.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                )
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeExtrudeRegion(
                    intArrayOf(3),
                    IntArray(3),
                    broken,
                    1.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeRevolveRegion(
                    one,
                    circleKind,
                    circleData,
                    90.0,
                    5,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeRevolveRegion(
                    one,
                    circleKind,
                    circleData,
                    0.0,
                    1,
                )
            }
            // a circle straddling the Y axis crosses it
            shouldThrow<IllegalArgumentException> {
                OcctBridge.nativeRevolveRegion(
                    one,
                    circleKind,
                    circleData,
                    90.0,
                    1,
                )
            }
        }
    })
