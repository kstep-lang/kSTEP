package dev.kstep.tests

import dev.kstep.geometry.EdgeFate
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.PocketDepth
import dev.kstep.geometry.PocketProfile
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.PocketFeature
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.FaceKind
import dev.kstep.geometry.select.FaceSelectionException
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.minus
import dev.kstep.geometry.select.or
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.PI

private const val POCKET_LEAK_SLACK = 10

/**
 * Welle Q2 (ADR-0040): pocket / hole from a face against the real OCCT bridge. Box 40 x 30 x 20 (V = 24000), top face:
 * a 10 x 10 x 5 pocket leaves 23500 (11 faces); a d = 8 blind hole 6 deep leaves 24000 - 96 pi (8 faces); a d = 8
 * through hole leaves 24000 - 320 pi (7 faces).
 */
class OcctPocketFeatureTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val z = Direction3.Z
        val topFace = Faces.atMax(z)
        val rect = PocketProfile.Rectangle(10.0, 10.0)

        fun box() = OcctKernel.makeBox(40.0, 30.0, 20.0)

        "a rectangular pocket: 23500, 11 faces, one solid, unchanged bounding box".config(enabled = available) {
            box().use { b ->
                OcctKernel.pocket(b, topFace, rect, PocketDepth.Blind(5.0)).use { s ->
                    s.volume shouldBe (23500.0 plusOrMinus 1e-6)
                    s.topology.solids shouldBe 1
                    s.topology.faces shouldBe 11
                    val bb = s.boundingBox!!
                    bb.minZ shouldBe (0.0 plusOrMinus 1e-7)
                    bb.maxX shouldBe (40.0 plusOrMinus 1e-7)
                    bb.maxZ shouldBe (20.0 plusOrMinus 1e-7)
                }
                b.volume shouldBe (24000.0 plusOrMinus 1e-9) // the argument is neither modified nor consumed
            }
        }

        "a blind hole and a through hole have the hand-computed volume and face count".config(enabled = available) {
            box().use { b ->
                OcctKernel.pocket(b, topFace, PocketProfile.Circle(8.0), PocketDepth.Blind(6.0)).use { s ->
                    s.volume shouldBe (24000.0 - 96.0 * PI plusOrMinus 1e-6)
                    s.topology.faces shouldBe 8
                }
                OcctKernel.pocket(b, topFace, PocketProfile.Circle(8.0), PocketDepth.Through).use { s ->
                    s.volume shouldBe (24000.0 - 320.0 * PI plusOrMinus 1e-6)
                    s.topology.faces shouldBe 7
                    s.topology.solids shouldBe 1
                }
            }
        }

        "a polygon pocket (triangle) removes its prism".config(enabled = available) {
            box().use { b ->
                val tri =
                    PocketProfile.Polygon(
                        listOf(ProfilePoint(-4.0, -3.0), ProfilePoint(4.0, -3.0), ProfilePoint(0.0, 3.0)),
                    )
                // triangle area 24, depth 4
                OcctKernel.pocket(b, topFace, tri, PocketDepth.Blind(4.0)).use { s ->
                    s.volume shouldBe (24000.0 - 96.0 plusOrMinus 1e-6)
                    s.topology.faces shouldBe 10
                }
            }
        }

        "the profile frame on a +X face is U = +Y, V = +Z around the face centroid".config(enabled = available) {
            box().use { b ->
                // centre of the +X face is (40, 15, 10); the hole is centred at u = 5 (y = 20), v = 3 (z = 13)
                OcctKernel
                    .pocket(
                        b,
                        Faces.atMax(Direction3.X),
                        PocketProfile.Circle(4.0, u = 5.0, v = 3.0),
                        PocketDepth.Blind(10.0),
                    ).use { s ->
                        val wall = s.faces.single { it.kind == FaceKind.CYLINDER }
                        wall.centroid.x shouldBe (35.0 plusOrMinus 1e-6)
                        wall.centroid.y shouldBe (20.0 plusOrMinus 1e-6)
                        wall.centroid.z shouldBe (13.0 plusOrMinus 1e-6)
                    }
                // and on the top face: u = +X, v = +Y around (20, 15)
                OcctKernel
                    .pocket(b, topFace, PocketProfile.Circle(4.0, u = 5.0, v = -3.0), PocketDepth.Blind(10.0))
                    .use { s ->
                        val wall = s.faces.single { it.kind == FaceKind.CYLINDER }
                        wall.centroid.x shouldBe (25.0 plusOrMinus 1e-6)
                        wall.centroid.y shouldBe (12.0 plusOrMinus 1e-6)
                        wall.centroid.z shouldBe (15.0 plusOrMinus 1e-6)
                    }
            }
        }

        "the selector follows the geometry: rebuild with a taller box, undo and redo".config(enabled = available) {
            val pocket = PocketFeature(0, SelectFaces(topFace, expected = 1), rect, PocketDepth.Blind(5.0))
            val h = FeatureHistory(FeatureSequence(listOf(BoxFeature(40.0, 30.0, 20.0), pocket)))
            h.rebuild().use { it.final.volume shouldBe (23500.0 plusOrMinus 1e-6) }
            h.update(0) { (it as BoxFeature).copy(dz = 30.0) }
            h.rebuild().use { it.final.volume shouldBe (35500.0 plusOrMinus 1e-6) }
            h.undo()
            h.rebuild().use { it.final.volume shouldBe (23500.0 plusOrMinus 1e-6) }
            h.redo()
            h.rebuild().use { it.final.volume shouldBe (35500.0 plusOrMinus 1e-6) }
        }

        "a selector with expected = null still demands exactly one face in a pocket feature".config(
            enabled = available,
        ) {
            val sides = Faces.normal(Direction3.X) or Faces.normal(Direction3.NEG_X)
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        PocketFeature(0, SelectFaces(sides), PocketProfile.Circle(4.0), PocketDepth.Blind(3.0)),
                    ),
                )
            shouldThrow<Exception> { FeatureRebuilder.rebuild(seq).close() }
        }

        "a vertical edge tracked through a pocket can be filleted afterwards".config(enabled = available) {
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        PocketFeature(0, SelectFaces(topFace, expected = 1), rect, PocketDepth.Blind(5.0)),
                        SelectedFilletFeature(1, TrackedEdges(0, Edges.parallelTo(z), expected = 4), 1.0),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use { r ->
                // 4 outer vertical edges, r = 1, L = 20
                r.final.volume shouldBe (23500.0 - 4.0 * (1.0 - PI / 4.0) * 20.0 plusOrMinus 1e-6)
            }
            box().use { b ->
                val top = OcctKernel.selectFaces(b, topFace, expected = 1).single()
                OcctKernel.pocketWithHistory(b, top, rect, PocketDepth.Blind(5.0)).use { t ->
                    val fates = t.history.operands.single()
                    fates.size shouldBe 12
                    fates.forEach { (it is EdgeFate.Kept) shouldBe true }
                }
            }
        }

        "a blind depth that is not less than the body, or a profile that leaves or touches the face, is refused"
            .config(enabled = available) {
                box().use { b ->
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.pocket(b, topFace, rect, PocketDepth.Blind(20.0))
                    }.message!! shouldContain "through"
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.pocket(b, topFace, rect, PocketDepth.Blind(25.0))
                    }.message!! shouldContain "through"
                    // crossing the rim
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.pocket(b, topFace, PocketProfile.Rectangle(50.0, 10.0), PocketDepth.Blind(5.0))
                    }.message!! shouldContain "inside"
                    // touching the rim
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.pocket(b, topFace, PocketProfile.Rectangle(40.0, 10.0), PocketDepth.Blind(5.0))
                    }.message!! shouldContain "inside"
                    // wholly outside
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.pocket(b, topFace, PocketProfile.Circle(4.0, u = 100.0), PocketDepth.Blind(5.0))
                    }.message!! shouldContain "inside"
                    // a self-intersecting bow tie
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.pocket(
                            b,
                            topFace,
                            PocketProfile.Polygon(
                                listOf(
                                    ProfilePoint(-3.0, -3.0),
                                    ProfilePoint(3.0, 3.0),
                                    ProfilePoint(3.0, -1.0),
                                    ProfilePoint(-3.0, 2.0),
                                ),
                            ),
                            PocketDepth.Blind(2.0),
                        )
                    }.message!! shouldContain "itself"
                }
            }

        "a cylinder wall is refused as a pocket face; a tie and an empty selection report the candidates"
            .config(enabled = available) {
                OcctKernel.makeCylinder(10.0, 20.0).use { c ->
                    val wall = c.faces.first { it.kind == FaceKind.CYLINDER }.index
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.pocket(c, wall, PocketProfile.Circle(2.0), PocketDepth.Blind(1.0))
                    }.message!! shouldContain "planar"
                }
                box().use { b ->
                    val tie = Faces.normal(Direction3.X) or Faces.normal(Direction3.NEG_X)
                    shouldThrow<FaceSelectionException> {
                        OcctKernel.pocket(b, tie, PocketProfile.Circle(4.0), PocketDepth.Blind(3.0))
                    }.message!! shouldContain "#"
                    shouldThrow<FaceSelectionException> {
                        OcctKernel.pocket(b, topFace minus topFace, PocketProfile.Circle(4.0), PocketDepth.Blind(3.0))
                    }
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.pocket(b, 99, PocketProfile.Circle(4.0), PocketDepth.Blind(3.0))
                    }.message!! shouldContain "out of range"
                }
            }

        "a through hole in a thin plate leaves one solid".config(enabled = available) {
            // NOTE: a profile strictly inside the face always leaves a rim on that face. The "splits the body" branch is
            // still reachable when material further down is cut apart (see the three-block test below); only the
            // "internal void" branch is plausibly defensive, because a cut tool open to the outside cannot enclose a
            // cavity. This test pins that the thin-plate case stays a single solid.
            OcctKernel.makeBox(40.0, 30.0, 2.0).use { plate ->
                OcctKernel.pocket(plate, Faces.atMax(z), PocketProfile.Circle(10.0), PocketDepth.Through).use { s ->
                    s.topology.solids shouldBe 1
                }
            }
        }

        "a through pocket that cuts a column apart splits the body and is refused".config(enabled = available) {
            OcctKernel.makeBox(40.0, 30.0, 10.0).use { bottom ->
                OcctKernel.makeBox(4.0, 4.0, 8.0).use { column ->
                    OcctKernel.makeBox(40.0, 30.0, 2.0).use { plate ->
                        OcctKernel.translate(column, 18.0, 13.0, 10.0).use { col ->
                            OcctKernel.translate(plate, 0.0, 0.0, 18.0).use { top ->
                                OcctKernel.union(bottom, col).use { bc ->
                                    OcctKernel.union(bc, top).use { body ->
                                        val ex =
                                            shouldThrow<Exception> {
                                                OcctKernel.pocket(
                                                    body,
                                                    Faces.atMax(z),
                                                    PocketProfile.Circle(10.0),
                                                    PocketDepth.Through,
                                                )
                                            }
                                        (ex.cause ?: ex).message!! shouldContain "splits the body"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "a blind pocket that would break through a stepped body is refused".config(enabled = available) {
            OcctKernel.makeBox(40.0, 30.0, 5.0).use { plate ->
                OcctKernel.makeBox(10.0, 30.0, 20.0).use { web ->
                    OcctKernel.union(plate, web).use { l ->
                        // the 5 mm step face; body extent along Z is 20, so 8 < 20 passes the extent guard
                        val stepFace =
                            l.faces.first { f ->
                                f.kind == FaceKind.PLANE && f.normal == Direction3.Z && f.centroid.z in 4.9..5.1
                            }
                        val step = stepFace.index
                        shouldThrow<IllegalArgumentException> {
                            OcctKernel.pocket(l, step, PocketProfile.Rectangle(5.0, 5.0), PocketDepth.Blind(8.0))
                        }.message!! shouldContain "through"
                        // a blind depth within the 5 mm material still works
                        OcctKernel.pocket(l, step, PocketProfile.Rectangle(5.0, 5.0), PocketDepth.Blind(3.0)).use { s ->
                            s.topology.solids shouldBe 1
                        }
                    }
                }
            }
        }

        "an unknown handle is reported, not crashed on".config(enabled = available) {
            shouldThrow<IllegalStateException> {
                OcctBridge.nativePocketShape(Long.MAX_VALUE, 0, DoubleArray(0), 4.0, 0.0, 0.0, 3.0, 0)
            }
        }

        "fifty pockets leak no handles beyond the ones kept".config(enabled = available) {
            box().use { b ->
                val before = OcctBridge.nativeLiveShapeCount()
                repeat(50) {
                    OcctKernel.pocket(b, topFace, rect, PocketDepth.Blind(5.0)).close()
                    runCatching {
                        OcctKernel.pocket(
                            b,
                            topFace,
                            PocketProfile.Rectangle(50.0, 10.0),
                            PocketDepth.Blind(5.0),
                        )
                    }
                }
                (OcctBridge.nativeLiveShapeCount() - before) shouldBeLessThanOrEqual POCKET_LEAK_SLACK
            }
        }
    })
