package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.geometry.GenerationSources
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.CylinderFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.HoleFeature
import dev.kstep.geometry.feature.SelectEdges
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import dev.kstep.sketch.SketchOperation
import dev.kstep.sketch.SketchSources
import dev.kstep.sketch.sketchFeature
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Welle N (ADR-0033): undo/redo over real shapes. Hand values: the plate 40x20x10 is 8000; four vertical edges
 * rounded at r = 2 give 7965.664 (10 faces); a through hole of d = 8 removes pi * 16 * 10 = 502.655 ->
 * 7463.009 (11 faces); widening it to d = 10 removes 785.398 -> 7180.266.
 */
class OcctFeatureHistoryTest :
    StringSpec({
        val occt = OcctKernel.availability() is OcctAvailability.Available
        val both = occt && PlaneGcsSolver.availability() is PlaneGcsAvailability.Available

        fun volumeAndFaces(history: FeatureHistory): Pair<Double, Int> =
            history.rebuild().use {
                it.final.volume to
                    it.final.topology.faces
            }

        "undo and redo walk the volume chain 8000 / 7965.664 / 7463.009 and back".config(enabled = occt) {
            val h = FeatureHistory()
            h.add(BoxFeature(40.0, 20.0, 10.0))
            h.add(SelectedFilletFeature(0, SelectEdges(Edges.parallelTo(Direction3.Z), expected = 4), 2.0))
            h.add(HoleFeature(1, 8.0, 20.0, 20.0, 10.0, 15.0))
            volumeAndFaces(h).first shouldBe (7463.009 plusOrMinus 1e-3)
            volumeAndFaces(h).second shouldBe 11
            h.undo()
            h.undo()
            volumeAndFaces(h).first shouldBe (8000.0 plusOrMinus 1e-6)
            volumeAndFaces(h).second shouldBe 6
            h.redo()
            h.redo()
            volumeAndFaces(h).first shouldBe (7463.009 plusOrMinus 1e-3)
            h.update(2) { (it as HoleFeature).copy(diameter = 10.0) }
            volumeAndFaces(h).first shouldBe (7180.266 plusOrMinus 1e-3)
            h.undo()
            volumeAndFaces(h).first shouldBe (7463.009 plusOrMinus 1e-3)
        }

        "removing and re-adding a feature in the middle keeps later references consistent".config(enabled = occt) {
            val h = FeatureHistory()
            h.add(BoxFeature(40.0, 20.0, 10.0))
            h.add(CylinderFeature(3.0, 30.0, 20.0, 10.0, -10.0))
            h.add(HoleFeature(0, 8.0, 20.0, 20.0, 10.0, 15.0))
            h.remove(1) // the cylinder is unreferenced
            h.current.features.size shouldBe 2
            (h.current.features[1] as HoleFeature).baseIndex shouldBe 0
            volumeAndFaces(h).first shouldBe (8000.0 - Math.PI * 16.0 * 10.0 plusOrMinus 1e-3)
            h.undo()
            (h.current.features[2] as HoleFeature).baseIndex shouldBe 0
            h.current.features.size shouldBe 3
        }

        "a sketch parameter change is undone and a GeneratedEdges feature survives a move and an undo".config(
            enabled = both,
        ) {
            val plate =
                sketchFeature(mapOf("width" to 40.0), SketchOperation.Extrude(10.0), label = "plate") { p ->
                    rectangle(0.0, 0.0, p["width"], 20.0)
                }
            val h = FeatureHistory()
            h.add(CylinderFeature(1.0, 1.0)) // an unrelated root that will be moved behind the plate
            h.add(plate)
            h.add(
                SelectedFilletFeature(
                    1,
                    GeneratedEdges(1, SketchSources.corners("p0", "p1", "p2", "p3"), expected = 4),
                    2.0,
                ),
            )
            volumeAndFaces(h).first shouldBe (7965.664 plusOrMinus 1e-3)
            h.move(0, 1) // the plate becomes feature 0, the fillet's indices follow
            (h.current.features[2] as SelectedFilletFeature).baseIndex shouldBe 0
            ((h.current.features[2] as SelectedFilletFeature).edges as GeneratedEdges).featureIndex shouldBe 0
            h.rebuild().use { it.shapes[2].volume }
            h.update(0) { (it as dev.kstep.sketch.SketchFeature).withParameter("width", 60.0) }
            h.rebuild().use { it.final.volume shouldBe (11965.664 plusOrMinus 1e-3) }
            h.undo() // the parameter change
            h.rebuild().use { it.final.volume shouldBe (7965.664 plusOrMinus 1e-3) }
            h.undo() // the move
            h.current.features[0] shouldBe CylinderFeature(1.0, 1.0)
            ((h.current.features[2] as SelectedFilletFeature).edges as GeneratedEdges).featureIndex shouldBe 1
        }

        "a profile extrude with GeneratedEdges moves with an inserted feature".config(enabled = occt) {
            val rect =
                listOf(
                    ProfilePoint(0.0, 0.0),
                    ProfilePoint(40.0, 0.0),
                    ProfilePoint(40.0, 20.0),
                    ProfilePoint(0.0, 20.0),
                )
            val h = FeatureHistory()
            h.add(ExtrudeFeature(rect, 10.0))
            h.add(SelectedFilletFeature(0, GeneratedEdges(0, GenerationSources.profileVertices(4), expected = 4), 2.0))
            h.add(CylinderFeature(1.0, 1.0), at = 0)
            ((h.current.features[2] as SelectedFilletFeature).edges as GeneratedEdges).featureIndex shouldBe 1
            h.rebuild().use { it.shapes[2].volume shouldBe (7965.664 plusOrMinus 1e-3) }
        }
    })
