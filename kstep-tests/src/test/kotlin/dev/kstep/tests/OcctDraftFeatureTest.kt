package dev.kstep.tests

import dev.kstep.geometry.NeutralPlane
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.DraftFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.FaceKind
import dev.kstep.geometry.select.FaceSelectionException
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.Point3
import dev.kstep.geometry.select.minus
import dev.kstep.geometry.select.or
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.PI
import kotlin.math.tan

/**
 * Welle Q2 (ADR-0040): draft against the real OCCT bridge. Box 40 x 30 x 20, the four side faces, neutral plane z = 0,
 * pull +Z. The cross sections are NOT similar rectangles (40:30 against 36.5:26.5), so the frustum formula is only an
 * approximation; the exact volume is the prismatoid formula
 * V = 24000 -/+ 28000 tan(a) + 32000/3 tan(a)^2 (minus for a draft into the material).
 */
class OcctDraftFeatureTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val pullZ = NeutralPlane(Point3(0.0, 0.0, 0.0), Direction3.Z)
        val sides =
            Faces.normal(Direction3.X) or Faces.normal(Direction3.NEG_X) or
                Faces.normal(Direction3.Y) or Faces.normal(Direction3.NEG_Y)
        val t5 = tan(5.0 * PI / 180.0)
        val inward = 24000.0 - 28000.0 * t5 + 32000.0 / 3.0 * t5 * t5
        val outward = 24000.0 + 28000.0 * t5 + 32000.0 / 3.0 * t5 * t5

        fun box() = OcctKernel.makeBox(40.0, 30.0, 20.0)

        "+5 degrees narrows the box along the pull direction: exact prismatoid volume, six faces".config(
            enabled = available,
        ) {
            box().use { b ->
                OcctKernel.draft(b, sides, 5.0, pullZ, expected = 4).use { s ->
                    s.volume shouldBe (inward plusOrMinus 1e-3)
                    s.topology.faces shouldBe 6
                    s.topology.solids shouldBe 1
                    val bb = s.boundingBox!!
                    bb.minX shouldBe (0.0 plusOrMinus 1e-6)
                    bb.maxX shouldBe (40.0 plusOrMinus 1e-6)
                    bb.maxY shouldBe (30.0 plusOrMinus 1e-6)
                    // the top face is the smaller one: 2 * 20 * tan(5 deg) less in each direction
                    val top = s.faces.single { it.normal != null && it.normal!!.z > 0.99 && it.centroid.z > 19.0 }
                    top.area shouldBe ((40.0 - 40.0 * t5) * (30.0 - 40.0 * t5) plusOrMinus 1e-6)
                }
                inward shouldBe (21631.962928 plusOrMinus 1e-5)
            }
        }

        "-5 degrees widens it, and reversing the pull direction mirrors the sign convention".config(
            enabled = available,
        ) {
            box().use { b ->
                OcctKernel.draft(b, sides, -5.0, pullZ).use { s -> s.volume shouldBe (outward plusOrMinus 1e-3) }
                outward shouldBe (26531.328085 plusOrMinus 1e-5)
                // pull -Z about the plane z = 20: the material narrows towards -Z, i.e. the same body as +5 / +Z / z = 0
                val down = NeutralPlane(Point3(0.0, 0.0, 20.0), Direction3.NEG_Z)
                OcctKernel.draft(b, sides, 5.0, down).use { s -> s.volume shouldBe (inward plusOrMinus 1e-3) }
            }
        }

        "a draft feature rebuilds after the box height changes".config(enabled = available) {
            val draft = DraftFeature(0, SelectFaces(sides, expected = 4), 5.0, pullZ)
            val h = FeatureHistory(FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), draft)))
            h.rebuild().use { it.final.volume shouldBe (inward plusOrMinus 1e-3) }
            h.update(0) { (it as BoxFeature).copy(dz = 10.0) }
            val t = 10.0 * t5
            val expected = 10.0 / 6.0 * (1200.0 + 4.0 * (40.0 - t) * (30.0 - t) + (40.0 - 2 * t) * (30.0 - 2 * t))
            h.rebuild().use { it.final.volume shouldBe (expected plusOrMinus 1e-3) }
            h.undo()
            h.rebuild().use { it.final.volume shouldBe (inward plusOrMinus 1e-3) }
        }

        "a face parallel to the neutral plane, an angle that makes a face vanish and a curved face are refused"
            .config(enabled = available) {
                box().use { b ->
                    shouldThrow<OcctGeometryException> {
                        OcctKernel.draft(b, Faces.atMax(Direction3.Z), 5.0, pullZ)
                    }.message!! shouldContain "draft"
                    shouldThrow<IllegalArgumentException> { OcctKernel.draft(b, sides, 45.0, pullZ) }
                    shouldThrow<IllegalArgumentException> { OcctKernel.draft(b, sides, Double.NaN, pullZ) }
                }
                OcctKernel.makeBox(10.0, 10.0, 20.0).use { slim ->
                    shouldThrow<OcctGeometryException> { OcctKernel.draft(slim, sides, 44.0, pullZ) }
                }
                OcctKernel.makeCylinder(10.0, 20.0).use { c ->
                    val wall = c.faces.first { it.kind == FaceKind.CYLINDER }.index
                    shouldThrow<IllegalArgumentException> { OcctKernel.draft(c, listOf(wall), 5.0, pullZ) }
                        .message!! shouldContain "planar"
                }
            }

        "a draft whose result leaves the +/-1e7 coordinate range is refused".config(enabled = available) {
            // The neutral plane origin is within bounds, but a face far from it swings out by about 1e7 * tan(44.9 deg).
            val far = NeutralPlane(Point3(0.0, 0.0, -1e7), Direction3.Z)
            box().use { origin ->
                OcctKernel.translate(origin, 9e6, 0.0, 0.0).use { b ->
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.draft(b, Faces.normal(Direction3.X), -44.9, far)
                    }.message!! shouldContain "must stay within"
                }
            }
        }

        "an empty face selection reports the candidates".config(enabled = available) {
            box().use { b ->
                shouldThrow<FaceSelectionException> { OcctKernel.draft(b, sides minus sides, 5.0, pullZ) }
                shouldThrow<IllegalArgumentException> { OcctKernel.draft(b, listOf(99), 5.0, pullZ) }
                    .message!! shouldContain "out of range"
            }
        }
    })
