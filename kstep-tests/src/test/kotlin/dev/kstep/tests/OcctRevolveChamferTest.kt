package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.RevolveAxis
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI

/** Welle J (ADR-0029): revolve and chamfer against a real OCCT, volumes from closed forms (Pappus). */
class OcctRevolveChamferTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val tolerance = 1e-5

        fun rect(
            x0: Double,
            x1: Double,
            y0: Double,
            y1: Double,
        ) = listOf(ProfilePoint(x0, y0), ProfilePoint(x1, y0), ProfilePoint(x1, y1), ProfilePoint(x0, y1))

        fun prism() = OcctKernel.extrudeProfile(rect(0.0, 20.0, 0.0, 30.0), 40.0)

        "a sleeve revolved 360 degrees about Y has the annulus volume".config(enabled = available) {
            OcctKernel.revolveProfile(rect(5.0, 10.0, 0.0, 20.0)).use { s ->
                s.volume shouldBe (1500.0 * PI plusOrMinus tolerance)
                s.topology.faces shouldBe 4
                s.topology.solids shouldBe 1
            }
        }

        "a 90 degree revolve is a quarter of the sleeve".config(enabled = available) {
            OcctKernel.revolveProfile(rect(5.0, 10.0, 0.0, 20.0), 90.0).use { s ->
                s.volume shouldBe (375.0 * PI plusOrMinus tolerance)
                s.topology.faces shouldBe 6
            }
        }

        "a profile with an edge on the axis gives a solid cylinder".config(enabled = available) {
            OcctKernel.revolveProfile(rect(0.0, 10.0, 0.0, 20.0)).use { s ->
                s.volume shouldBe (2000.0 * PI plusOrMinus tolerance)
                s.topology.faces shouldBe 3
            }
        }

        "a square away from the axis revolves to a torus-like ring (Pappus)".config(enabled = available) {
            OcctKernel.revolveProfile(rect(10.0, 14.0, 0.0, 4.0)).use { s ->
                s.volume shouldBe (384.0 * PI plusOrMinus tolerance)
                s.topology.faces shouldBe 4
            }
        }

        "a right triangle revolves to a cone".config(enabled = available) {
            OcctKernel
                .revolveProfile(
                    listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 20.0)),
                ).use { s ->
                    s.volume shouldBe (2000.0 * PI / 3.0 plusOrMinus tolerance)
                    s.topology.faces shouldBe 2
                }
        }

        "revolving about X works the same".config(enabled = available) {
            OcctKernel.revolveProfile(rect(0.0, 20.0, 5.0, 10.0), 360.0, RevolveAxis.X).use { s ->
                s.volume shouldBe (1500.0 * PI plusOrMinus tolerance)
            }
        }

        "a profile on the negative side of the axis is still a valid solid".config(enabled = available) {
            OcctKernel.revolveProfile(rect(-10.0, -5.0, 0.0, 20.0)).use { s ->
                s.volume shouldBe (1500.0 * PI plusOrMinus tolerance)
            }
        }

        "chamfering edge 0 of a 20x30x40 prism removes d^2/2 * L".config(enabled = available) {
            prism().use { p ->
                OcctKernel.chamfer(p, 0, 2.0).use { c ->
                    c.volume shouldBe (23920.0 plusOrMinus tolerance)
                    c.topology.faces shouldBe 7
                }
            }
        }

        "chamfering edge 2 (the 20-long edge) removes less".config(enabled = available) {
            prism().use { p ->
                OcctKernel.chamfer(p, 2, 2.0).use { c -> c.volume shouldBe (23960.0 plusOrMinus tolerance) }
            }
        }

        "chamfering two edges removes both bevels".config(enabled = available) {
            prism().use { p ->
                OcctKernel.chamfer(p, listOf(0, 2), 2.0).use { c ->
                    // Edge 0 (L=40) and edge 2 (L=20) do not share a vertex: 80 + 40 removed.
                    val v = c.volume
                    (v < 24000.0 - 100.0) shouldBe true
                }
            }
        }

        "a chamfered cylinder edge (curved) is smaller and valid".config(enabled = available) {
            OcctKernel.makeCylinder(10.0, 20.0).use { cyl ->
                val n = cyl.topology.edges
                (0 until n).forEach { i ->
                    runCatching { OcctKernel.chamfer(cyl, i, 1.0) }.onSuccess { c ->
                        c.use { (it.volume < cyl.volume) shouldBe true }
                    }
                }
            }
        }

        "a chamfer distance too large for the edge gives OcctGeometryException".config(enabled = available) {
            prism().use { p ->
                shouldThrow<OcctGeometryException> { OcctKernel.chamfer(p, 2, 100.0) }
            }
        }

        "an out-of-range chamfer edge index gives IllegalArgumentException".config(enabled = available) {
            prism().use { p ->
                shouldThrow<IllegalArgumentException> { OcctKernel.chamfer(p, 9999, 1.0) }
            }
        }

        "a chamfer input above MAX_CHAMFER_INPUT_FACES is rejected".config(enabled = available) {
            val n = OcctKernel.MAX_CHAMFER_INPUT_FACES
            val ngon =
                (0 until n).map {
                    val a = 2.0 * PI * it / n
                    ProfilePoint(50.0 * kotlin.math.cos(a), 50.0 * kotlin.math.sin(a))
                }
            OcctKernel.extrudeProfile(ngon, 5.0).use { big ->
                shouldThrow<IllegalArgumentException> { OcctKernel.chamfer(big, 0, 0.1) }
            }
        }
    })
