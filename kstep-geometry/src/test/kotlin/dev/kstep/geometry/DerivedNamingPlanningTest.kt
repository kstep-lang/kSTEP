package dev.kstep.geometry

import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ChamferFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.Feature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureHistoryException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.FilletFeature
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.HoleFeature
import dev.kstep.geometry.feature.SelectedChamferFeature
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeSelector
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.and
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The source / role / generator matrix of ADR-0034 is validated in Kotlin BEFORE any native call: every case here
 * runs without OCCT (a rebuild would fail with OcctUnavailableException if it ever reached native code).
 */
class DerivedNamingPlanningTest :
    StringSpec({
        val hole = HoleFeature(0, 8.0, 12.0, 20.0, 20.0, 11.0)
        val boxes =
            listOf(
                BoxFeature(20.0, 20.0, 10.0),
                BoxFeature(20.0, 20.0, 10.0),
                TranslateFeature(1, 10.0, 10.0, 0.0),
                BooleanFeature(BooleanOperation.UNION, 0, 2),
            )
        val rim = listOf<GenerationSource>(HoleWall)
        val section = listOf<GenerationSource>(BooleanSection)
        val profile = listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 10.0))

        fun valid(features: List<Feature>) = FeatureRebuilder.validateStructure(FeatureSequence(features))

        fun reject(
            features: List<Feature>,
            fragment: String,
        ) {
            shouldThrow<IllegalArgumentException> { valid(features) }.message!! shouldContain fragment
            // the rebuild refuses it too, before any native call (otherwise this would be an OcctUnavailableException)
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(FeatureSequence(features)) }
        }

        fun fillet(
            generator: Int,
            sources: List<GenerationSource>,
            role: GeneratedRole,
            base: Int = generator,
        ) = SelectedFilletFeature(base, GeneratedEdges(generator, sources, role), 1.0)

        "the valid combinations of the matrix are accepted" {
            valid(listOf(BoxFeature(40.0, 40.0, 10.0), hole, fillet(1, rim, GeneratedRole.RIM_ENTRY)))
            valid(listOf(BoxFeature(40.0, 40.0, 10.0), hole, fillet(1, rim, GeneratedRole.RIM_EXIT)))
            valid(listOf(BoxFeature(40.0, 40.0, 10.0), hole, fillet(1, rim, GeneratedRole.SECTION)))
            valid(boxes + fillet(3, section, GeneratedRole.SECTION))
            valid(boxes + fillet(3, listOf(OperandFaces(1, Faces.atMax(Direction3.Z))), GeneratedRole.SECTION))
            valid(
                listOf(BoxFeature(20.0, 20.0, 10.0), FilletFeature(0, listOf(0), 1.0)) +
                    fillet(1, listOf(BlendedEdges), GeneratedRole.BOUNDARY),
            )
            valid(
                listOf(BoxFeature(20.0, 20.0, 10.0), ChamferFeature(0, listOf(0), 1.0)) +
                    fillet(1, listOf(OperandEdges(0, Edges.all())), GeneratedRole.BOUNDARY),
            )
        }

        "a derived generator further up the path is followed through later operations" {
            valid(
                listOf(BoxFeature(40.0, 40.0, 10.0), hole, TranslateFeature(1, 1.0, 0.0, 0.0)) +
                    fillet(1, rim, GeneratedRole.RIM_ENTRY, base = 2),
            )
        }

        "a profile source on a derived generator, or a derived source on a sweep, is rejected" {
            reject(
                listOf(BoxFeature(40.0, 40.0, 10.0), hole, fillet(1, listOf(ProfileVertex(0)), GeneratedRole.SECTION)),
                "must be BlendedEdges",
            )
            reject(
                listOf(ExtrudeFeature(profile, 5.0), fillet(0, rim, GeneratedRole.SIDE)),
                "must be profile sources",
            )
        }

        "mixed profile and derived sources are rejected" {
            reject(
                listOf(ExtrudeFeature(profile, 5.0), fillet(0, listOf(ProfileVertex(0), HoleWall), GeneratedRole.SIDE)),
                "must be profile sources",
            )
            reject(
                listOf(
                    BoxFeature(40.0, 40.0, 10.0),
                    hole,
                    fillet(1, listOf(HoleWall, ProfileVertex(0)), GeneratedRole.SECTION),
                ),
                "must be BlendedEdges",
            )
        }

        "a role of the wrong family is rejected on both sides" {
            reject(
                listOf(ExtrudeFeature(profile, 5.0), fillet(0, listOf(ProfileVertex(0)), GeneratedRole.BOUNDARY)),
                "only fits derived features",
            )
            reject(
                listOf(BoxFeature(40.0, 40.0, 10.0), hole, fillet(1, rim, GeneratedRole.SIDE)),
                "does not support role SIDE",
            )
            reject(
                listOf(BoxFeature(40.0, 40.0, 10.0), hole, fillet(1, rim, GeneratedRole.BOUNDARY)),
                "expected SECTION",
            )
            reject(
                listOf(BoxFeature(20.0, 20.0, 10.0), FilletFeature(0, listOf(0), 1.0)) +
                    fillet(1, listOf(BlendedEdges), GeneratedRole.SECTION),
                "expected BOUNDARY",
            )
            reject(boxes + fillet(3, section, GeneratedRole.RIM_ENTRY), "does not support role RIM_ENTRY")
        }

        "RIM roles need exactly the source HoleWall on a hole" {
            reject(
                listOf(BoxFeature(40.0, 40.0, 10.0), hole, fillet(1, listOf(BooleanSection), GeneratedRole.RIM_ENTRY)),
                "RIM_ENTRY / RIM_EXIT",
            )
            reject(
                listOf(
                    BoxFeature(40.0, 40.0, 10.0),
                    hole,
                    fillet(1, listOf(HoleWall, BooleanSection), GeneratedRole.RIM_EXIT),
                ),
                "RIM_ENTRY / RIM_EXIT",
            )
        }

        "OperandEdges is refused on booleans and holes (OCCT reports vertices for their edges, spike G3)" {
            reject(boxes + fillet(3, listOf(OperandEdges(0, Edges.all())), GeneratedRole.SECTION), "OperandEdges")
            reject(
                listOf(
                    BoxFeature(40.0, 40.0, 10.0),
                    hole,
                    fillet(1, listOf(OperandEdges(0, Edges.all())), GeneratedRole.SECTION),
                ),
                "OperandEdges",
            )
        }

        "HoleWall is refused on booleans and blends, BlendedEdges on booleans and holes" {
            reject(boxes + fillet(3, rim, GeneratedRole.SECTION), "HoleWall")
            reject(
                listOf(BoxFeature(20.0, 20.0, 10.0), FilletFeature(0, listOf(0), 1.0)) +
                    fillet(1, rim, GeneratedRole.BOUNDARY),
                "HoleWall",
            )
            reject(boxes + fillet(3, listOf(BlendedEdges), GeneratedRole.SECTION), "BlendedEdges")
            reject(
                listOf(BoxFeature(40.0, 40.0, 10.0), hole, fillet(1, listOf(BlendedEdges), GeneratedRole.SECTION)),
                "BlendedEdges",
            )
        }

        "an operand outside 0..1 is rejected" {
            reject(
                boxes + fillet(3, listOf(OperandFaces(2, Faces.atMax(Direction3.Z))), GeneratedRole.SECTION),
                "OperandFaces",
            )
            reject(
                boxes + fillet(3, listOf(OperandFaces(-1, Faces.atMax(Direction3.Z))), GeneratedRole.SECTION),
                "OperandFaces",
            )
            reject(
                listOf(BoxFeature(20.0, 20.0, 10.0), FilletFeature(0, listOf(0), 1.0)) +
                    fillet(1, listOf(OperandEdges(1, Edges.all())), GeneratedRole.BOUNDARY),
                "OperandEdges",
            )
        }

        "a selector tree beyond the depth limit is rejected before any native call" {
            var deep: EdgeSelector = Edges.all()
            repeat(40) { deep = deep and Edges.all() }
            reject(
                listOf(BoxFeature(20.0, 20.0, 10.0), FilletFeature(0, listOf(0), 1.0)) +
                    fillet(1, listOf(OperandEdges(0, deep)), GeneratedRole.BOUNDARY),
                "nesting",
            )
        }

        "a hostile deep selector is rejected without recursing through it" {
            var deep: EdgeSelector = Edges.all()
            repeat(100_000) { deep = deep and Edges.all() }
            reject(
                listOf(BoxFeature(20.0, 20.0, 10.0), FilletFeature(0, listOf(0), 1.0)) +
                    fillet(1, listOf(OperandEdges(0, deep), HoleWall), GeneratedRole.BOUNDARY),
                "nesting",
            )
        }

        "generators that name nothing are rejected with the new list of generators" {
            reject(
                listOf(BoxFeature(1.0, 1.0, 1.0), fillet(0, rim, GeneratedRole.SECTION)),
                "cannot be a generator",
            )
            reject(
                listOf(
                    BoxFeature(20.0, 20.0, 10.0),
                    TranslateFeature(0, 1.0, 0.0, 0.0),
                    fillet(1, section, GeneratedRole.SECTION),
                ),
                "fillet, chamfer, boolean and hole",
            )
        }

        "duplicate and too many derived sources are rejected" {
            reject(
                listOf(
                    BoxFeature(40.0, 40.0, 10.0),
                    hole,
                    fillet(1, listOf(HoleWall, HoleWall), GeneratedRole.SECTION),
                ),
                "duplicate",
            )
            val many =
                List(OcctKernel.MAX_NAMING_SOURCES + 1) {
                    OperandFaces(
                        0,
                        Faces.atMax(
                            Direction3(
                                1.0,
                                0.0,
                                it + 1.0,
                            ),
                        ),
                    )
                }
            reject(boxes + fillet(3, many, GeneratedRole.SECTION), "at most")
        }

        "FeatureHistory refuses to replace a generator by one that no longer fits the names" {
            val h = FeatureHistory()
            boxes.forEach { h.add(it) }
            h.add(fillet(3, section, GeneratedRole.SECTION))
            val before = h.current
            shouldThrow<FeatureHistoryException> { h.replace(3, TranslateFeature(0, 1.0, 0.0, 0.0)) }
            h.current shouldBe before
        }

        "moving features keeps derived references consistent and leaves the sources untouched" {
            val h = FeatureHistory()
            h.add(BoxFeature(40.0, 40.0, 10.0))
            h.add(BoxFeature(1.0, 1.0, 1.0)) // unreferenced, moved behind the rest
            h.add(HoleFeature(0, 8.0, 12.0, 20.0, 20.0, 11.0))
            val consumer = SelectedFilletFeature(2, GeneratedEdges(2, rim, GeneratedRole.RIM_ENTRY, expected = 1), 1.0)
            h.add(consumer)
            h.move(1, 3)
            val moved = h.current.features
            (moved[1] as HoleFeature).baseIndex shouldBe 0
            val c = moved[2] as SelectedFilletFeature
            c.baseIndex shouldBe 1
            (c.edges as GeneratedEdges).featureIndex shouldBe 1
            (c.edges as GeneratedEdges).sources shouldBe rim
            (c.edges as GeneratedEdges).role shouldBe GeneratedRole.RIM_ENTRY
            h.undo()
            h.current.features[3] shouldBe consumer
        }

        "a derived source is refused by the profile resolvers" {
            val e =
                shouldThrow<IllegalArgumentException> {
                    resolveProfileSources(profile, listOf(HoleWall))
                }
            e.message!! shouldContain "derived feature"
            shouldThrow<IllegalArgumentException> {
                resolveRegionSources(ProfileRegion(ProfileLoop(emptyList())), listOf(BooleanSection))
            }
            SelectedChamferFeature(0, GeneratedEdges(0, rim, GeneratedRole.RIM_ENTRY), 1.0).baseIndex shouldBe 0
        }
    })
