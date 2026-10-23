package dev.kstep.geometry

import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureHistoryErrorKind
import dev.kstep.geometry.feature.FeatureHistoryException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.MirrorFeature
import dev.kstep.geometry.feature.ScaleFeature
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Point3
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Welle P (ADR-0036): every scalar rule of mirror/scale is checked in Kotlin BEFORE any native call, so these cases
 * need no OCCT. The shape argument is never reached; a closed dummy would only matter after validation.
 */
class MirrorScaleValidationTest :
    StringSpec({
        // A handle that is never used: validation throws before withHandle. 0 is not a registered handle.
        val never = OcctShape(0L)

        fun scaleMessage(factor: Double): String =
            shouldThrow<IllegalArgumentException> { OcctKernel.scale(never, factor) }.message!!

        "scale factor 0 is rejected and says so" {
            scaleMessage(0.0) shouldContain "must not be 0"
        }

        "a negative scale factor is rejected with the hint to use mirror" {
            val m = scaleMessage(-1.0)
            m shouldContain "-1.0"
            m shouldContain "use mirror(...) for a reflection"
        }

        "NaN, infinity and out-of-range scale factors are rejected and name the value" {
            scaleMessage(Double.NaN) shouldContain "NaN"
            scaleMessage(Double.POSITIVE_INFINITY) shouldContain "finite"
            scaleMessage(Double.NEGATIVE_INFINITY) shouldContain "finite"
            scaleMessage(1e7) shouldContain "1.0E7"
            scaleMessage(1e-7) shouldContain "1.0E-7"
        }

        "an invalid scale centre is rejected" {
            shouldThrow<IllegalArgumentException> { OcctKernel.scale(never, 2.0, Double.NaN, 0.0, 0.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.scale(never, 2.0, 0.0, 2e7, 0.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.scaleWithHistory(never, 0.0) }
        }

        "a zero or non-finite mirror normal and a bad origin are rejected" {
            shouldThrow<IllegalArgumentException> { OcctKernel.mirror(never, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
                .message!! shouldContain "direction length"
            shouldThrow<IllegalArgumentException> { OcctKernel.mirror(never, 0.0, 0.0, 0.0, Double.NaN, 0.0, 1.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.mirror(never, Double.NaN, 0.0, 0.0, 1.0, 0.0, 0.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.mirrorWithHistory(never, 2e7, 0.0, 0.0, 1.0, 0.0, 0.0) }
        }

        "the MirrorPlane factories describe the coordinate planes" {
            MirrorPlane.YZ shouldBe MirrorPlane(Point3(0.0, 0.0, 0.0), Direction3.X)
            MirrorPlane.XZ.normal shouldBe Direction3.Y
            MirrorPlane.XY.normal shouldBe Direction3.Z
            MirrorPlane.x(-5.0).origin shouldBe Point3(-5.0, 0.0, 0.0)
            MirrorPlane.y(2.0).origin shouldBe Point3(0.0, 2.0, 0.0)
            MirrorPlane.z(3.0).normal shouldBe Direction3.Z
        }

        "compound validates its list size before any native call" {
            shouldThrow<IllegalArgumentException> { OcctKernel.compound(emptyList()) }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.compound(
                    List(OcctKernel.MAX_COMPOUND_PARTS + 1) { never },
                )
            }
        }

        // ---- feature structure ----

        val box = BoxFeature(10.0, 20.0, 30.0)

        "a mirror or scale feature must refer to an earlier feature" {
            for (bad in listOf(MirrorFeature(0), ScaleFeature(0, 2.0), MirrorFeature(5), ScaleFeature(1, 2.0))) {
                shouldThrow<IllegalArgumentException> {
                    FeatureRebuilder.validateStructure(
                        FeatureSequence(listOf(bad)),
                    )
                }.message!! shouldContain "baseIndex"
            }
            FeatureRebuilder.validateStructure(FeatureSequence(listOf(box, MirrorFeature(0), ScaleFeature(1, 2.0))))
        }

        "the MirrorFeature default plane is x = 0" {
            val f = MirrorFeature(0)
            listOf(f.originX, f.originY, f.originZ, f.normalX, f.normalY, f.normalZ) shouldBe
                listOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }

        "remove and move keep mirror/scale references consistent" {
            val h =
                FeatureHistory(
                    FeatureSequence(listOf(box, BoxFeature(1.0, 1.0, 1.0), MirrorFeature(0), ScaleFeature(2, 2.0))),
                )
            h.remove(1)
            h.current.features shouldBe listOf(box, MirrorFeature(0), ScaleFeature(1, 2.0))
            h.undo().features.size shouldBe 4
            shouldThrow<FeatureHistoryException> { h.remove(2) }.kind shouldBe
                FeatureHistoryErrorKind.REFERENCED_BY_LATER_FEATURE
            val g = FeatureHistory(FeatureSequence(listOf(box, BoxFeature(1.0, 1.0, 1.0), MirrorFeature(0))))
            g.move(1, 0)
            g.current.features shouldBe listOf(BoxFeature(1.0, 1.0, 1.0), box, MirrorFeature(1))
        }

        "add in the middle shifts a mirror reference; undo and redo restore equal sequences" {
            val seq = FeatureSequence(listOf(box, MirrorFeature(0, originX = -5.0), TranslateFeature(1, 1.0, 0.0, 0.0)))
            val h = FeatureHistory(seq)
            h.add(BoxFeature(1.0, 1.0, 1.0), at = 0)
            h.current.features[2] shouldBe MirrorFeature(1, originX = -5.0)
            val added = h.current
            h.undo().features shouldBe seq.features
            h.redo() shouldBe added
        }

        "adding a mirror feature through the history validates its reference" {
            val h = FeatureHistory(FeatureSequence(listOf(box)))
            h.add(MirrorFeature(0))
            h.add(ScaleFeature(1, 0.5))
            h.current.features.size shouldBe 3
            shouldThrow<FeatureHistoryException> { h.add(MirrorFeature(7)) }.kind shouldBe
                FeatureHistoryErrorKind.INVALID_SEQUENCE
        }

        "GeneratedEdges naming the mirror step itself as generator is refused with the hint" {
            val profile = listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 10.0))
            val sequence =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(profile, 5.0),
                        MirrorFeature(0),
                        SelectedFilletFeature(1, GeneratedEdges(1, listOf(ProfileVertex(0))), 1.0),
                    ),
                )
            val m = shouldThrow<IllegalArgumentException> { FeatureRebuilder.validateStructure(sequence) }.message!!
            m shouldContain "MirrorFeature"
            m shouldContain "name the edge at its generator before the step"
        }

        "a generator before the mirror step plans a path through it" {
            val profile = listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 10.0))
            val sequence =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(profile, 5.0),
                        MirrorFeature(0),
                        ScaleFeature(1, 2.0),
                        SelectedFilletFeature(2, GeneratedEdges(0, listOf(ProfileVertex(0))), 1.0),
                    ),
                )
            FeatureRebuilder.validateStructure(sequence)
        }
    })
