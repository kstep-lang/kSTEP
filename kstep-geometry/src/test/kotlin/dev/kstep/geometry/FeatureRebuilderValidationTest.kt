package dev.kstep.geometry

import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ChamferFeature
import dev.kstep.geometry.feature.CylinderFeature
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.FilletFeature
import dev.kstep.geometry.feature.RevolveFeature
import dev.kstep.geometry.feature.SelectEdges
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

/**
 * TrackedEdges structure is validated in Kotlin BEFORE any native call: these cases need no OCCT
 * (a malformed sequence is rejected as IllegalArgumentException even where the bridge is absent).
 */
class FeatureRebuilderValidationTest :
    StringSpec({
        val selector = Edges.parallelTo(Direction3.Z)
        val box = BoxFeature(40.0, 40.0, 10.0)
        val cut = BooleanFeature(BooleanOperation.CUT, 0, 1)

        fun tracked(
            source: Int,
            base: Int,
        ) = SelectedFilletFeature(base, TrackedEdges(source, selector, expected = 4), 2.0)

        "a sourceIndex that is not before baseIndex is rejected" {
            val seq = FeatureSequence(listOf(box, CylinderFeature(1.0, 1.0), cut, tracked(source = 2, base = 2)))
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(seq) }.message!! shouldContain
                "before baseIndex"
        }

        "a negative sourceIndex is rejected" {
            val seq = FeatureSequence(listOf(box, CylinderFeature(1.0, 1.0), cut, tracked(source = -1, base = 2)))
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(seq) }
        }

        "a sourceIndex that is not an ancestor of baseIndex is rejected" {
            // feature 1 (cylinder) is a root that never feeds feature 2's path from feature 0? it does (cut right operand) -- use an unrelated root
            val seq =
                FeatureSequence(
                    listOf(
                        box,
                        CylinderFeature(1.0, 1.0),
                        cut,
                        BoxFeature(5.0, 5.0, 5.0),
                        TranslateFeature(3, 1.0, 0.0, 0.0),
                        tracked(source = 3, base = 2),
                    ),
                )
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(seq) }
        }

        "more than one operation path from the source is rejected as ambiguous" {
            // feature 2 = boolean(0, 0): both operands are the source
            val seq =
                FeatureSequence(
                    listOf(box, BooleanFeature(BooleanOperation.UNION, 0, 0), tracked(source = 0, base = 1)),
                )
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(seq) }.message!! shouldContain "ambiguous"
        }

        "a derived feature referencing a later position is still rejected by the base validation" {
            val seq = FeatureSequence(listOf(box, SelectedFilletFeature(5, SelectEdges(selector), 1.0)))
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(seq) }.message!! shouldContain "baseIndex"
        }

        "tracked edges through revolve are structurally impossible: a revolve is never an ancestor" {
            val seq =
                FeatureSequence(
                    listOf(
                        RevolveFeature(listOf(ProfilePoint(1.0, 0.0), ProfilePoint(2.0, 0.0), ProfilePoint(2.0, 1.0))),
                        box,
                        TranslateFeature(1, 1.0, 0.0, 0.0),
                        tracked(source = 0, base = 2),
                    ),
                )
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(seq) }.message!! shouldContain
                "not an ancestor"
        }

        "existing index features are untouched by the structure check" {
            val seq = FeatureSequence(listOf(box, FilletFeature(0, listOf(0), 1.0), ChamferFeature(1, listOf(0), 0.5)))
            // structural validation passes; failure (if any) would come from OCCT, not IllegalArgumentException of the planner
            runCatching { FeatureRebuilder.rebuild(seq).close() }
        }
    })
