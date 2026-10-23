package dev.kstep.geometry

import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FaceIndices
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureHistoryErrorKind
import dev.kstep.geometry.feature.FeatureHistoryException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.SelectEdges
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.ShellFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.feature.remapIndices
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeSelector
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.FaceSelector
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.and
import dev.kstep.geometry.select.or
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Welle Q (ADR-0037): every scalar and structural rule of shell is checked in Kotlin BEFORE any native call, so these
 * cases need no OCCT. The shape argument is never reached.
 */
class ShellValidationTest :
    StringSpec({
        // Never used: validation throws before withHandle. 0 is not a registered handle.
        val never = OcctShape(0L)
        val top = SelectFaces(Faces.atMax(Direction3.Z), expected = 1)

        fun thicknessMessage(t: Double) =
            shouldThrow<IllegalArgumentException> { OcctKernel.shell(never, listOf(0), t) }.message!!

        "NaN, infinite, zero, negative and out-of-range thickness are rejected and name the value" {
            thicknessMessage(Double.NaN) shouldContain "NaN"
            thicknessMessage(Double.POSITIVE_INFINITY) shouldContain "finite"
            thicknessMessage(0.0) shouldContain "within"
            thicknessMessage(-1.0) shouldContain "-1.0"
            thicknessMessage(1e-8) shouldContain "1.0E-8"
            thicknessMessage(2e7) shouldContain "2.0E7"
        }

        "an empty, negative, duplicate or oversize face list is rejected" {
            shouldThrow<IllegalArgumentException> { OcctKernel.shell(never, emptyList(), 1.0) }
                .message!! shouldContain "at least one face"
            shouldThrow<IllegalArgumentException> { OcctKernel.shell(never, listOf(-1), 1.0) }
                .message!! shouldContain "negative"
            shouldThrow<IllegalArgumentException> { OcctKernel.shell(never, listOf(2, 2), 1.0) }
                .message!! shouldContain "unique"
            shouldThrow<IllegalArgumentException> {
                OcctKernel.shell(never, List(OcctKernel.MAX_SHELL_OPEN_FACES + 1) { it }, 1.0)
            }.message!! shouldContain "at most"
        }

        "shellWithHistory refuses OUTWARD with the reason, before looking at the shape" {
            shouldThrow<IllegalArgumentException> {
                OcctKernel.shellWithHistory(never, listOf(0), 1.0, ShellDirection.OUTWARD)
            }.message!! shouldContain "inner edges of the cavity"
        }

        "the native shell constants are the documented ones" {
            ShellDirection.INWARD.nativeCode shouldBe 0
            ShellDirection.OUTWARD.nativeCode shouldBe 1
            OcctKernel.MAX_SHELL_INPUT_FACES shouldBe 200
            OcctKernel.MAX_SHELL_OPEN_FACES shouldBe 64
        }

        // ---- feature structure ----

        "a shell feature must refer to an earlier feature" {
            for (bad in listOf(ShellFeature(0, top, 1.0), ShellFeature(3, top, 1.0))) {
                shouldThrow<IllegalArgumentException> {
                    FeatureRebuilder.validateStructure(FeatureSequence(listOf(bad)))
                }.message!! shouldContain "baseIndex"
            }
            FeatureRebuilder.validateStructure(
                FeatureSequence(listOf(BoxFeature(1.0, 1.0, 1.0), ShellFeature(0, top, 0.1))),
            )
        }

        "remapping rewrites the base index and leaves face indices alone" {
            val shell = ShellFeature(2, FaceIndices(listOf(1, 4)), 1.5, ShellDirection.OUTWARD)
            shell.remapIndices { it + 3 } shouldBe
                ShellFeature(5, FaceIndices(listOf(1, 4)), 1.5, ShellDirection.OUTWARD)
            shell.baseIndices shouldBe listOf(2)
        }

        "remove and move keep a shell's base reference consistent" {
            val box = BoxFeature(40.0, 30.0, 20.0)
            val h = FeatureHistory(FeatureSequence(listOf(box, BoxFeature(1.0, 1.0, 1.0), ShellFeature(0, top, 2.0))))
            h.remove(1)
            h.current.features shouldBe listOf(box, ShellFeature(0, top, 2.0))
            h.undo().features.size shouldBe 3
            shouldThrow<FeatureHistoryException> { h.remove(0) }.kind shouldBe
                FeatureHistoryErrorKind.REFERENCED_BY_LATER_FEATURE
            val g = FeatureHistory(FeatureSequence(listOf(BoxFeature(1.0, 1.0, 1.0), box, ShellFeature(1, top, 2.0))))
            g.move(1, 0)
            g.current.features shouldBe listOf(box, BoxFeature(1.0, 1.0, 1.0), ShellFeature(0, top, 2.0))
        }

        "GeneratedEdges naming the shell as generator is refused with the follow-up hint" {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, top, 2.0),
                        SelectedFilletFeature(1, GeneratedEdges(1, listOf(ProfileVertex(0))), 1.0),
                    ),
                )
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.validateStructure(seq) }.message!! shouldContain
                "follow-up"
        }

        "tracking through an INWARD shell is planned, through an OUTWARD shell it is refused with the reason" {
            fun seq(direction: ShellDirection) =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, top, 2.0, direction),
                        SelectedFilletFeature(1, TrackedEdges(0, Edges.parallelTo(Direction3.Z), expected = 4), 1.0),
                    ),
                )
            FeatureRebuilder.validateStructure(seq(ShellDirection.INWARD))
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.validateStructure(seq(ShellDirection.OUTWARD)) }
                .message!! shouldContain "cannot be tracked through"
        }

        "hostile selector trees in any feature are refused before anything hashes or compares them" {
            var faces: FaceSelector = Faces.all()
            repeat(10_000) { faces = faces or Faces.all() }
            var edges: EdgeSelector = Edges.all()
            repeat(10_000) { edges = edges and Edges.all() }
            val hostile =
                listOf(
                    ShellFeature(0, SelectFaces(faces), 1.0),
                    SelectedFilletFeature(0, SelectEdges(edges), 1.0),
                    SelectedFilletFeature(0, TrackedEdges(0, edges), 1.0),
                    SelectedFilletFeature(0, GeneratedEdges(0, listOf(ProfileVertex(0)), within = edges), 1.0),
                    SelectedFilletFeature(
                        0,
                        GeneratedEdges(0, listOf(OperandFaces(0, faces)), role = GeneratedRole.SECTION),
                        1.0,
                    ),
                    SelectedFilletFeature(0, GeneratedEdges(0, listOf(OperandEdges(0, edges))), 1.0),
                )
            for (feature in hostile) {
                shouldThrow<IllegalArgumentException> {
                    FeatureRebuilder.validateStructure(FeatureSequence(listOf(BoxFeature(1.0, 1.0, 1.0), feature)))
                }.message!! shouldContain "selector"
            }
            // The history validates before it stores or compares anything.
            val h = FeatureHistory(FeatureSequence(listOf(BoxFeature(1.0, 1.0, 1.0))))
            shouldThrow<FeatureHistoryException> { h.add(ShellFeature(0, SelectFaces(faces), 1.0)) }.kind shouldBe
                FeatureHistoryErrorKind.INVALID_SEQUENCE
        }
    })
