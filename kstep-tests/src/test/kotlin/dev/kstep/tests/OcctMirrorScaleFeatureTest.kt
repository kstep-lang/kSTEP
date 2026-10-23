package dev.kstep.tests

import dev.kstep.geometry.BlendedEdges
import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.GeneratedRole
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileVertex
import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.MirrorFeature
import dev.kstep.geometry.feature.ScaleFeature
import dev.kstep.geometry.feature.SelectedChamferFeature
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.and
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.PI

/**
 * Welle P (ADR-0036): [MirrorFeature] and [ScaleFeature] inside a [FeatureSequence], including the persistent edge
 * naming that has to survive the step. Expected values are hand-computed.
 */
class OcctMirrorScaleFeatureTest :
    StringSpec({
        val occt = OcctKernel.availability() is OcctAvailability.Available
        val z = Direction3.Z

        // L profile: 40x10 foot plus 10x20 upright = 400 + 200 = 600 mm2.
        val lProfile =
            listOf(
                ProfilePoint(0.0, 0.0),
                ProfilePoint(40.0, 0.0),
                ProfilePoint(40.0, 10.0),
                ProfilePoint(10.0, 10.0),
                ProfilePoint(10.0, 30.0),
                ProfilePoint(0.0, 30.0),
            )
        val rect =
            listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(10.0, 20.0), ProfilePoint(0.0, 20.0))
        val oneCorner = GeneratedEdges(0, listOf(ProfileVertex(0)), expected = 1)
        val filletLoss = (4.0 - PI) * 30.0 // (1 - pi/4) * r^2 * L with r = 2, L = 30

        "extrude, mirror, union gives the symmetric bracket: 24000, one solid, x in [-40, 40]".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(lProfile, 20.0),
                        MirrorFeature(0),
                        BooleanFeature(BooleanOperation.UNION, 0, 1),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                r.shapes[0].volume shouldBe (12000.0 plusOrMinus 1e-6)
                r.shapes[1].volume shouldBe (12000.0 plusOrMinus 1e-6)
                r.final.volume shouldBe (24000.0 plusOrMinus 1e-6)
                r.final.topology.solids shouldBe 1
                val b = r.final.boundingBox!!
                b.minX shouldBe (-40.0 plusOrMinus 1e-7)
                b.maxX shouldBe (40.0 plusOrMinus 1e-7)
                b.minY shouldBe (0.0 plusOrMinus 1e-7)
                b.maxY shouldBe (30.0 plusOrMinus 1e-7)
                b.minZ shouldBe (0.0 plusOrMinus 1e-7)
                b.maxZ shouldBe (20.0 plusOrMinus 1e-7)
                // Measured on OCCT 7.9.3: the two coplanar halves are fused into 10 faces, not the 14 of a plain sum.
                r.final.topology.faces shouldBe 10
            }
        }

        "a corner edge named at the extrude is still found after the mirror step".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(ExtrudeFeature(rect, 30.0), MirrorFeature(0), SelectedFilletFeature(1, oneCorner, 2.0)),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                r.final.volume shouldBe (6000.0 - filletLoss plusOrMinus 1e-3)
                r.final.volume shouldBe (5974.2478 plusOrMinus 1e-3)
                r.final.topology.faces shouldBe 7
                r.final.boundingBox!!.maxX shouldBe (0.0 plusOrMinus 1e-7)
            }
        }

        "the named corner follows the geometry, not the index, when the mirror plane moves".config(enabled = occt) {
            // Mirror in x = 5: the body lands at x in [0, 10] again (x -> 10 - x), the named vertex 0 at (0, 0) -> (10, 0).
            val seq =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(rect, 30.0),
                        MirrorFeature(0, originX = 5.0),
                        SelectedFilletFeature(1, oneCorner, 2.0),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                r.final.volume shouldBe (6000.0 - filletLoss plusOrMinus 1e-3)
                val c = r.final.triangulate().coordinates
                // The rounded corner is the mirrored (10, 0): the sharp vertex (10, 0, z) must be gone, (0, 0, z) kept.
                var sharpAtTen = false
                var sharpAtZero = false
                var i = 0
                while (i < c.size) {
                    if (kotlin.math.abs(c[i] - 10.0) < 1e-6 && kotlin.math.abs(c[i + 1]) < 1e-6) sharpAtTen = true
                    if (kotlin.math.abs(c[i]) < 1e-6 && kotlin.math.abs(c[i + 1]) < 1e-6) sharpAtZero = true
                    i += 3
                }
                sharpAtTen shouldBe false
                sharpAtZero shouldBe true
            }
        }

        "fillet then mirror then chamfer of the blend outline reproduces the unmirrored hand value".config(
            enabled = occt,
        ) {
            val onlyCorner = Edges.parallelTo(z) and Edges.atMin(Direction3(1.0, 1.0, 0.0))
            val outline =
                GeneratedEdges(1, listOf(BlendedEdges), GeneratedRole.BOUNDARY, within = Edges.atMax(z), expected = 1)
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(20.0, 20.0, 10.0),
                        SelectedFilletFeature(
                            0,
                            dev.kstep.geometry.feature
                                .SelectEdges(onlyCorner, expected = 1),
                            2.0,
                        ),
                        MirrorFeature(1),
                        SelectedChamferFeature(2, outline, 1.0),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                r.shapes[2].volume shouldBe (4000.0 - (1.0 - PI / 4.0) * 4.0 * 10.0 plusOrMinus 1e-6)
                // Same number as ADR-0034's unmirrored case: the chamfer chain along the blend outline is symmetric.
                r.final.volume shouldBe (3972.107 plusOrMinus 0.01)
                r.final.topology.faces shouldBe 10
            }
        }

        "TrackedEdges is followed through a scale step: the edge on x = 10 lands on x = 20".config(enabled = occt) {
            val selector = Edges.parallelTo(z) and Edges.atMax(Direction3(1.0, 1.0, 0.0))
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(10.0, 20.0, 30.0),
                        ScaleFeature(0, 2.0),
                        SelectedFilletFeature(1, TrackedEdges(0, selector, expected = 1), 2.0),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                r.shapes[1].boundingBox!!.maxX shouldBe (20.0 plusOrMinus 1e-7)
                // 20 x 40 x 60 minus one rounded vertical edge of length 60: (4 - pi) * 60.
                r.final.volume shouldBe (48000.0 - (4.0 - PI) * 60.0 plusOrMinus 1e-3)
                val c = r.final.triangulate().coordinates
                var sharpFar = false
                var sharpNear = false
                var i = 0
                while (i < c.size) {
                    val farCorner = kotlin.math.abs(c[i] - 20.0) < 1e-6 && kotlin.math.abs(c[i + 1] - 40.0) < 1e-6
                    if (farCorner) sharpFar = true
                    if (kotlin.math.abs(c[i]) < 1e-6 && kotlin.math.abs(c[i + 1]) < 1e-6) sharpNear = true
                    i += 3
                }
                sharpFar shouldBe false // the edge at (10, 20) of the 10 x 20 box is now at (20, 40) and is rounded
                sharpNear shouldBe true
            }
        }

        "a generator before a scale step is followed through it".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(ExtrudeFeature(rect, 30.0), ScaleFeature(0, 2.0), SelectedFilletFeature(1, oneCorner, 2.0)),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                r.final.volume shouldBe (48000.0 - (4.0 - PI) * 60.0 plusOrMinus 1e-3)
            }
        }

        "naming the mirror step itself as generator is a structured error with the hint".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(rect, 30.0),
                        MirrorFeature(0),
                        SelectedFilletFeature(1, GeneratedEdges(1, listOf(ProfileVertex(0)), expected = 1), 2.0),
                    ),
                )
            val e = shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(seq) }
            e.message!! shouldContain "name the edge at its generator before the step"
        }

        "a wrong expected count through a mirror step lists the candidates".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(rect, 30.0),
                        MirrorFeature(0),
                        SelectedFilletFeature(1, GeneratedEdges(0, listOf(ProfileVertex(0)), expected = 3), 2.0),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(seq) }
            e.featureIndex shouldBe 2
            val naming = e.namingFailure!!
            naming.matched.size shouldBe 1
            naming.candidates.isNotEmpty() shouldBe true
            naming.message!! shouldContain "expected 3"
        }

        "feature history: add mirror, undo, redo and rebuild give the right volumes at every step".config(
            enabled = occt,
        ) {
            val h = FeatureHistory(FeatureSequence(listOf(BoxFeature(10.0, 20.0, 30.0))))
            h.add(MirrorFeature(0))
            h.add(BooleanFeature(BooleanOperation.UNION, 0, 1))

            fun finalVolume(): Double = FeatureRebuilder.rebuild(h.current).use { it.final.volume }
            finalVolume() shouldBe (12000.0 plusOrMinus 1e-6)
            h.undo()
            finalVolume() shouldBe (6000.0 plusOrMinus 1e-6) // mirror alone: the same volume
            h.undo()
            finalVolume() shouldBe (6000.0 plusOrMinus 1e-6)
            h.redo()
            h.redo()
            finalVolume() shouldBe (12000.0 plusOrMinus 1e-6)
            h.add(ScaleFeature(2, 0.5))
            finalVolume() shouldBe (1500.0 plusOrMinus 1e-6)
            h.undo().features.size shouldBe 3
        }

        "changing the scale factor with copy() rebuilds a different, hand-computed result".config(enabled = occt) {
            fun volume(factor: Double): Double {
                val seq = FeatureSequence(listOf(BoxFeature(10.0, 20.0, 30.0), ScaleFeature(0, factor)))
                return FeatureRebuilder.rebuild(seq).use { it.final.volume }
            }
            volume(2.0) shouldBe (48000.0 plusOrMinus 1e-6)
            volume(3.0) shouldBe (162000.0 plusOrMinus 1e-6)
            ScaleFeature(0, 2.0).copy(factor = 3.0) shouldBe ScaleFeature(0, 3.0)
        }
    })
