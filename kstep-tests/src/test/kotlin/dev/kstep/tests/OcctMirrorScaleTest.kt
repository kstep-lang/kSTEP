package dev.kstep.tests

import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.EdgeFate
import dev.kstep.geometry.MirrorPlane
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.ProfileLoop
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileRegion
import dev.kstep.geometry.ProfileSegment
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Point3
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val MS_LEAK_SLACK = 10

/**
 * Welle P (ADR-0036): mirror and scale against the real OCCT bridge, every expected value hand-computed.
 * Box 10 x 20 x 30 has volume 6000; scaling by f multiplies it by f^3.
 */
class OcctMirrorScaleTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun box() = OcctKernel.makeBox(10.0, 20.0, 30.0)

        fun OcctShape.checkBox(
            volume: Double,
            x: ClosedFloatingPointRange<Double>,
            y: ClosedFloatingPointRange<Double>,
            z: ClosedFloatingPointRange<Double>,
        ) {
            this.volume shouldBe (volume plusOrMinus 1e-6)
            val b = boundingBox!!
            b.minX shouldBe (x.start plusOrMinus 1e-7)
            b.maxX shouldBe (x.endInclusive plusOrMinus 1e-7)
            b.minY shouldBe (y.start plusOrMinus 1e-7)
            b.maxY shouldBe (y.endInclusive plusOrMinus 1e-7)
            b.minZ shouldBe (z.start plusOrMinus 1e-7)
            b.maxZ shouldBe (z.endInclusive plusOrMinus 1e-7)
        }

        fun signedMeshVolume(shape: OcctShape): Double {
            val c = shape.triangulate().coordinates
            var sum = 0.0
            var i = 0
            while (i < c.size) {
                val ax = c[i]
                val ay = c[i + 1]
                val az = c[i + 2]
                val bx = c[i + 3]
                val by = c[i + 4]
                val bz = c[i + 5]
                val cx = c[i + 6]
                val cy = c[i + 7]
                val cz = c[i + 8]
                sum += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)
                i += 9
            }
            return sum / 6.0
        }

        "mirror of a box in the plane x = 0 gives the hand-computed volume, box and 6 valid faces".config(
            enabled = available,
        ) {
            box().use { b ->
                OcctKernel.mirror(b, MirrorPlane.YZ).use { m ->
                    m.checkBox(6000.0, -10.0..0.0, 0.0..20.0, 0.0..30.0)
                    m.topology.faces shouldBe 6
                    m.topology.solids shouldBe 1
                }
            }
        }

        "mirror in an oblique plane through the origin maps (x, y, z) to (-y, -x, z)".config(enabled = available) {
            box().use { b ->
                OcctKernel.mirror(b, MirrorPlane(Point3(0.0, 0.0, 0.0), Direction3(1.0, 1.0, 0.0))).use { m ->
                    m.checkBox(6000.0, -20.0..0.0, -10.0..0.0, 0.0..30.0)
                }
            }
        }

        "mirror about a plane that is not through the origin uses the origin point".config(enabled = available) {
            box().use { b ->
                // x -> -10 - x for the plane x = -5
                OcctKernel.mirror(b, MirrorPlane.x(-5.0)).use { m ->
                    m.checkBox(6000.0, -20.0..-10.0, 0.0..20.0, 0.0..30.0)
                }
            }
        }

        "the mirrored mesh has a positive signed volume, i.e. outward winding for render and GLB".config(
            enabled = available,
        ) {
            box().use { b ->
                signedMeshVolume(b) shouldBe (6000.0 plusOrMinus 1e-6)
                OcctKernel.mirror(b, MirrorPlane.YZ).use { m ->
                    signedMeshVolume(m) shouldBe (6000.0 plusOrMinus 1e-6)
                }
                OcctKernel.makeCylinder(5.0, 12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0).use { c ->
                    OcctKernel.mirror(c, MirrorPlane.XZ).use { m ->
                        val expected = Math.PI * 25.0 * 12.0
                        m.volume shouldBe (expected plusOrMinus 1e-6)
                        // the mesh is a polygonal approximation of the cylinder: within 2 percent, and positive
                        signedMeshVolume(m) shouldBe (expected plusOrMinus expected * 0.02)
                    }
                }
            }
        }

        "scale 2 about the origin gives 48000 and the doubled box".config(enabled = available) {
            box().use { b ->
                OcctKernel.scale(b, 2.0).use { s -> s.checkBox(48000.0, 0.0..20.0, 0.0..40.0, 0.0..60.0) }
            }
        }

        "scale 2 about (10, 20, 30) grows the box around that point".config(enabled = available) {
            box().use { b ->
                OcctKernel.scale(b, 2.0, 10.0, 20.0, 30.0).use { s ->
                    s.checkBox(48000.0, -10.0..10.0, -20.0..20.0, -30.0..30.0)
                }
            }
        }

        "scale 0.5 about (5, 10, 15) gives 750".config(enabled = available) {
            box().use { b ->
                OcctKernel.scale(b, 0.5, 5.0, 10.0, 15.0).use { s ->
                    s.checkBox(750.0, 2.5..7.5, 5.0..15.0, 7.5..22.5)
                }
            }
        }

        "scale by exactly 1 is a copy, not the input".config(enabled = available) {
            box().use { b ->
                OcctKernel.scale(b, 1.0).use { s ->
                    s.checkBox(6000.0, 0.0..10.0, 0.0..20.0, 0.0..30.0)
                    (s === b) shouldBe false
                }
            }
        }

        "mirror of a compound of two islands keeps two valid solids with positive volume".config(
            enabled = available,
        ) {
            fun rect(
                x0: Double,
                x1: Double,
            ): ProfileRegion {
                val p =
                    listOf(ProfilePoint(x0, 0.0), ProfilePoint(x1, 0.0), ProfilePoint(x1, 10.0), ProfilePoint(x0, 10.0))
                return ProfileRegion(ProfileLoop(p.indices.map { ProfileSegment.Line(p[it], p[(it + 1) % 4]) }))
            }
            OcctKernel.extrudeRegions(listOf(rect(0.0, 10.0), rect(20.0, 30.0)), 5.0).use { two ->
                two.topology.solids shouldBe 2
                OcctKernel.mirror(two, MirrorPlane.YZ).use { m ->
                    m.topology.solids shouldBe 2
                    m.volume shouldBe (1000.0 plusOrMinus 1e-6)
                    OcctKernel.solidSummaries(m).forEach { (it.volume > 0.0) shouldBe true }
                    m.boundingBox!!.minX shouldBe (-30.0 plusOrMinus 1e-7)
                    m.boundingBox!!.maxX shouldBe (-0.0 plusOrMinus 1e-7)
                    OcctKernel.scale(two, 2.0).use { s ->
                        s.topology.solids shouldBe 2
                        s.volume shouldBe (8000.0 plusOrMinus 1e-6)
                    }
                }
            }
        }

        "the history maps every edge to exactly one mirrored or scaled edge".config(enabled = available) {
            box().use { b ->
                for (tracked in listOf(
                    OcctKernel.mirrorWithHistory(b, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0),
                    OcctKernel.scaleWithHistory(b, 3.0, 1.0, 2.0, 3.0),
                )) {
                    tracked.use {
                        val fates = it.history.operands.single()
                        fates.size shouldBe 12
                        val images = ArrayList<Int>()
                        fates.forEach { fate ->
                            (fate is EdgeFate.Modified || fate is EdgeFate.Kept) shouldBe true
                            fate.resultEdges.size shouldBe 1
                            images.addAll(fate.resultEdges)
                        }
                        images.toSet().size shouldBe 12
                    }
                }
            }
        }

        "the input stays open and unchanged after mirror and scale".config(enabled = available) {
            box().use { b ->
                OcctKernel.mirror(b, MirrorPlane.XY).close()
                OcctKernel.scale(b, 4.0).close()
                b.checkBox(6000.0, 0.0..10.0, 0.0..20.0, 0.0..30.0)
            }
        }

        "a closed input is an IllegalStateException".config(enabled = available) {
            val b = box()
            b.close()
            shouldThrow<IllegalStateException> { OcctKernel.mirror(b, MirrorPlane.YZ) }
            shouldThrow<IllegalStateException> { OcctKernel.scale(b, 2.0) }
        }

        "results beyond the coordinate or dimension bounds are refused in Kotlin with a clear message".config(
            enabled = available,
        ) {
            box().use { b ->
                shouldThrow<IllegalArgumentException> { OcctKernel.scale(b, 1e6) }.message!! shouldContain "1.0E7"
                OcctKernel.makeBox(1.0, 1.0, 0.001).use { thin ->
                    shouldThrow<IllegalArgumentException> { OcctKernel.scale(thin, 1e-6) }
                        .message!! shouldContain "thinner"
                }
                shouldThrow<IllegalArgumentException> { OcctKernel.mirror(b, MirrorPlane.x(9.99e6)) }
                    .message!! shouldContain "would leave"
            }
        }

        "hostile native calls are refused without leaking a registered shape".config(enabled = available) {
            val handle = OcctBridge.nativeMakeBox(10.0, 20.0, 30.0)
            try {
                fun refuse(block: () -> Unit) {
                    shouldThrow<RuntimeException> { block() }
                }
                System.gc()
                val before = OcctBridge.nativeLiveShapeCount()
                repeat(100) {
                    refuse { OcctBridge.nativeScaleShape(handle, 0.0, 0.0, 0.0, 1e6) } // result beyond +/-1e7
                    refuse { OcctBridge.nativeScaleShape(handle, 0.0, 0.0, 0.0, 0.0) }
                    refuse { OcctBridge.nativeScaleShape(handle, 0.0, 0.0, 0.0, -2.0) }
                    refuse { OcctBridge.nativeScaleShape(handle, 0.0, 0.0, 0.0, Double.NaN) }
                    refuse { OcctBridge.nativeScaleShape(handle, 0.0, 0.0, 0.0, 1e-7) }
                    refuse { OcctBridge.nativeScaleShape(handle, Double.NaN, 0.0, 0.0, 2.0) }
                    refuse { OcctBridge.nativeMirrorShape(handle, 9.99e6, 0.0, 0.0, 1.0, 0.0, 0.0) } // lands at ~2e7
                    refuse { OcctBridge.nativeMirrorShape(handle, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
                    refuse { OcctBridge.nativeMirrorShape(handle, 0.0, 0.0, 0.0, Double.NaN, 0.0, 1.0) }
                    refuse { OcctBridge.nativeMirrorShape(handle, 2e7, 0.0, 0.0, 1.0, 0.0, 0.0) }
                    refuse { OcctBridge.nativeMirrorShape(-1L, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0) }
                    refuse {
                        OcctBridge.nativeMirrorShapeTracked(
                            handle,
                            0.0,
                            0.0,
                            0.0,
                            1.0,
                            0.0,
                            0.0,
                            arrayOfNulls(0),
                        )
                    }
                    refuse { OcctBridge.nativeScaleShapeTracked(handle, 0.0, 0.0, 0.0, 2.0, arrayOfNulls(0)) }
                }
                System.gc()
                OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + MS_LEAK_SLACK
            } finally {
                OcctBridge.nativeReleaseShape(handle)
            }
        }

        "compound combines separate mirrored bodies and rejects touching ones".config(enabled = available) {
            box().use { b ->
                OcctKernel.mirror(b, MirrorPlane.x(-5.0)).use { m ->
                    OcctKernel.compound(listOf(b, m)).use { c ->
                        c.topology.solids shouldBe 2
                        c.volume shouldBe (12000.0 plusOrMinus 1e-6)
                        c.boundingBox!!.minX shouldBe (-20.0 plusOrMinus 1e-7)
                        c.boundingBox!!.maxX shouldBe (10.0 plusOrMinus 1e-7)
                    }
                    b.checkBox(6000.0, 0.0..10.0, 0.0..20.0, 0.0..30.0) // inputs were copied, not consumed
                }
                OcctKernel.mirror(b, MirrorPlane.YZ).use { touching ->
                    shouldThrow<IllegalArgumentException> { OcctKernel.compound(listOf(b, touching)) }
                        .message!! shouldContain "touch or overlap"
                    OcctKernel.boolean(BooleanOperation.UNION, b, touching).use { u ->
                        u.volume shouldBe (12000.0 plusOrMinus 1e-6)
                    }
                }
            }
        }

        // Without the bridge no OcctShape can be built at all (its constructor is internal to the module and
        // every factory needs OCCT), so mirror/scale are unreachable with a real shape: the factory fails first.
        "mirror fails predictably when the native bridge is unavailable".config(enabled = !available) {
            shouldThrow<OcctUnavailableException> { OcctKernel.makeBox(10.0, 20.0, 30.0) }
        }
    })
