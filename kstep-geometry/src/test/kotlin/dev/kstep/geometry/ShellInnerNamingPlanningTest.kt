package dev.kstep.geometry

import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.Feature
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.FilletFeature
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.HoleFeature
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.ShellFeature
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.feature.remapIndices
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
 * Welle T (ADR-0041): the source / role / generator rules for the inner wall of a shell are validated in Kotlin before
 * any native call. Every case runs without OCCT (a rebuild would throw OcctUnavailableException if it got that far).
 */
class ShellInnerNamingPlanningTest :
    StringSpec({
        val z = Direction3.Z
        val top = SelectFaces(Faces.atMax(z), expected = 1)
        val inner = listOf<GenerationSource>(ShellInnerEdges(Edges.parallelTo(z)))
        val box = BoxFeature(40.0, 30.0, 20.0)

        fun valid(features: List<Feature>) = FeatureRebuilder.validateStructure(FeatureSequence(features))

        fun reject(
            features: List<Feature>,
            fragment: String,
        ) {
            shouldThrow<IllegalArgumentException> { valid(features) }.message!! shouldContain fragment
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(FeatureSequence(features)) }
        }

        fun round(
            sources: List<GenerationSource>,
            role: GeneratedRole,
            generator: Int = 1,
            base: Int = generator,
        ) = SelectedFilletFeature(base, GeneratedEdges(generator, sources, role), 1.0)

        "an INWARD shell with ShellInnerEdges / ShellInnerFaces and role CAVITY is accepted" {
            valid(listOf(box, ShellFeature(0, top, 2.0), round(inner, GeneratedRole.CAVITY)))
            valid(
                listOf(
                    box,
                    ShellFeature(0, top, 2.0),
                    round(listOf(ShellInnerFaces(Faces.atMin(z))), GeneratedRole.CAVITY),
                ),
            )
        }

        "the shell generator is followed through a later translate" {
            valid(
                listOf(
                    box,
                    ShellFeature(0, top, 2.0),
                    TranslateFeature(1, 1.0, 0.0, 0.0),
                    round(inner, GeneratedRole.CAVITY, generator = 1, base = 2),
                ),
            )
        }

        "an OUTWARD shell is not a generator and the reason is given" {
            reject(
                listOf(box, ShellFeature(0, top, 2.0, ShellDirection.OUTWARD), round(inner, GeneratedRole.CAVITY)),
                "only an INWARD shell",
            )
        }

        "the wrong role on a shell is rejected and CAVITY is named" {
            for (role in listOf(
                GeneratedRole.SECTION,
                GeneratedRole.BOUNDARY,
                GeneratedRole.RIM_ENTRY,
                GeneratedRole.SIDE,
            )) {
                reject(listOf(box, ShellFeature(0, top, 2.0), round(inner, role)), "CAVITY")
            }
        }

        "other derived sources on a shell are rejected" {
            reject(
                listOf(box, ShellFeature(0, top, 2.0), round(listOf(BlendedEdges), GeneratedRole.CAVITY)),
                "cannot be named by",
            )
            reject(
                listOf(box, ShellFeature(0, top, 2.0), round(listOf(HoleWall), GeneratedRole.CAVITY)),
                "cannot be named by",
            )
            reject(
                listOf(box, ShellFeature(0, top, 2.0), round(listOf(BooleanSection), GeneratedRole.CAVITY)),
                "cannot be named by",
            )
            reject(
                listOf(
                    box,
                    ShellFeature(0, top, 2.0),
                    round(listOf(OperandFaces(0, Faces.atMax(z))), GeneratedRole.CAVITY),
                ),
                "cannot be named by",
            )
        }

        "shell sources on any other generator are rejected" {
            reject(
                listOf(box, FilletFeature(0, listOf(0), 1.0), round(inner, GeneratedRole.BOUNDARY)),
                "cannot be named by",
            )
            reject(
                listOf(
                    BoxFeature(40.0, 40.0, 10.0),
                    HoleFeature(0, 8.0, 12.0, 20.0, 20.0, 11.0),
                    round(inner, GeneratedRole.SECTION),
                ),
                "cannot be named by",
            )
        }

        "profile sources on a shell are rejected" {
            reject(
                listOf(box, ShellFeature(0, top, 2.0), round(listOf(ProfileVertex(0)), GeneratedRole.SIDE)),
                "ShellInnerEdges",
            )
        }

        "duplicate and too many shell sources are rejected" {
            reject(
                listOf(box, ShellFeature(0, top, 2.0), round(inner + inner, GeneratedRole.CAVITY)),
                "duplicate",
            )
            val many =
                List(OcctKernel.MAX_NAMING_SOURCES + 1) { ShellInnerFaces(Faces.atMax(Direction3(1.0, 0.0, it + 1.0))) }
            reject(listOf(box, ShellFeature(0, top, 2.0), round(many, GeneratedRole.CAVITY)), "at most")
        }

        "a hostile deep selector in a shell source is rejected without recursing" {
            var deep: EdgeSelector = Edges.all()
            repeat(100_000) { deep = deep and Edges.all() }
            reject(
                listOf(box, ShellFeature(0, top, 2.0), round(listOf(ShellInnerEdges(deep)), GeneratedRole.CAVITY)),
                "nesting",
            )
        }

        "remapping the feature indices rewrites the generator index and keeps the sources equal" {
            val consumer = round(inner, GeneratedRole.CAVITY, generator = 1, base = 1)
            val moved = consumer.remapIndices { it + 2 }
            val reference = (moved as SelectedFilletFeature).edges as GeneratedEdges
            reference.featureIndex shouldBe 3
            reference.sources shouldBe inner
            moved.baseIndex shouldBe 3
        }

        "the new sources have bounded readable descriptions" {
            ShellInnerEdges(Edges.parallelTo(z)).describe() shouldContain "ShellInnerEdges("
            ShellInnerFaces(Faces.atMin(z)).describe() shouldContain "ShellInnerFaces("
            var deep: EdgeSelector = Edges.all()
            repeat(40) { deep = deep and Edges.all() }
            (ShellInnerEdges(deep).describe().length <= 64) shouldBe true
        }

        "shellNamed checks OUTWARD, the arguments and the sources before any native call" {
            val never = OcctShape(0L)
            shouldThrow<IllegalArgumentException> {
                OcctKernel.shellNamed(never, listOf(0), 1.0, ShellDirection.OUTWARD, inner)
            }.message!! shouldContain "OUTWARD"
            shouldThrow<IllegalArgumentException> {
                OcctKernel.shellNamed(never, emptyList(), 1.0, ShellDirection.INWARD, inner)
            }.message!! shouldContain "at least one face"
            shouldThrow<IllegalArgumentException> {
                OcctKernel.shellNamed(never, listOf(0), 1.0, ShellDirection.INWARD, emptyList())
            }.message!! shouldContain "at least one generation source"
            shouldThrow<IllegalArgumentException> {
                OcctKernel.shellNamed(never, listOf(0), 1.0, ShellDirection.INWARD, listOf(HoleWall))
            }.message!! shouldContain "does not fit a shell"
            shouldThrow<IllegalArgumentException> {
                OcctKernel.shellNamed(never, listOf(0), 1.0, ShellDirection.INWARD, listOf(ProfileVertex(0)))
            }.message!! shouldContain "profile source"
            shouldThrow<IllegalArgumentException> {
                OcctKernel.shellNamed(never, listOf(0), 1.0, ShellDirection.INWARD, listOf(BlendedEdges, BlendedEdges))
            }.message!! shouldContain "duplicate"
        }
    })
