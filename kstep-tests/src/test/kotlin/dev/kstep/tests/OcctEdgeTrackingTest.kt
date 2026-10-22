package dev.kstep.tests

import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.EdgeFate
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.HoleFeature
import dev.kstep.geometry.feature.SelectEdges
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch

/**
 * Welle L (ADR-0031), edge tracking through OCCT's own history. Hand-computed values:
 * a 40x40x10 box cut by a 10x10x20 box at (15,15,-5) is a through-pocket, V = 16000 - 1000 = 15000;
 * filleting its four OUTER vertical edges at r=2 removes 4 * (1 - pi/4) * 4 * 10 = 34.336.
 */
class OcctEdgeTrackingTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val filletLoss = 4 * (1.0 - Math.PI / 4.0) * 4.0 * 10.0

        fun pocketSequence(
            dx: Double,
            selectOuter: Boolean,
        ) = FeatureSequence(
            listOf(
                BoxFeature(dx, 40.0, 10.0),
                BoxFeature(10.0, 10.0, 20.0),
                TranslateFeature(1, 15.0, 15.0, -5.0),
                BooleanFeature(BooleanOperation.CUT, 0, 2),
                SelectedFilletFeature(
                    baseIndex = 3,
                    edges =
                        if (selectOuter) {
                            TrackedEdges(0, Edges.parallelTo(Direction3.Z), expected = 4)
                        } else {
                            SelectEdges(Edges.parallelTo(Direction3.Z), expected = 4)
                        },
                    radius = 2.0,
                ),
            ),
        )

        "the pocket itself is V = 15000 with eight vertical edges".config(enabled = available) {
            FeatureRebuilder.rebuild(FeatureSequence(pocketSequence(40.0, true).features.take(4))).use { r ->
                r.final.volume shouldBe (15000.0 plusOrMinus 1e-6)
                OcctKernel.selectEdges(r.final, Edges.parallelTo(Direction3.Z)).size shouldBe 8
            }
        }

        "without tracking, expected = 4 is ambiguous on the pocketed body (8 candidates)".config(enabled = available) {
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(pocketSequence(40.0, false)) }
            e.featureIndex shouldBe 4
            e.selectionFailure!!.candidates.size shouldBe 8
        }

        "tracked edges from the original box single out the four outer edges: V = 14965.664, 14 faces".config(
            enabled = available,
        ) {
            FeatureRebuilder.rebuild(pocketSequence(40.0, true)).use { r ->
                r.final.volume shouldBe (15000.0 - filletLoss plusOrMinus 1e-3)
                r.final.volume shouldBe (14965.664 plusOrMinus 1e-3)
                r.final.topology.faces shouldBe 14
            }
        }

        "the tracked selection survives a parameter change: 60x40x10 gives V = 22965.664".config(enabled = available) {
            FeatureRebuilder.rebuild(pocketSequence(60.0, true)).use { r ->
                r.final.volume shouldBe (24000.0 - 1000.0 - filletLoss plusOrMinus 1e-3)
                r.final.volume shouldBe (22965.664 plusOrMinus 1e-3)
            }
        }

        "an edge removed by a later operation yields a structured deleted-edge failure".config(enabled = available) {
            // tool 10x10x20 at (-5,-5,-5) swallows the vertical box edge through the origin
            val sequence =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 40.0, 10.0),
                        BoxFeature(10.0, 10.0, 20.0),
                        TranslateFeature(1, -5.0, -5.0, -5.0),
                        BooleanFeature(BooleanOperation.CUT, 0, 2),
                        SelectedFilletFeature(3, TrackedEdges(0, Edges.parallelTo(Direction3.Z), expected = 4), 1.0),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(sequence) }
            e.featureIndex shouldBe 4
            val failure = e.selectionFailure!!
            failure.message!! shouldMatch
                Regex("(?s).*tracked edge \\d+ of feature\\[0] was (deleted|lost) by feature\\[3].*")
            failure.message!! shouldContain "BooleanFeature"
            failure.candidates.size shouldBe 4
            e.closedShapes.size shouldBe 4
        }

        "an edge removed at a LATER step reports candidates of the intermediate shape, not the source".config(
            enabled = available,
        ) {
            // pocket (feature 3, ~24 edges) first, then a second cut swallows the corner edge at the origin
            val sequence =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 40.0, 10.0),
                        BoxFeature(10.0, 10.0, 20.0),
                        TranslateFeature(1, 15.0, 15.0, -5.0),
                        BooleanFeature(BooleanOperation.CUT, 0, 2),
                        BoxFeature(10.0, 10.0, 20.0),
                        TranslateFeature(4, -5.0, -5.0, -5.0),
                        BooleanFeature(BooleanOperation.CUT, 3, 5),
                        SelectedFilletFeature(6, TrackedEdges(0, Edges.parallelTo(Direction3.Z), expected = 4), 1.0),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(sequence) }
            e.featureIndex shouldBe 7
            val failure = e.selectionFailure!!
            failure.message!! shouldMatch
                Regex("(?s).*tracked edge \\d+ of feature\\[3] was (deleted|lost) by feature\\[6].*")
            failure.candidates.size shouldBe 4
            e.closedShapes.size shouldBe 7
        }

        "translateWithHistory maps every one of the 12 box edges to its moved copy".config(enabled = available) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { box ->
                OcctKernel.translateWithHistory(box, 5.0, 0.0, 0.0).use { tracked ->
                    val fates = tracked.history.operands.single()
                    fates.size shouldBe 12
                    fates.all { it.resultEdges.size == 1 } shouldBe true
                    fates.flatMap { it.resultEdges }.toSet().size shouldBe 12
                    // geometry follows: every moved edge sits 5 mm further along X
                    fates.forEachIndexed { i, fate ->
                        tracked.shape.edges[fate.resultEdges.single()]
                            .mid.x shouldBe
                            (box.edges[i].mid.x + 5.0 plusOrMinus 1e-9)
                    }
                }
            }
        }

        "filletWithHistory reports the filleted edge as deleted and leaves the other 11 reachable".config(
            enabled = available,
        ) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { box ->
                val zEdge = OcctKernel.selectEdges(box, Edges.parallelTo(Direction3.Z)).first()
                OcctKernel.filletWithHistory(box, listOf(zEdge), 2.0).use { tracked ->
                    val fates = tracked.history.operands.single()
                    fates.size shouldBe 12
                    fates[zEdge] shouldBe EdgeFate.Deleted
                    // the filleted edge's two neighbours on the side faces are shortened, the other seven survive
                    fates.count { it is EdgeFate.Modified } shouldBe 4
                    fates.count { it is EdgeFate.Kept } shouldBe 7
                    fates.filterIndexed { i, _ -> i != zEdge }.count { it.resultEdges.isNotEmpty() } shouldBe 11
                    // never reports an image outside the result
                    fates.flatMap { it.resultEdges }.all { it in 0 until tracked.shape.topology.edges } shouldBe true
                }
            }
        }

        "booleanWithHistory has two operands and keeps the untouched outer vertical edges".config(enabled = available) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { box ->
                OcctKernel.makeBox(10.0, 10.0, 20.0).use { tool ->
                    OcctKernel.translate(tool, 15.0, 15.0, -5.0).use { moved ->
                        OcctKernel.booleanWithHistory(BooleanOperation.CUT, box, moved).use { tracked ->
                            tracked.history.operands.size shouldBe 2
                            tracked.history.operands[0].size shouldBe 12
                            tracked.history.operands[1].size shouldBe 12
                            val outerZ = OcctKernel.selectEdges(box, Edges.parallelTo(Direction3.Z), expected = 4)
                            outerZ.forEach { e ->
                                val fate = tracked.history.operands[0][e]
                                (fate is EdgeFate.Kept || fate is EdgeFate.Modified) shouldBe true
                                val image = tracked.shape.edges[fate.resultEdges.single()]
                                image.length shouldBe (10.0 plusOrMinus 1e-9)
                            }
                        }
                    }
                }
            }
        }

        "a hole feature on a tracked path keeps the box edges addressable".config(enabled = available) {
            val sequence =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 40.0, 10.0),
                        HoleFeature(0, diameter = 8.0, depth = 20.0, x = 20.0, y = 20.0, z = 15.0),
                        SelectedFilletFeature(1, TrackedEdges(0, Edges.parallelTo(Direction3.Z), expected = 4), 2.0),
                    ),
                )
            FeatureRebuilder.rebuild(sequence).use { r ->
                val holeVolume = Math.PI * 16.0 * 10.0
                r.final.volume shouldBe (16000.0 - holeVolume - filletLoss plusOrMinus 1e-3)
            }
        }

        "native tracked entry points reject a missing or empty history holder".config(enabled = available) {
            val handle = OcctBridge.nativeMakeBox(1.0, 1.0, 1.0)
            try {
                val matrix = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
                shouldThrow<NullPointerException> {
                    @Suppress("CAST_NEVER_SUCCEEDS")
                    OcctBridge.nativeTransformShapeTracked(handle, matrix, null as Array<IntArray?>)
                }
                shouldThrow<IllegalArgumentException> {
                    OcctBridge.nativeTransformShapeTracked(handle, matrix, arrayOfNulls<IntArray>(0))
                }
                shouldThrow<IllegalArgumentException> {
                    OcctBridge.nativeFilletEdgesTracked(handle, intArrayOf(0), 0.1, arrayOfNulls<IntArray>(0))
                }
                shouldThrow<IllegalArgumentException> {
                    OcctBridge.nativeChamferEdgesTracked(handle, intArrayOf(99), 0.1, arrayOfNulls<IntArray>(1))
                }
                shouldThrow<IllegalStateException> {
                    OcctBridge.nativeBooleanOpTracked(
                        1,
                        987654L,
                        handle,
                        arrayOfNulls<IntArray>(1),
                    )
                }
            } finally {
                OcctBridge.nativeReleaseShape(handle)
            }
        }
    })
