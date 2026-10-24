package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.ThickenFeature
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.FaceKind
import dev.kstep.geometry.select.FaceSelectionException
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.minus
import dev.kstep.geometry.select.or
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Welle Q2 (ADR-0040): thicken (per-face outward offset) against the real OCCT bridge. Box 40 x 30 x 20: the top face
 * 2 mm outward gives 40 * 30 * 22 = 26400; the top by 2 and the +X face by 3 gives 28380 (measured, the corner
 * overlap is counted once).
 */
class OcctThickenFeatureTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val top = Faces.atMax(Direction3.Z)

        fun box() = OcctKernel.makeBox(40.0, 30.0, 20.0)

        "the top face pushed 2 mm outward: 26400, six faces, z up to 22".config(enabled = available) {
            box().use { b ->
                OcctKernel.thicken(b, top, 2.0, expected = 1).use { s ->
                    s.volume shouldBe (26400.0 plusOrMinus 1e-6)
                    s.topology.faces shouldBe 6
                    s.topology.solids shouldBe 1
                    s.boundingBox!!.maxZ shouldBe (22.0 plusOrMinus 1e-7)
                    s.boundingBox!!.minZ shouldBe (0.0 plusOrMinus 1e-7)
                }
                b.volume shouldBe (24000.0 plusOrMinus 1e-9)
            }
        }

        "two faces with one distance extend each other's neighbours: the box grows to 42 x 30 x 22".config(
            enabled = available,
        ) {
            box().use { b ->
                OcctKernel.thicken(b, top or Faces.atMax(Direction3.X), 2.0, expected = 2).use { s ->
                    s.volume shouldBe (42.0 * 30.0 * 22.0 plusOrMinus 1e-6)
                    s.topology.faces shouldBe 6
                    s.boundingBox!!.maxX shouldBe (42.0 plusOrMinus 1e-7)
                }
            }
        }

        "a thicken feature rebuilds after the box height changes".config(enabled = available) {
            val h =
                FeatureHistory(
                    FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), ThickenFeature(0, SelectFaces(top, 1), 2.0))),
                )
            h.rebuild().use { it.final.volume shouldBe (26400.0 plusOrMinus 1e-6) }
            h.update(0) { (it as BoxFeature).copy(dz = 30.0) }
            h.rebuild().use { it.final.volume shouldBe (38400.0 plusOrMinus 1e-6) }
            h.undo()
            h.rebuild().use { it.final.volume shouldBe (26400.0 plusOrMinus 1e-6) }
        }

        "a curved face, an empty selection and an out-of-range index are refused".config(enabled = available) {
            OcctKernel.makeCylinder(10.0, 20.0).use { c ->
                val wall = c.faces.first { it.kind == FaceKind.CYLINDER }.index
                shouldThrow<IllegalArgumentException> { OcctKernel.thicken(c, listOf(wall), 1.0) }
                    .message!! shouldContain "planar"
            }
            box().use { b ->
                shouldThrow<FaceSelectionException> { OcctKernel.thicken(b, top minus top, 1.0) }
                shouldThrow<IllegalArgumentException> { OcctKernel.thicken(b, listOf(42), 1.0) }
                    .message!! shouldContain "out of range"
            }
        }
    })
