package dev.kstep.geometry

import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.CylinderFeature
import dev.kstep.geometry.feature.ExternalRootFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.ExtrudeRegionsFeature
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TranslateFeature
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

private class NoNaming : ExternalRootFeature {
    override val label: String = "no-naming"

    override fun buildShape(): OcctShape = throw UnsupportedOperationException()
}

/** GeneratedEdges structure is validated in Kotlin BEFORE any native call: these cases need no OCCT. */
class GeneratedEdgesPlanningTest :
    StringSpec({
        val profile = listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 10.0))
        val extrude = ExtrudeFeature(profile, 5.0)
        val v = listOf<GenerationSource>(ProfileVertex(0))

        fun reject(
            features: List<dev.kstep.geometry.feature.Feature>,
            fragment: String,
        ) {
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.validateStructure(FeatureSequence(features)) }
                .message!! shouldContain fragment
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(FeatureSequence(features)) }
        }

        "a featureIndex after the baseIndex is rejected" {
            reject(
                listOf(extrude, BoxFeature(1.0, 1.0, 1.0), SelectedFilletFeature(0, GeneratedEdges(1, v), 1.0)),
                "featureIndex 1",
            )
        }

        "a negative featureIndex is rejected" {
            reject(listOf(extrude, SelectedFilletFeature(0, GeneratedEdges(-1, v), 1.0)), "featureIndex -1")
        }

        "a box, a cylinder, a translate and a multi-body region cannot be generators" {
            reject(listOf(BoxFeature(1.0, 1.0, 1.0), SelectedFilletFeature(0, GeneratedEdges(0, v), 1.0)), "BoxFeature")
            reject(
                listOf(CylinderFeature(1.0, 1.0), SelectedFilletFeature(0, GeneratedEdges(0, v), 1.0)),
                "CylinderFeature",
            )
            reject(
                listOf(
                    extrude,
                    TranslateFeature(0, 1.0, 0.0, 0.0),
                    SelectedFilletFeature(1, GeneratedEdges(1, v), 1.0),
                ),
                "TranslateFeature",
            )
            val region = ProfileRegion(ProfileLoop(listOf(ProfileSegment.Circle(ProfilePoint(0.0, 0.0), 1.0))))
            reject(
                listOf(ExtrudeRegionsFeature(listOf(region), 1.0), SelectedFilletFeature(0, GeneratedEdges(0, v), 1.0)),
                "multi-body",
            )
        }

        "an external feature without naming support is rejected" {
            reject(listOf(NoNaming(), SelectedFilletFeature(0, GeneratedEdges(0, v), 1.0)), "cannot be a generator")
        }

        "empty, duplicate and too many sources are rejected" {
            reject(
                listOf(extrude, SelectedFilletFeature(0, GeneratedEdges(0, emptyList()), 1.0)),
                "at least one source",
            )
            reject(
                listOf(
                    extrude,
                    SelectedFilletFeature(0, GeneratedEdges(0, listOf(ProfileVertex(0), ProfileVertex(0))), 1.0),
                ),
                "duplicate",
            )
            reject(
                listOf(
                    extrude,
                    SelectedFilletFeature(
                        0,
                        GeneratedEdges(0, List(OcctKernel.MAX_NAMING_SOURCES + 1) { ProfileVertex(it) }),
                        1.0,
                    ),
                ),
                "at most",
            )
        }

        "an ambiguous or missing operation path is rejected" {
            val cut = BooleanFeature(BooleanOperation.UNION, 0, 1)
            reject(
                listOf(
                    extrude,
                    BoxFeature(1.0, 1.0, 1.0),
                    BooleanFeature(BooleanOperation.UNION, 0, 0),
                    cut,
                    SelectedFilletFeature(2, GeneratedEdges(1, v), 1.0),
                ),
                "GeneratedEdges.featureIndex",
            )
        }

        "a valid plan with the generator equal to the baseIndex or an earlier ancestor passes" {
            FeatureRebuilder.validateStructure(
                FeatureSequence(listOf(extrude, SelectedFilletFeature(0, GeneratedEdges(0, v), 1.0))),
            )
            FeatureRebuilder.validateStructure(
                FeatureSequence(
                    listOf(
                        extrude,
                        TranslateFeature(0, 1.0, 0.0, 0.0),
                        SelectedFilletFeature(1, GeneratedEdges(0, v), 1.0),
                    ),
                ),
            )
            FeatureRebuilder.validateStructure(FeatureSequence(emptyList()))
        }
    })
