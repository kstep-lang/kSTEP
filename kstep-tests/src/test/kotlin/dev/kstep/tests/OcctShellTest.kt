package dev.kstep.tests

import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.EdgeFate
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.ShellDirection
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.FaceSelectionException
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.or
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.PI

private const val SHELL_LEAK_SLACK = 10

/**
 * Welle Q (ADR-0037): shell against the real OCCT bridge. Box 40 x 30 x 20 (V = 24000), top face open:
 * inward t = 2 leaves 24000 - 36*26*18 = 7152; outward t = 2 gives 44*34*22 - 24000 = 8912.
 */
class OcctShellTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun box() = OcctKernel.makeBox(40.0, 30.0, 20.0)

        fun OcctShape.checkBox(
            x: ClosedFloatingPointRange<Double>,
            y: ClosedFloatingPointRange<Double>,
            z: ClosedFloatingPointRange<Double>,
        ) {
            val b = boundingBox!!
            b.minX shouldBe (x.start plusOrMinus 1e-7)
            b.maxX shouldBe (x.endInclusive plusOrMinus 1e-7)
            b.minY shouldBe (y.start plusOrMinus 1e-7)
            b.maxY shouldBe (y.endInclusive plusOrMinus 1e-7)
            b.minZ shouldBe (z.start plusOrMinus 1e-7)
            b.maxZ shouldBe (z.endInclusive plusOrMinus 1e-7)
        }

        "an inward shell of the box keeps the bounding box: 7152, 11 faces, 24 edges, 16 vertices, one solid".config(
            enabled = available,
        ) {
            box().use { b ->
                OcctKernel.shell(b, Faces.atMax(Direction3.Z), 2.0, expected = 1).use { s ->
                    s.volume shouldBe (7152.0 plusOrMinus 1e-6)
                    s.topology.solids shouldBe 1
                    s.topology.faces shouldBe 11
                    s.topology.edges shouldBe 24
                    s.topology.vertices shouldBe 16
                    s.checkBox(0.0..40.0, 0.0..30.0, 0.0..20.0)
                }
            }
        }

        "an outward shell grows the bounding box by the wall: 8912 and [-2,42] x [-2,32] x [-2,20]".config(
            enabled = available,
        ) {
            box().use { b ->
                OcctKernel.shell(b, Faces.atMax(Direction3.Z), 2.0, ShellDirection.OUTWARD).use { s ->
                    s.volume shouldBe (8912.0 plusOrMinus 1e-6)
                    s.topology.faces shouldBe 11
                    s.checkBox(-2.0..42.0, -2.0..32.0, -2.0..20.0)
                }
            }
        }

        "a hollowed cylinder has the hand-computed volume 461 pi and 5 faces".config(enabled = available) {
            OcctKernel.makeCylinder(10.0, 20.0).use { c ->
                OcctKernel.shell(c, Faces.normal(Direction3.Z), 1.0).use { s ->
                    s.volume shouldBe (461.0 * PI plusOrMinus 1e-6)
                    s.topology.faces shouldBe 5
                    s.checkBox(-10.0..10.0, -10.0..10.0, 0.0..20.0)
                }
            }
        }

        "the index API and the selector API give the same shell, and the input stays open and unchanged".config(
            enabled = available,
        ) {
            box().use { b ->
                val top = OcctKernel.selectFaces(b, Faces.atMax(Direction3.Z), expected = 1)
                OcctKernel.shell(b, top, 2.0).use { byIndex ->
                    OcctKernel.shell(b, Faces.atMax(Direction3.Z), 2.0).use { bySelector ->
                        byIndex.volume shouldBe (bySelector.volume plusOrMinus 1e-9)
                        byIndex.topology shouldBe bySelector.topology
                    }
                }
                b.volume shouldBe (24000.0 plusOrMinus 1e-9)
                b.topology.faces shouldBe 6
            }
        }

        "two open faces make a tunnel: the opposite side faces of the box".config(enabled = available) {
            box().use { b ->
                OcctKernel.shell(b, Faces.normal(Direction3.X) or Faces.normal(Direction3.NEG_X), 2.0).use { s ->
                    // 40 x 30 x 20 with x faces open and a 2 mm wall on the other four: 24000 - 40*26*16 = 7360
                    s.volume shouldBe (7360.0 plusOrMinus 1e-6)
                }
            }
        }

        "a wall of half the smallest extent or more is refused in both directions".config(enabled = available) {
            box().use { b ->
                for (d in ShellDirection.entries) {
                    shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, Faces.atMax(Direction3.Z), 10.0, d) }
                        .message!! shouldContain "half the smallest extent"
                    shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, Faces.atMax(Direction3.Z), 12.0, d) }
                }
                OcctKernel.shell(b, Faces.atMax(Direction3.Z), 9.9).use { s ->
                    s.volume shouldBe
                        (21918.996 plusOrMinus 1e-3)
                }
            }
        }

        "an empty face selection is a FaceSelectionException listing every face".config(enabled = available) {
            box().use { b ->
                val e =
                    shouldThrow<FaceSelectionException> {
                        OcctKernel.shell(b, Faces.normal(Direction3(0.3, 1.0, 0.7)), 2.0)
                    }
                e.candidates.size shouldBe 6
                val ambiguous =
                    shouldThrow<FaceSelectionException> {
                        OcctKernel.shell(b, Faces.all(), 2.0, expected = 1)
                    }
                ambiguous.matched.size shouldBe 6
            }
        }

        "opening every face, a bad index and a duplicate are refused with a clear message".config(enabled = available) {
            box().use { b ->
                shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, (0 until 6).toList(), 2.0) }
                    .message!! shouldContain "at least one face must stay closed"
                shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, listOf(6), 2.0) }
                    .message!! shouldContain "out of range"
                shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, listOf(-1), 2.0) }
                shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, listOf(1, 1), 2.0) }
                shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, emptyList(), 2.0) }
            }
        }

        "a compound of two bodies is refused before OCCT sees it".config(enabled = available) {
            OcctKernel.makeBox(10.0, 10.0, 10.0).use { a ->
                OcctKernel.translate(a, 30.0, 0.0, 0.0).use { moved ->
                    OcctKernel.compound(listOf(a, moved)).use { two ->
                        shouldThrow<IllegalArgumentException> { OcctKernel.shell(two, listOf(0), 1.0) }
                            .message!! shouldContain "solid"
                    }
                }
            }
        }

        "a shell whose wall closes a narrow hole is an OcctGeometryException and leaks no shape".config(
            enabled = available,
        ) {
            OcctKernel.makeBox(60.0, 60.0, 40.0).use { plate ->
                OcctKernel.makeCylinder(6.0, 42.0, 30.0, 30.0, -1.0).use { pin ->
                    OcctKernel.boolean(BooleanOperation.CUT, plate, pin).use { holed ->
                        System.gc()
                        val before = OcctBridge.nativeLiveShapeCount()
                        repeat(20) {
                            // The wall (12) is thicker than the hole radius (6): OCCT builds an invalid solid.
                            val e =
                                shouldThrow<OcctGeometryException> {
                                    OcctKernel.shell(holed, Faces.atMax(Direction3.Z), 12.0, ShellDirection.OUTWARD)
                                }
                            e.message!! shouldContain "failed to shell"
                            e.cause!!.message!! shouldContain "validity"
                        }
                        // The same wall in the other direction is fine.
                        OcctKernel.shell(holed, Faces.atMax(Direction3.Z), 2.0).use { ok ->
                            ok.topology.solids shouldBe
                                1
                        }
                        System.gc()
                        OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + SHELL_LEAK_SLACK
                    }
                }
            }
        }

        "shell leaks no registered shape over repeated success and failure".config(enabled = available) {
            box().use { b ->
                System.gc()
                val before = OcctBridge.nativeLiveShapeCount()
                repeat(50) {
                    OcctKernel.shell(b, Faces.atMax(Direction3.Z), 2.0).close()
                    shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, Faces.atMax(Direction3.Z), 11.0) }
                    shouldThrow<IllegalArgumentException> { OcctKernel.shell(b, (0 until 6).toList(), 1.0) }
                }
                System.gc()
                OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + SHELL_LEAK_SLACK
            }
        }

        "hostile native shell calls are refused".config(enabled = available) {
            val handle = OcctBridge.nativeMakeBox(40.0, 30.0, 20.0)
            try {
                System.gc()
                val before = OcctBridge.nativeLiveShapeCount()
                val top = intArrayOf(5)
                repeat(20) {
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShellShape(handle, top, Double.NaN, 0) }
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShellShape(handle, top, 0.0, 0) }
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShellShape(handle, top, -1.0, 0) }
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShellShape(handle, top, 1.0, 2) }
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShellShape(handle, intArrayOf(), 1.0, 0) }
                    shouldThrow<IllegalArgumentException> {
                        OcctBridge.nativeShellShape(
                            handle,
                            IntArray(65) { it },
                            1.0,
                            0,
                        )
                    }
                    shouldThrow<IllegalArgumentException> {
                        OcctBridge.nativeShellShape(
                            handle,
                            intArrayOf(99),
                            1.0,
                            0,
                        )
                    }
                    shouldThrow<IllegalArgumentException> {
                        OcctBridge.nativeShellShape(
                            handle,
                            intArrayOf(2, 2),
                            1.0,
                            0,
                        )
                    }
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShellShape(handle, top, 10.0, 0) }
                    shouldThrow<IllegalStateException> { OcctBridge.nativeShellShape(-1L, top, 1.0, 0) }
                    shouldThrow<RuntimeException> {
                        OcctBridge.nativeShellShapeTracked(
                            handle,
                            top,
                            1.0,
                            0,
                            arrayOfNulls(0),
                        )
                    }
                }
                System.gc()
                OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + SHELL_LEAK_SLACK
            } finally {
                OcctBridge.nativeReleaseShape(handle)
            }
        }

        "the inward history keeps every original edge by identity, one to one".config(enabled = available) {
            box().use { b ->
                val top = OcctKernel.selectFaces(b, Faces.atMax(Direction3.Z), expected = 1)
                OcctKernel.shellWithHistory(b, top, 2.0).use { t ->
                    val fates = t.history.operands.single()
                    fates.size shouldBe 12
                    val images = ArrayList<Int>()
                    fates.forEach { fate ->
                        (fate is EdgeFate.Kept) shouldBe true
                        fate.resultEdges.size shouldBe 1
                        images.addAll(fate.resultEdges)
                    }
                    images.toSet().size shouldBe 12
                    // The kept images are the OUTER skin: they all lie on the original bounding box.
                    images.forEach { i ->
                        val e = t.shape.edges[i]
                        val onHull =
                            listOf(e.start, e.end).all { p ->
                                (p.x == 0.0 || p.x == 40.0 || p.y == 0.0 || p.y == 30.0 || p.z == 0.0 || p.z == 20.0)
                            }
                        onHull shouldBe true
                    }
                }
                shouldThrow<IllegalArgumentException> {
                    OcctKernel.shellWithHistory(
                        b,
                        top,
                        2.0,
                        ShellDirection.OUTWARD,
                    )
                }.message!! shouldContain "OUTWARD"
            }
        }
    })
