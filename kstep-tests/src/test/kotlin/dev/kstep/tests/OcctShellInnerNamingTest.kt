package dev.kstep.tests

import dev.kstep.geometry.GeneratedRole
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ShellDirection
import dev.kstep.geometry.ShellInnerEdges
import dev.kstep.geometry.ShellInnerFaces
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.ShellFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeKind
import dev.kstep.geometry.select.EdgeNamingException
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.and
import dev.kstep.geometry.select.or
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.PI

private const val SIN_LEAK_SLACK = 10
private const val SIN_TOL = 1e-3

/**
 * Welle T (ADR-0041): the inner wall of an INWARD shell by name. Box 40 x 30 x 20, top open, wall 2: the cavity is
 * 36 x 26 x 18 (V = 7152). Rounding its four vertical edges (L = 18) with r = 1 adds 4 (1 - pi/4) 18 = 15.451332, so
 * V = 7167.451; at height 30 (L = 28) V = 9792 + 4 (1 - pi/4) 28 = 9816.035. The class name carries `Occt`: the CI skip gate covers it.
 */
class OcctShellInnerNamingTest :
    StringSpec({
        val occt = OcctKernel.availability() is OcctAvailability.Available
        val z = Direction3.Z
        val openTop = SelectFaces(Faces.atMax(z), expected = 1)
        val vertical = ShellInnerEdges(Edges.parallelTo(z))
        val roundInner =
            SelectedFilletFeature(1, GeneratedEdges(1, listOf(vertical), GeneratedRole.CAVITY, expected = 4), 1.0)
        val innerLoss = { l: Double -> 4.0 * (1.0 - PI / 4.0) * l }

        "T1: the four vertical input edges name the four inner edges (L = 18) at (2,2), (38,2), (2,28), (38,28)"
            .config(enabled = occt) {
                OcctKernel.makeBox(40.0, 30.0, 20.0).use { b ->
                    val top = OcctKernel.selectFaces(b, Faces.atMax(z), expected = 1)
                    OcctKernel.shellNamed(b, top, 2.0, ShellDirection.INWARD, listOf(vertical)).use { named ->
                        named.shape.volume shouldBe (7152.0 plusOrMinus 1e-6)
                        val idx = OcctKernel.generatedEdges(named, listOf(vertical), GeneratedRole.CAVITY, expected = 4)
                        val edges = idx.map { named.shape.edges[it] }
                        edges.forEach { it.length shouldBe (18.0 plusOrMinus SIN_TOL) }
                        val corners = edges.map { Pair(it.start.x.toInt(), it.start.y.toInt()) }.toSet()
                        corners shouldBe setOf(Pair(2, 2), Pair(38, 2), Pair(2, 28), Pair(38, 28))
                        edges.forEach {
                            minOf(it.start.z, it.end.z) shouldBe (2.0 plusOrMinus SIN_TOL)
                            maxOf(it.start.z, it.end.z) shouldBe (20.0 plusOrMinus SIN_TOL)
                        }
                    }
                }
            }

        "T2: rounding the named inner edges gives V = 7167.451 and 15 faces".config(enabled = occt) {
            val seq = FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), ShellFeature(0, openTop, 2.0), roundInner))
            FeatureRebuilder.rebuild(seq).use { r ->
                r.final.volume shouldBe (7152.0 + innerLoss(18.0) plusOrMinus SIN_TOL)
                r.final.volume shouldBe (7167.4513 plusOrMinus SIN_TOL)
                r.final.topology.faces shouldBe 15
            }
        }

        "T3 and T4: the name follows the geometry (height 30, L = 28), undo and redo".config(enabled = occt) {
            val h =
                FeatureHistory(
                    FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), ShellFeature(0, openTop, 2.0), roundInner)),
                )
            h.update(0) { (it as BoxFeature).copy(dz = 30.0) }
            h.rebuild().use { r ->
                r.final.volume shouldBe (9816.0354 plusOrMinus SIN_TOL)
                r.final.topology.faces shouldBe 15
            }
            h.undo()
            h.rebuild().use { it.final.volume shouldBe (7167.4513 plusOrMinus SIN_TOL) }
            h.redo()
            h.rebuild().use { it.final.volume shouldBe (9816.0354 plusOrMinus SIN_TOL) }
        }

        "T5a: an input edge between two opened faces has no inner image and fails with the candidates".config(
            enabled = occt,
        ) {
            val twoOpen = SelectFaces(Faces.atMax(z) or Faces.atMin(Direction3.Y), expected = 2)
            val source = ShellInnerEdges(Edges.atMax(z) and Edges.atMin(Direction3.Y))
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, twoOpen, 2.0),
                        SelectedFilletFeature(
                            1,
                            GeneratedEdges(1, listOf(source), GeneratedRole.CAVITY, expected = 1),
                            1.0,
                        ),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(seq) }
            e.featureIndex shouldBe 2
            val cause = e.cause as EdgeNamingException
            cause.message!! shouldContain "no inner image"
            cause.candidates.shouldNotBeEmpty()
        }

        "T5b: a selector that hits all twelve edges is ambiguous for expected = 4".config(enabled = occt) {
            val all = ShellInnerEdges(Edges.all())
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, openTop, 2.0),
                        SelectedFilletFeature(
                            1,
                            GeneratedEdges(1, listOf(all), GeneratedRole.CAVITY, expected = 4),
                            1.0,
                        ),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(seq) }
            (e.cause is EdgeNamingException) shouldBe true
        }

        "T5c: a wrong expected count fails".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, openTop, 2.0),
                        SelectedFilletFeature(
                            1,
                            GeneratedEdges(1, listOf(vertical), GeneratedRole.CAVITY, expected = 3),
                            1.0,
                        ),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(seq) }
            (e.cause is EdgeNamingException) shouldBe true
        }

        "T5d: the opened face has no inner copy and fails".config(enabled = occt) {
            OcctKernel.makeBox(40.0, 30.0, 20.0).use { b ->
                val top = OcctKernel.selectFaces(b, Faces.atMax(z), expected = 1)
                val source = ShellInnerFaces(Faces.atMax(z))
                OcctKernel.shellNamed(b, top, 2.0, ShellDirection.INWARD, listOf(source)).use { named ->
                    val e =
                        shouldThrow<EdgeNamingException> {
                            OcctKernel.generatedEdges(named, listOf(source), GeneratedRole.CAVITY, expected = null)
                        }
                    e.message!! shouldContain "opened"
                }
            }
        }

        "T5e: a selector without a hit on the input fails before the native call".config(enabled = occt) {
            OcctKernel.makeBox(40.0, 30.0, 20.0).use { b ->
                val top = OcctKernel.selectFaces(b, Faces.atMax(z), expected = 1)
                val nothing = ShellInnerEdges(Edges.parallelTo(Direction3(0.3, 1.0, 0.7)))
                shouldThrow<EdgeNamingException> {
                    OcctKernel.shellNamed(b, top, 2.0, ShellDirection.INWARD, listOf(nothing))
                }.message!! shouldContain "matched no edge"
            }
        }

        "T6: ShellInnerFaces names the four edges of the inner floor at z = 2".config(enabled = occt) {
            OcctKernel.makeBox(40.0, 30.0, 20.0).use { b ->
                val top = OcctKernel.selectFaces(b, Faces.atMax(z), expected = 1)
                val floor = ShellInnerFaces(Faces.atMin(z))
                OcctKernel.shellNamed(b, top, 2.0, ShellDirection.INWARD, listOf(floor)).use { named ->
                    val idx = OcctKernel.generatedEdges(named, listOf(floor), GeneratedRole.CAVITY, expected = 4)
                    idx.map { named.shape.edges[it] }.forEach {
                        it.start.z shouldBe (2.0 plusOrMinus SIN_TOL)
                        it.end.z shouldBe (2.0 plusOrMinus SIN_TOL)
                    }
                }
            }
        }

        "T7: one shell is generator and tracked-edge path at once".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, openTop, 2.0),
                        SelectedFilletFeature(
                            1,
                            GeneratedEdges(1, listOf(vertical), GeneratedRole.CAVITY, expected = 4),
                            1.0,
                        ),
                        SelectedFilletFeature(2, TrackedEdges(0, Edges.parallelTo(z), expected = 4), 1.0),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                // 7152 + inner rounds (L = 18) - outer rounds (L = 20), both with r = 1
                r.final.volume shouldBe (7152.0 + innerLoss(18.0) - innerLoss(20.0) plusOrMinus SIN_TOL)
            }
        }

        "T8: the name follows the history through a later translate".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, openTop, 2.0),
                        TranslateFeature(1, 5.0, 0.0, 0.0),
                        SelectedFilletFeature(
                            2,
                            GeneratedEdges(1, listOf(vertical), GeneratedRole.CAVITY, expected = 4),
                            1.0,
                        ),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                r.final.volume shouldBe (7152.0 + innerLoss(18.0) plusOrMinus SIN_TOL)
            }
        }

        "T9: failed named shells leave no native shape behind".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        ShellFeature(0, openTop, 2.0),
                        SelectedFilletFeature(
                            1,
                            GeneratedEdges(1, listOf(vertical), GeneratedRole.CAVITY, expected = 3),
                            1.0,
                        ),
                    ),
                )
            System.gc()
            val before = OcctBridge.nativeLiveShapeCount()
            repeat(10) { shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(seq) } }
            System.gc()
            OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + SIN_LEAK_SLACK
        }

        "T10: a cylinder with an open lid names its rims, the seam has no usable image".config(enabled = occt) {
            OcctKernel.makeCylinder(10.0, 20.0).use { c ->
                val top = OcctKernel.selectFaces(c, Faces.atMax(z), expected = 1)
                val rims = ShellInnerEdges(Edges.ofType(EdgeKind.CIRCLE))
                OcctKernel.shellNamed(c, top, 1.0, ShellDirection.INWARD, listOf(rims)).use { named ->
                    val idx = OcctKernel.generatedEdges(named, listOf(rims), GeneratedRole.CAVITY, expected = 2)
                    idx.map { named.shape.edges[it].radius!! }.forEach { it shouldBe (9.0 plusOrMinus SIN_TOL) }
                }
                val seam = ShellInnerEdges(Edges.seams())
                OcctKernel.shellNamed(c, top, 1.0, ShellDirection.INWARD, listOf(seam)).use { named ->
                    shouldThrow<EdgeNamingException> {
                        OcctKernel.generatedEdges(named, listOf(seam), GeneratedRole.CAVITY, expected = null)
                    }.message!! shouldContain "no inner image"
                }
            }
        }

        "T11: shellNamed returns the same topology order as shell".config(enabled = occt) {
            OcctKernel.makeBox(40.0, 30.0, 20.0).use { b ->
                val top = OcctKernel.selectFaces(b, Faces.atMax(z), expected = 1)
                OcctKernel.shell(b, top, 2.0).use { plain ->
                    OcctKernel.shellNamed(b, top, 2.0, ShellDirection.INWARD, listOf(vertical)).use { named ->
                        named.shape.edges.map { Triple(it.start, it.end, it.kind) } shouldBe
                            plain.edges.map { Triple(it.start, it.end, it.kind) }
                        named.shape.faces.map { it.kind to it.area } shouldBe plain.faces.map { it.kind to it.area }
                    }
                }
            }
        }

        "an OUTWARD shell cannot name its inner wall".config(enabled = occt) {
            OcctKernel.makeBox(40.0, 30.0, 20.0).use { b ->
                val top = OcctKernel.selectFaces(b, Faces.atMax(z), expected = 1)
                shouldThrow<IllegalArgumentException> {
                    OcctKernel.shellNamed(b, top, 2.0, ShellDirection.OUTWARD, listOf(vertical))
                }.message!! shouldContain "OUTWARD"
            }
        }
    })
