package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ChamferFeature
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.FilletFeature
import dev.kstep.geometry.feature.SelectEdges
import dev.kstep.geometry.feature.SelectedChamferFeature
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeKind
import dev.kstep.geometry.select.EdgeSelectionException
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.Faces
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Welle L (ADR-0031): selector-driven fillet/chamfer against real OCCT, with hand-computed
 * volumes. Fillet r=2 on a vertical 10 mm edge removes (1 - pi/4) * r^2 * 10 = 8.584 each.
 * Chamfer d=1 on the four top edges leaves a frustum slab: 1/3 * (1600 + 1444 + 1520) = 1521.333.
 */
class OcctSelectedFilletChamferTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val filletedBox40 = 16000.0 - 4 * (1.0 - Math.PI / 4.0) * 4.0 * 10.0
        val filletedBox60 = 24000.0 - 4 * (1.0 - Math.PI / 4.0) * 4.0 * 10.0

        "filleting the four vertical edges by selector gives V = 15965.664 and 10 faces".config(enabled = available) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { box ->
                OcctKernel.fillet(box, Edges.parallelTo(Direction3.Z), 2.0, expected = 4).use { r ->
                    r.volume shouldBe (filletedBox40 plusOrMinus 1e-3)
                    r.volume shouldBe (15965.664 plusOrMinus 1e-3)
                    r.topology.faces shouldBe 10
                }
            }
        }

        "the index API is unchanged: the same four edges by index give the same volume".config(enabled = available) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { box ->
                val indices = OcctKernel.selectEdges(box, Edges.parallelTo(Direction3.Z), expected = 4)
                OcctKernel.fillet(box, indices, 2.0).use { it.volume shouldBe (filletedBox40 plusOrMinus 1e-9) }
            }
        }

        "chamfering the four top edges by face selector gives V = 15921.333 and 10 faces".config(enabled = available) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { box ->
                OcctKernel.chamfer(box, Edges.ofFace(Faces.atMax(Direction3.Z)), 1.0, expected = 4).use { r ->
                    r.volume shouldBe (16000.0 - (1600.0 - 1521.3333333333333) plusOrMinus 1e-3)
                    r.volume shouldBe (15921.333 plusOrMinus 1e-3)
                    r.topology.faces shouldBe 10
                }
            }
        }

        "a selector re-resolves after a parameter change: widening the box keeps the selection valid".config(
            enabled = available,
        ) {
            fun sequence(dx: Double) =
                FeatureSequence(
                    listOf(
                        BoxFeature(dx, 40.0, 10.0),
                        SelectedFilletFeature(0, SelectEdges(Edges.parallelTo(Direction3.Z), expected = 4), 2.0),
                    ),
                )
            FeatureRebuilder.rebuild(sequence(40.0)).use { it.final.volume shouldBe (filletedBox40 plusOrMinus 1e-3) }
            FeatureRebuilder.rebuild(sequence(60.0)).use {
                it.final.volume shouldBe (filletedBox60 plusOrMinus 1e-3)
                it.final.volume shouldBe (23965.664 plusOrMinus 1e-3)
            }
        }

        "a selected chamfer feature rebuilds like the direct call".config(enabled = available) {
            val sequence =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 40.0, 10.0),
                        SelectedChamferFeature(
                            0,
                            SelectEdges(Edges.ofFace(Faces.atMax(Direction3.Z)), expected = 4),
                            1.0,
                        ),
                    ),
                )
            FeatureRebuilder.rebuild(sequence).use { it.final.volume shouldBe (15921.333 plusOrMinus 1e-3) }
        }

        "a selector that matches nothing fails the rebuild with a structured failure and closed shapes".config(
            enabled = available,
        ) {
            val sequence =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 40.0, 10.0),
                        SelectedFilletFeature(0, SelectEdges(Edges.ofType(EdgeKind.CIRCLE)), 2.0),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(sequence) }
            e.featureIndex shouldBe 1
            val failure = e.selectionFailure!!
            failure.candidates.size shouldBe 12
            failure.matched shouldBe emptyList()
            e.closedShapes.size shouldBe 1
            shouldThrow<IllegalStateException> { e.closedShapes[0].volume }
        }

        "an ambiguous selection reports all candidates in the failure".config(enabled = available) {
            val sequence =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 40.0, 10.0),
                        SelectedFilletFeature(0, SelectEdges(Edges.longest(), expected = 1), 2.0),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(sequence) }
            e.selectionFailure!!.candidates.size shouldBe 8
            e.message!! shouldContain "ambiguous"
        }

        "FeatureRebuildException exposes no selection failure for a non-selector failure".config(enabled = available) {
            val e =
                shouldThrow<FeatureRebuildException> {
                    FeatureRebuilder.rebuild(
                        FeatureSequence(listOf(BoxFeature(40.0, 40.0, 10.0), FilletFeature(0, listOf(0), 1e9))),
                    )
                }
            (e.selectionFailure == null) shouldBe true
        }

        "OcctKernel.selectEdges throws EdgeSelectionException directly".config(enabled = available) {
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { box ->
                shouldThrow<EdgeSelectionException> { OcctKernel.selectEdges(box, Edges.ofType(EdgeKind.CIRCLE)) }
            }
        }

        "a chamfer feature by index still replays unchanged".config(enabled = available) {
            FeatureRebuilder
                .rebuild(
                    FeatureSequence(listOf(BoxFeature(10.0, 10.0, 10.0), ChamferFeature(0, listOf(0), 1.0))),
                ).use {
                    (it.final.volume < 1000.0) shouldBe true
                }
        }
    })
