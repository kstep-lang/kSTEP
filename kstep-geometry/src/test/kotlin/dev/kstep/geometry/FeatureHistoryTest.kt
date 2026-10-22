package dev.kstep.geometry

import dev.kstep.geometry.feature.AddFeature
import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.CylinderFeature
import dev.kstep.geometry.feature.ExternalRootFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.Feature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureHistoryErrorKind
import dev.kstep.geometry.feature.FeatureHistoryException
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.FilletFeature
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.HoleFeature
import dev.kstep.geometry.feature.MoveFeature
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

private class StubExternal(
    override val label: String = "stub",
) : ExternalRootFeature {
    override fun buildShape(): OcctShape = throw UnsupportedOperationException()
}

class FeatureHistoryTest :
    StringSpec({
        val box = BoxFeature(40.0, 20.0, 10.0)
        val z = Edges.parallelTo(Direction3.Z)
        val profile = listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 10.0))

        fun kind(block: () -> Unit): FeatureHistoryErrorKind = shouldThrow<FeatureHistoryException>(block).kind

        "add appends, undo reverts, redo re-applies and the labels follow" {
            val h = FeatureHistory()
            h.canUndo shouldBe false
            h.add(box)
            h.add(FilletFeature(0, listOf(0), 1.0))
            h.current.features.size shouldBe 2
            h.undoLabels() shouldBe listOf("add FilletFeature", "add BoxFeature")
            h.undo().features shouldBe listOf(box)
            h.redoLabels() shouldBe listOf("add FilletFeature")
            h.redo().features.size shouldBe 2
            h.canRedo shouldBe false
        }

        "a new edit after undo clears the redo stack" {
            val h = FeatureHistory(FeatureSequence(listOf(box)))
            h.add(CylinderFeature(1.0, 1.0))
            h.undo()
            h.canRedo shouldBe true
            h.add(BoxFeature(1.0, 1.0, 1.0))
            h.canRedo shouldBe false
            kind { h.redo() } shouldBe FeatureHistoryErrorKind.NOTHING_TO_REDO
        }

        "undo and redo on an empty history are refused" {
            val h = FeatureHistory()
            kind { h.undo() } shouldBe FeatureHistoryErrorKind.NOTHING_TO_UNDO
            kind { h.redo() } shouldBe FeatureHistoryErrorKind.NOTHING_TO_REDO
        }

        "adding in the middle shifts the indices of later features, also inside edge references" {
            val tracked = SelectedFilletFeature(2, TrackedEdges(0, z), 1.0)
            val generated = SelectedFilletFeature(2, GeneratedEdges(0, listOf(ProfileVertex(0))), 1.0)
            val seq =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(profile, 5.0),
                        CylinderFeature(1.0, 1.0),
                        BooleanFeature(BooleanOperation.CUT, 0, 1),
                        tracked,
                    ),
                )
            val h = FeatureHistory(seq)
            h.add(BoxFeature(1.0, 1.0, 1.0), at = 1)
            h.current.features[3] shouldBe BooleanFeature(BooleanOperation.CUT, 0, 2)
            h.current.features[4] shouldBe SelectedFilletFeature(3, TrackedEdges(0, z), 1.0)
            h.undo().features shouldBe seq.features
            val h2 = FeatureHistory(FeatureSequence(seq.features.dropLast(1) + generated))
            h2.add(BoxFeature(1.0, 1.0, 1.0), at = 0)
            h2.current.features.last() shouldBe
                SelectedFilletFeature(3, GeneratedEdges(1, listOf(ProfileVertex(0))), 1.0)
        }

        "the new feature's own references must point backwards" {
            val h = FeatureHistory(FeatureSequence(listOf(box, CylinderFeature(1.0, 1.0))))
            kind { h.add(FilletFeature(1, listOf(0), 1.0), at = 1) } shouldBe FeatureHistoryErrorKind.INVALID_SEQUENCE
            kind { h.add(box, at = 5) } shouldBe FeatureHistoryErrorKind.INDEX_OUT_OF_RANGE
        }

        "remove is refused while a later feature refers to the feature, with the dependents listed" {
            val h =
                FeatureHistory(
                    FeatureSequence(listOf(box, FilletFeature(0, listOf(0), 1.0), TranslateFeature(1, 1.0, 0.0, 0.0))),
                )
            val e = shouldThrow<FeatureHistoryException> { h.remove(1) }
            e.kind shouldBe FeatureHistoryErrorKind.REFERENCED_BY_LATER_FEATURE
            e.dependents shouldBe listOf(2)
            h.current.features.size shouldBe 3
            // a GeneratedEdges reference counts as well
            val g =
                FeatureHistory(
                    FeatureSequence(
                        listOf(
                            ExtrudeFeature(profile, 5.0),
                            SelectedFilletFeature(0, GeneratedEdges(0, listOf(ProfileVertex(0))), 1.0),
                        ),
                    ),
                )
            kind { g.remove(0) } shouldBe FeatureHistoryErrorKind.REFERENCED_BY_LATER_FEATURE
        }

        "remove of an unreferenced feature decrements the indices of later ones" {
            val h =
                FeatureHistory(
                    FeatureSequence(
                        listOf(
                            box,
                            CylinderFeature(1.0, 1.0),
                            BoxFeature(2.0, 2.0, 2.0),
                            TranslateFeature(2, 1.0, 0.0, 0.0),
                        ),
                    ),
                )
            h.remove(1)
            h.current.features shouldBe listOf(box, BoxFeature(2.0, 2.0, 2.0), TranslateFeature(1, 1.0, 0.0, 0.0))
            h.undo().features.size shouldBe 4
            kind { h.remove(9) } shouldBe FeatureHistoryErrorKind.INDEX_OUT_OF_RANGE
        }

        "move applies the permutation to all indices and refuses a forward reference" {
            val seq =
                FeatureSequence(
                    listOf(box, CylinderFeature(1.0, 1.0), BooleanFeature(BooleanOperation.CUT, 0, 1)),
                )
            val h = FeatureHistory(seq)
            h.move(1, 0)
            h.current.features shouldBe
                listOf(CylinderFeature(1.0, 1.0), box, BooleanFeature(BooleanOperation.CUT, 1, 0))
            kind { h.move(0, 2) } shouldBe FeatureHistoryErrorKind.ORDER_VIOLATION
            val e = shouldThrow<FeatureHistoryException> { h.move(2, 0) }
            e.kind shouldBe FeatureHistoryErrorKind.ORDER_VIOLATION
            h.current.features.size shouldBe 3
            h.move(0, 0) // no-op
            h.undoCount shouldBe 1
        }

        "a GeneratedEdges reference survives a move of an unrelated feature and an undo" {
            val seq =
                FeatureSequence(
                    listOf(
                        CylinderFeature(1.0, 1.0),
                        ExtrudeFeature(profile, 5.0),
                        SelectedFilletFeature(1, GeneratedEdges(1, listOf(ProfileVertex(0))), 1.0),
                    ),
                )
            val h = FeatureHistory(seq)
            h.move(0, 1)
            h.current.features[0] shouldBe ExtrudeFeature(profile, 5.0)
            h.current.features[2] shouldBe SelectedFilletFeature(0, GeneratedEdges(0, listOf(ProfileVertex(0))), 1.0)
            h.undo().features shouldBe seq.features
        }

        "replace and update change one feature without remapping, undo restores it" {
            val hole = HoleFeature(0, 8.0, 20.0, 20.0, 10.0, 15.0)
            val h = FeatureHistory(FeatureSequence(listOf(box, hole)))
            h.update(1) { (it as HoleFeature).copy(diameter = 10.0) }
            (h.current.features[1] as HoleFeature).diameter shouldBe 10.0
            h.undo()
            (h.current.features[1] as HoleFeature).diameter shouldBe 8.0
            kind { h.replace(5, box) } shouldBe FeatureHistoryErrorKind.INDEX_OUT_OF_RANGE
        }

        "an edit that changes nothing is not recorded" {
            val h = FeatureHistory(FeatureSequence(listOf(box)))
            h.replace(0, BoxFeature(40.0, 20.0, 10.0))
            h.undoCount shouldBe 0
        }

        "the oldest undo entry is dropped past the limit" {
            val h = FeatureHistory(limit = 3)
            repeat(5) { h.add(BoxFeature(it + 1.0, 1.0, 1.0)) }
            h.undoCount shouldBe 3
            repeat(3) { h.undo() }
            h.current.features.size shouldBe 2
            kind { h.undo() } shouldBe FeatureHistoryErrorKind.NOTHING_TO_UNDO
        }

        "an invalid limit is an argument error" {
            shouldThrow<IllegalArgumentException> { FeatureHistory(limit = 0) }
            shouldThrow<IllegalArgumentException> { FeatureHistory(limit = FeatureHistory.MAX_LIMIT + 1) }
        }

        "a caller mutating its own list afterwards does not change a snapshot" {
            val mutable = mutableListOf<Feature>(box)
            val h = FeatureHistory(FeatureSequence(mutable))
            mutable.add(CylinderFeature(1.0, 1.0))
            h.current.features.size shouldBe 1
            h.add(CylinderFeature(2.0, 2.0))
            mutable.clear()
            h.undo().features shouldBe listOf(box)
        }

        "an invalid sequence is refused with its cause attached" {
            val h = FeatureHistory(FeatureSequence(listOf(box)))
            val e = shouldThrow<FeatureHistoryException> { h.add(FilletFeature(5, listOf(0), 1.0)) }
            e.kind shouldBe FeatureHistoryErrorKind.INVALID_SEQUENCE
            (e.cause is IllegalArgumentException) shouldBe true
            h.current.features.size shouldBe 1
            shouldThrow<FeatureHistoryException> {
                FeatureHistory(FeatureSequence(listOf(FilletFeature(0, listOf(0), 1.0))))
            }.kind shouldBe FeatureHistoryErrorKind.INVALID_SEQUENCE
        }

        "an external root feature is kept by reference through every edit" {
            val external = StubExternal()
            val h = FeatureHistory(FeatureSequence(listOf(external)))
            h.add(BoxFeature(1.0, 1.0, 1.0), at = 0)
            h.current.features[1] shouldBeSameInstanceAs external
            h.undo().features[0] shouldBeSameInstanceAs external
        }

        "apply accepts an explicit edit object and an empty history may be rebuilt only after an add" {
            val h = FeatureHistory()
            h.apply(AddFeature(box)).features shouldBe listOf(box)
            h.apply(MoveFeature(0, 0)).features shouldBe listOf(box)
            shouldThrow<IllegalArgumentException> { FeatureHistory().rebuild() }
        }
    })
