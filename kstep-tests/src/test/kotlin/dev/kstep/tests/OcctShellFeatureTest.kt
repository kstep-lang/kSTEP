package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileVertex
import dev.kstep.geometry.ShellDirection
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.FaceIndices
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.ShellFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.FaceSelectionException
import dev.kstep.geometry.select.Faces
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.PI

private const val SF_LEAK_SLACK = 10

/**
 * Welle Q (ADR-0037): [ShellFeature] inside a [FeatureSequence] and a [FeatureHistory]. The box 40 x 30 x 20 with its
 * top open and a 2 mm wall has V = 7152; at height 30 it has V = 40*30*30 - 36*26*28 = 9792.
 */
class OcctShellFeatureTest :
    StringSpec({
        val occt = OcctKernel.availability() is OcctAvailability.Available
        val z = Direction3.Z
        val openTop = SelectFaces(Faces.atMax(z), expected = 1)
        // one 2 mm vertical-edge fillet: (1 - pi/4) * r^2 * L with r = 1, L = 20, on each of four outer edges
        val outerFilletLoss = 4.0 * (1.0 - PI / 4.0) * 20.0

        "a shell feature rebuilds to the hand-computed volume".config(enabled = occt) {
            val seq = FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), ShellFeature(0, openTop, 2.0)))
            FeatureRebuilder.rebuild(seq).use { r ->
                r.shapes[0].volume shouldBe (24000.0 plusOrMinus 1e-6)
                r.final.volume shouldBe (7152.0 plusOrMinus 1e-6)
                r.final.topology.faces shouldBe 11
            }
        }

        "FaceIndices opens exactly the listed base-shape faces".config(enabled = occt) {
            OcctKernel.makeBox(40.0, 30.0, 20.0).use { b ->
                val top = OcctKernel.selectFaces(b, Faces.atMax(z), expected = 1)
                val seq = FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), ShellFeature(0, FaceIndices(top), 2.0)))
                FeatureRebuilder.rebuild(seq).use { r -> r.final.volume shouldBe (7152.0 plusOrMinus 1e-6) }
            }
        }

        "the selector follows the geometry: update the box height, undo and redo".config(enabled = occt) {
            val h =
                FeatureHistory(FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), ShellFeature(0, openTop, 2.0))))
            h.update(0) { (it as BoxFeature).copy(dz = 30.0) }
            h.rebuild().use { it.final.volume shouldBe (9792.0 plusOrMinus 1e-6) }
            h.undo()
            h.rebuild().use { it.final.volume shouldBe (7152.0 plusOrMinus 1e-6) }
            h.redo()
            h.rebuild().use { it.final.volume shouldBe (9792.0 plusOrMinus 1e-6) }
            h.update(1) { (it as ShellFeature).copy(thickness = 3.0) }
            // 40*30*30 - 34*24*27 = 36000 - 22032
            h.rebuild().use { it.final.volume shouldBe (13968.0 plusOrMinus 1e-6) }
        }

        "remove and move keep the shell's base reference consistent".config(enabled = occt) {
            val h =
                FeatureHistory(
                    FeatureSequence(
                        listOf(BoxFeature(1.0, 1.0, 1.0), BoxFeature(40.0, 30.0, 20.0), ShellFeature(1, openTop, 2.0)),
                    ),
                )
            h.remove(0)
            h.current.features[1] shouldBe ShellFeature(0, openTop, 2.0)
            h.undo()
            h.move(1, 0)
            h.current.features[2] shouldBe ShellFeature(0, openTop, 2.0)
            h.rebuild().use { it.final.volume shouldBe (7152.0 plusOrMinus 1e-6) }
        }

        "an edge tracked through an inward shell is the OUTER edge: SelectEdges is ambiguous, TrackedEdges is not"
            .config(
                enabled = occt,
            ) {
                val tracked =
                    FeatureSequence(
                        listOf(
                            BoxFeature(40.0, 30.0, 20.0),
                            ShellFeature(0, openTop, 2.0),
                            SelectedFilletFeature(1, TrackedEdges(0, Edges.parallelTo(z), expected = 4), 1.0),
                        ),
                    )
                FeatureRebuilder.rebuild(tracked).use { r ->
                    r.final.volume shouldBe (7152.0 - outerFilletLoss plusOrMinus 1e-6)
                    r.final.topology.faces shouldBe 15
                    // After the shell there are eight vertical edges (outer and inner): a plain selector cannot choose.
                    OcctKernel.selectEdges(r.shapes[1], Edges.parallelTo(z), expected = 8).size shouldBe 8
                }
            }

        "a corner edge named at the extrude is found after the shell".config(enabled = occt) {
            val rect =
                listOf(
                    ProfilePoint(0.0, 0.0),
                    ProfilePoint(40.0, 0.0),
                    ProfilePoint(40.0, 30.0),
                    ProfilePoint(0.0, 30.0),
                )
            val seq =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(rect, 20.0),
                        ShellFeature(0, openTop, 2.0),
                        SelectedFilletFeature(1, GeneratedEdges(0, listOf(ProfileVertex(0)), expected = 1), 1.0),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                r.final.volume shouldBe (7152.0 - outerFilletLoss / 4.0 plusOrMinus 1e-6)
            }
        }

        "tracking through an OUTWARD shell is refused before any native call, with the reason".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, openTop, 2.0, ShellDirection.OUTWARD),
                        SelectedFilletFeature(1, TrackedEdges(0, Edges.parallelTo(z), expected = 4), 1.0),
                    ),
                )
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.validateStructure(seq) }.message!! shouldContain
                "inner edges of the cavity"
            // the shell alone, outward, rebuilds fine
            FeatureRebuilder
                .rebuild(FeatureSequence(seq.features.take(2)))
                .use { r -> r.final.volume shouldBe (8912.0 plusOrMinus 1e-6) }
        }

        "a failing shell reports the feature and closes everything built so far".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, SelectFaces(Faces.normal(Direction3(0.3, 1.0, 0.7))), 2.0),
                    ),
                )
            System.gc()
            val before = OcctBridge.nativeLiveShapeCount()
            repeat(10) {
                val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(seq) }
                e.featureIndex shouldBe 1
                (e.cause is FaceSelectionException) shouldBe true
                e.message!! shouldContain "matched no face"
            }
            System.gc()
            OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + SF_LEAK_SLACK
        }

        "a too-thick shell in a sequence fails at the shell feature with the thickness in the message".config(
            enabled = occt,
        ) {
            val seq = FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), ShellFeature(0, openTop, 12.0)))
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(seq) }
            e.featureIndex shouldBe 1
            e.message!! shouldContain "half the smallest extent"
        }

        "GeneratedEdges naming the shell itself is refused with the follow-up hint".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, openTop, 2.0),
                        SelectedFilletFeature(1, GeneratedEdges(1, listOf(ProfileVertex(0))), 1.0),
                    ),
                )
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.validateStructure(seq) }.message!! shouldContain
                "follow-up"
        }
    })
