package dev.kstep.tests

import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctEmptyResultException
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.Placement
import dev.kstep.geometry.ProfilePoint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.PI

/**
 * Welle J (ADR-0029): booleans, cylinder, hole and translate/transformed against a real OCCT.
 * Volumes are closed-form; face counts are the values measured against OCCT 7.9.3 (they pin that
 * `SimplifyResult` merges coplanar split faces).
 */
class OcctBooleanOperationsTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val tolerance = 1e-6

        fun plate() = OcctKernel.makeBox(40.0, 40.0, 10.0)

        fun shiftedBoxes(): Pair<OcctShape, OcctShape> =
            OcctKernel.makeBox(20.0, 20.0, 10.0) to
                OcctKernel.translate(OcctKernel.makeBox(20.0, 20.0, 10.0), 10.0, 10.0, 0.0)

        "a cylinder has the closed-form volume and 3 faces".config(enabled = available) {
            OcctKernel.makeCylinder(4.0, 10.0).use { c ->
                c.volume shouldBe (160.0 * PI plusOrMinus tolerance)
                c.topology.faces shouldBe 3
            }
        }

        "a cylinder along an arbitrary axis keeps its volume".config(enabled = available) {
            OcctKernel.makeCylinder(2.0, 5.0, 1.0, 2.0, 3.0, 1.0, 1.0, 0.0).use { c ->
                c.volume shouldBe (20.0 * PI plusOrMinus tolerance)
            }
        }

        "a through-hole with exactly coplanar ends removes the cylinder volume".config(enabled = available) {
            plate().use { blank ->
                OcctKernel.hole(blank, 8.0, 10.0, 20.0, 20.0, 10.0).use { p ->
                    p.volume shouldBe (16000.0 - 160.0 * PI plusOrMinus 1e-5)
                    p.topology.faces shouldBe 7
                }
            }
        }

        "a through-hole with overshoot gives the same result (robustness)".config(enabled = available) {
            plate().use { blank ->
                OcctKernel.hole(blank, 8.0, 12.0, 20.0, 20.0, 11.0).use { p ->
                    p.volume shouldBe (16000.0 - 160.0 * PI plusOrMinus 1e-5)
                    p.topology.faces shouldBe 7
                }
            }
        }

        "a blind hole removes only its own depth".config(enabled = available) {
            plate().use { blank ->
                OcctKernel.hole(blank, 8.0, 5.0, 20.0, 20.0, 10.0).use { p ->
                    p.volume shouldBe (16000.0 - 80.0 * PI plusOrMinus 1e-5)
                    p.topology.faces shouldBe 8
                }
            }
        }

        "a hole that misses the body returns an unchanged copy".config(enabled = available) {
            plate().use { blank ->
                OcctKernel.hole(blank, 4.0, 5.0, 100.0, 100.0, 10.0).use { p ->
                    p.volume shouldBe (16000.0 plusOrMinus tolerance)
                }
            }
        }

        "union of overlapping boxes merges split faces".config(enabled = available) {
            val (a, b) = shiftedBoxes()
            a.use {
                b.use {
                    OcctKernel.union(a, b).use { u ->
                        u.volume shouldBe (7000.0 plusOrMinus tolerance)
                        u.topology.faces shouldBe 10
                    }
                }
            }
        }

        "common of overlapping boxes is the overlap".config(enabled = available) {
            val (a, b) = shiftedBoxes()
            a.use {
                b.use {
                    OcctKernel.common(a, b).use { c ->
                        c.volume shouldBe (1000.0 plusOrMinus tolerance)
                        c.topology.faces shouldBe 6
                    }
                }
            }
        }

        "cut of overlapping boxes removes the overlap".config(enabled = available) {
            val (a, b) = shiftedBoxes()
            a.use {
                b.use {
                    OcctKernel.cut(a, b).use { c ->
                        c.volume shouldBe (3000.0 plusOrMinus tolerance)
                        c.topology.faces shouldBe 8
                    }
                }
            }
        }

        "the generic boolean entry point matches the three shortcuts".config(enabled = available) {
            val (a, b) = shiftedBoxes()
            a.use {
                b.use {
                    listOf(
                        BooleanOperation.UNION to 7000.0,
                        BooleanOperation.COMMON to 1000.0,
                        BooleanOperation.CUT to 3000.0,
                    ).forEach { (op, v) ->
                        OcctKernel.boolean(op, a, b).use { r ->
                            r.volume shouldBe
                                (v plusOrMinus tolerance)
                        }
                    }
                }
            }
        }

        "union of boxes that merely touch is one solid".config(enabled = available) {
            OcctKernel.makeBox(10.0, 10.0, 10.0).use { a ->
                OcctKernel.translate(OcctKernel.makeBox(10.0, 10.0, 10.0), 10.0, 0.0, 0.0).use { b ->
                    OcctKernel.union(a, b).use { u ->
                        u.volume shouldBe (2000.0 plusOrMinus tolerance)
                        u.topology.faces shouldBe 6
                    }
                }
            }
        }

        "union of disjoint solids is rejected as multi-body".config(enabled = available) {
            OcctKernel.makeBox(10.0, 10.0, 10.0).use { a ->
                OcctKernel.translate(OcctKernel.makeBox(10.0, 10.0, 10.0), 50.0, 0.0, 0.0).use { b ->
                    val failure = shouldThrow<OcctGeometryException> { OcctKernel.union(a, b) }
                    failure.message!! shouldContain "multi-body"
                }
            }
        }

        "a cut whose tool lies strictly inside the body is rejected as an internal void".config(enabled = available) {
            OcctKernel.makeBox(30.0, 30.0, 30.0).use { a ->
                OcctKernel.translate(OcctKernel.makeBox(5.0, 5.0, 5.0), 10.0, 10.0, 10.0).use { b ->
                    val failure = shouldThrow<OcctGeometryException> { OcctKernel.cut(a, b) }
                    failure.message!! shouldContain "internal void"
                }
            }
        }

        "a fully enclosed hole is rejected as an internal void".config(enabled = available) {
            plate().use { blank ->
                val failure =
                    shouldThrow<OcctGeometryException> {
                        OcctKernel.hole(blank, 8.0, 4.0, 20.0, 20.0, 7.0)
                    }
                failure.message!! shouldContain "internal void"
            }
        }

        "triangulating an operand while a cut runs on it never crashes the JVM".config(enabled = available) {
            repeat(20) {
                OcctKernel.makeBox(40.0, 40.0, 10.0).use { blank ->
                    val error = AtomicReference<Throwable?>(null)
                    val worker =
                        thread(start = true, name = "occt-triangulate-race") {
                            try {
                                repeat(10) { blank.triangulate() }
                            } catch (t: Throwable) {
                                error.set(t)
                            }
                        }
                    repeat(10) {
                        // A tool that misses the body shares every sub-shape with `blank`.
                        OcctKernel.hole(blank, 4.0, 2.0, 100.0, 100.0, 5.0).close()
                    }
                    worker.join()
                    error.get() shouldBe null
                }
            }
        }

        "common of disjoint solids and cut(a, a) give OcctEmptyResultException".config(enabled = available) {
            OcctKernel.makeBox(10.0, 10.0, 10.0).use { a ->
                OcctKernel.translate(OcctKernel.makeBox(10.0, 10.0, 10.0), 50.0, 0.0, 0.0).use { b ->
                    shouldThrow<OcctEmptyResultException> { OcctKernel.common(a, b) }
                    shouldThrow<OcctEmptyResultException> { OcctKernel.cut(a, a) }
                }
            }
        }

        "an operand above MAX_BOOLEAN_INPUT_FACES is rejected before OCCT works".config(enabled = available) {
            val n = OcctKernel.MAX_BOOLEAN_INPUT_FACES // n-gon prism has n + 2 faces
            val ngon =
                (0 until n).map {
                    val a = 2.0 * PI * it / n
                    ProfilePoint(50.0 * kotlin.math.cos(a), 50.0 * kotlin.math.sin(a))
                }
            OcctKernel.extrudeProfile(ngon, 5.0).use { big ->
                OcctKernel.makeBox(1.0, 1.0, 1.0).use { small ->
                    shouldThrow<IllegalArgumentException> { OcctKernel.union(big, small) }
                    shouldThrow<IllegalArgumentException> { OcctKernel.cut(small, big) }
                }
            }
        }

        "operands stay open and unchanged, and the result is independently closeable".config(enabled = available) {
            val (a, b) = shiftedBoxes()
            val r = OcctKernel.union(a, b)
            r.close()
            a.volume shouldBe (4000.0 plusOrMinus tolerance)
            b.volume shouldBe (4000.0 plusOrMinus tolerance)
            a.close()
            b.close()
        }

        "a closed operand gives IllegalStateException".config(enabled = available) {
            val (a, b) = shiftedBoxes()
            a.close()
            shouldThrow<IllegalStateException> { OcctKernel.union(a, b) }
            b.close()
        }

        "translate keeps the volume and moves the shape".config(enabled = available) {
            OcctKernel.makeBox(10.0, 10.0, 10.0).use { box ->
                OcctKernel.translate(box, 5.0, 0.0, 0.0).use { moved ->
                    moved.volume shouldBe (1000.0 plusOrMinus tolerance)
                    // Overlap of the original and the copy shifted by 5 is a 5x10x10 slab.
                    OcctKernel.common(box, moved).use { it.volume shouldBe (500.0 plusOrMinus tolerance) }
                }
                val quarterTurn = Placement.rotationZ(90.0).then(Placement.translation(10.0, 0.0, 0.0))
                OcctKernel.transformed(box, quarterTurn).use { rotated ->
                    // Rotating a 10-cube 90 degrees about Z and shifting by +10 in x lands exactly on the original.
                    OcctKernel.common(box, rotated).use { it.volume shouldBe (1000.0 plusOrMinus tolerance) }
                }
            }
        }

        "a plate with a hole exports to STEP with a cylindrical surface".config(enabled = available) {
            val outDir = File("build/occt-geometry-ops-test").apply { mkdirs() }
            plate().use { blank ->
                OcctKernel.hole(blank, 8.0, 10.0, 20.0, 20.0, 10.0).use { p ->
                    val text = p.writeStepFile(File(outDir, "plate-with-hole.step").toPath()).toFile().readText()
                    text shouldContain "MANIFOLD_SOLID_BREP"
                    text shouldContain "CYLINDRICAL_SURFACE"
                }
            }
        }

        "concurrent reads of a boolean result racing close never crash the JVM".config(enabled = available) {
            repeat(100) { i ->
                val (a, b) = shiftedBoxes()
                val u = OcctKernel.union(a, b)
                a.close()
                b.close()
                val readerError = AtomicReference<Throwable?>(null)
                val ready = CountDownLatch(1)
                val reader =
                    thread(start = true, name = "occt-boolean-race-$i") {
                        ready.countDown()
                        try {
                            repeat(20) {
                                try {
                                    u.volume shouldBe (7000.0 plusOrMinus tolerance)
                                } catch (_: IllegalStateException) {
                                    // close() on the other thread won the race -- expected.
                                }
                            }
                        } catch (t: Throwable) {
                            readerError.set(t)
                        }
                    }
                ready.await()
                u.close()
                reader.join()
                readerError.get() shouldBe null
            }
        }
    })
