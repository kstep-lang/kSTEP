package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.sketch.sketch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Welle L (ADR-0031): sketch -> solve -> extrude/revolve through BOTH native bridges. Hand-computed:
 * plate 40x20 with a r=3 hole, height 10: V = 8000 - 90*pi = 7717.257, 7 faces.
 */
class OcctSketchExtrudeTest :
    StringSpec({
        val both =
            OcctKernel.availability() is OcctAvailability.Available &&
                PlaneGcsSolver.availability() is PlaneGcsAvailability.Available

        fun plate() =
            sketch {
                val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                val centre = point(20.0, 10.0)
                offset(frame.bottomLeft, centre, dx = 20.0, dy = 10.0)
                circle(centre, 3.0)
            }.solve()

        "a solved sketch extrudes to V = 7717.257 with 7 faces".config(enabled = both) {
            plate().extrude(10.0).use { shape ->
                shape.volume shouldBe (8000.0 - 90.0 * Math.PI plusOrMinus 1e-6)
                shape.volume shouldBe (7717.257 plusOrMinus 1e-3)
                shape.topology.faces shouldBe 7
            }
        }

        "the extrude feature replays through the feature history and equals the direct extrusion".config(
            enabled = both,
        ) {
            val feature = plate().extrudeFeature(10.0)
            FeatureRebuilder.rebuild(FeatureSequence(listOf(feature))).use { result ->
                result.final.volume shouldBe (7717.257 plusOrMinus 1e-3)
            }
        }

        "a sketched ring revolves about Y to its Pappus volume".config(enabled = both) {
            // rectangle x in [10,20], y in [0,10] with a hole r=2 centred (15,5): 2*pi*15*(100 - 4*pi) = 8240.43
            val solved =
                sketch {
                    val frame = rectangle(10.0, 0.0, 10.0, 10.0)
                    val centre = point(15.0, 5.0)
                    offset(frame.bottomLeft, centre, dx = 5.0, dy = 5.0)
                    circle(centre, 2.0)
                }.solve()
            solved.revolve(360.0, RevolveAxis.Y).use { shape ->
                shape.volume shouldBe (8240.43 plusOrMinus 1e-2)
                shape.topology.faces shouldBe 5
            }
        }

        "a sketched slot (two lines, two arcs) extrudes to V = 1785.398".config(enabled = both) {
            val solved =
                sketch {
                    val c1 = point(5.0, 5.0, fixed = true)
                    val c2 = point(15.0, 5.0, fixed = true)
                    val a = point(5.0, 0.1)
                    val b = point(15.0, -0.1)
                    val c = point(15.0, 10.1)
                    val d = point(5.0, 9.9)
                    // each corner is already pinned by one vertical and its arc radius; a horizontal(a, b)
                    // on top would be redundant, and the sketch would (correctly) be reported over-constrained
                    vertical(c1, a)
                    vertical(c1, d)
                    vertical(c2, b)
                    vertical(c2, c)
                    line(a, b)
                    line(c, d)
                    arc(c2, b, c, 5.0, ccw = true)
                    arc(c1, d, a, 5.0, ccw = true)
                }.solve()
            solved.extrude(10.0).use { it.volume shouldBe ((100.0 + 25.0 * Math.PI) * 10.0 plusOrMinus 1e-5) }
        }
    })
