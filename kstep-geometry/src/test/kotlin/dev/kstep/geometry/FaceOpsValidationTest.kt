package dev.kstep.geometry

import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.DraftFeature
import dev.kstep.geometry.feature.FaceIndices
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.PocketFeature
import dev.kstep.geometry.feature.SelectFaces
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.ThickenFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.feature.remapIndices
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.Point3
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Welle Q2 (ADR-0040): every scalar and structural rule of pocket, draft and thicken is checked in Kotlin BEFORE any
 * native call, so these cases need no OCCT. The shape argument is never reached.
 */
class FaceOpsValidationTest :
    StringSpec({
        // Never used: validation throws before withHandle. 0 is not a registered handle.
        val never = OcctShape(0L)
        val neutral = NeutralPlane(Point3(0.0, 0.0, 0.0), Direction3.Z)
        val rect = PocketProfile.Rectangle(10.0, 10.0)
        val top = SelectFaces(Faces.atMax(Direction3.Z), expected = 1)

        fun pocketMessage(
            profile: PocketProfile = rect,
            depth: PocketDepth = PocketDepth.Blind(5.0),
            face: Int = 0,
        ) = shouldThrow<IllegalArgumentException> { OcctKernel.pocket(never, face, profile, depth) }.message!!

        "pocket depth 0, negative, NaN, infinite and too large are rejected" {
            pocketMessage(depth = PocketDepth.Blind(0.0)) shouldContain "depth"
            pocketMessage(depth = PocketDepth.Blind(-1.0)) shouldContain "-1.0"
            pocketMessage(depth = PocketDepth.Blind(Double.NaN)) shouldContain "NaN"
            pocketMessage(depth = PocketDepth.Blind(Double.POSITIVE_INFINITY)) shouldContain "finite"
            pocketMessage(depth = PocketDepth.Blind(2e7)) shouldContain "within"
        }

        "pocket profiles with a bad size, centre or point list are rejected" {
            pocketMessage(PocketProfile.Rectangle(0.0, 10.0)) shouldContain "width"
            pocketMessage(PocketProfile.Rectangle(10.0, -1.0)) shouldContain "length"
            pocketMessage(PocketProfile.Rectangle(10.0, 10.0, u = Double.NaN)) shouldContain "NaN"
            pocketMessage(PocketProfile.Circle(0.0)) shouldContain "diameter"
            pocketMessage(PocketProfile.Circle(-3.0)) shouldContain "diameter"
            pocketMessage(PocketProfile.Circle(4.0, v = 2e7)) shouldContain "within"
            pocketMessage(PocketProfile.Polygon(listOf(ProfilePoint(0.0, 0.0), ProfilePoint(1.0, 0.0)))) shouldContain
                "between"
            pocketMessage(
                PocketProfile.Polygon(
                    List(OcctKernel.MAX_POCKET_PROFILE_POINTS + 1) { ProfilePoint(it.toDouble(), 0.0) },
                ),
            ) shouldContain "between"
            pocketMessage(
                PocketProfile.Polygon(listOf(ProfilePoint(0.0, 0.0), ProfilePoint(1.0, 1.0), ProfilePoint(2.0, 2.0))),
            ) shouldContain "no area"
            pocketMessage(
                PocketProfile.Polygon(listOf(ProfilePoint(0.0, 0.0), ProfilePoint(0.0, 0.0), ProfilePoint(2.0, 2.0))),
            ) shouldContain "coincident"
            pocketMessage(face = -1) shouldContain "negative"
        }

        "draft angles 0, 45, 90, NaN and -0.0 are rejected" {
            for (angle in listOf(0.0, -0.0, 45.0, -45.0, 90.0, 1e-4, Double.NaN, Double.POSITIVE_INFINITY)) {
                shouldThrow<IllegalArgumentException> { OcctKernel.draft(never, listOf(0), angle, neutral) }
                    .message!! shouldContain "angle"
            }
        }

        "draft face lists and neutral planes are checked" {
            shouldThrow<IllegalArgumentException> { OcctKernel.draft(never, emptyList(), 5.0, neutral) }
                .message!! shouldContain "at least one face"
            shouldThrow<IllegalArgumentException> { OcctKernel.draft(never, listOf(1, 1), 5.0, neutral) }
                .message!! shouldContain "unique"
            shouldThrow<IllegalArgumentException> { OcctKernel.draft(never, listOf(-1), 5.0, neutral) }
                .message!! shouldContain "negative"
            shouldThrow<IllegalArgumentException> {
                OcctKernel.draft(never, List(OcctKernel.MAX_DRAFT_FACES + 1) { it }, 5.0, neutral)
            }.message!! shouldContain "at most"
            shouldThrow<IllegalArgumentException> {
                OcctKernel.draft(never, listOf(0), 5.0, NeutralPlane(Point3(Double.NaN, 0.0, 0.0), Direction3.Z))
            }.message!! shouldContain "origin"
            // a zero pull direction cannot even be constructed
            shouldThrow<IllegalArgumentException> { Direction3(0.0, 0.0, 0.0) }
        }

        "thicken distance and faces are checked" {
            for (d in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 2e7)) {
                shouldThrow<IllegalArgumentException> { OcctKernel.thicken(never, listOf(0), d) }
                    .message!! shouldContain "distance"
            }
            shouldThrow<IllegalArgumentException> { OcctKernel.thicken(never, emptyList(), 1.0) }
                .message!! shouldContain "at least one face"
            shouldThrow<IllegalArgumentException> { OcctKernel.thicken(never, listOf(3, 3), 1.0) }
                .message!! shouldContain "unique"
        }

        "the native Q2 constants are the documented ones" {
            OcctKernel.MAX_POCKET_INPUT_FACES shouldBe 400
            OcctKernel.MAX_POCKET_PROFILE_POINTS shouldBe 1024
            OcctKernel.MAX_DRAFT_INPUT_FACES shouldBe 200
            OcctKernel.MAX_DRAFT_FACES shouldBe 64
            OcctKernel.MAX_DRAFT_ANGLE_DEGREES shouldBe 45.0
            OcctKernel.MAX_THICKEN_INPUT_FACES shouldBe 200
            OcctKernel.MAX_THICKEN_FACES shouldBe 64
        }

        "the native Q2 constants match the bridge source" {
            val cpp = java.io.File("src/main/cpp/kstep_occt_bridge.cpp").readText()

            fun constant(name: String) =
                Regex("""constexpr\s+\w+\s+$name\s*=\s*([\d.eE+-]+)""").find(cpp)!!.groupValues[1].toDouble()
            constant("kMaxPocketInputFaces") shouldBe OcctKernel.MAX_POCKET_INPUT_FACES.toDouble()
            constant("kMaxPocketProfilePoints") shouldBe OcctKernel.MAX_POCKET_PROFILE_POINTS.toDouble()
            constant("kMaxDraftInputFaces") shouldBe OcctKernel.MAX_DRAFT_INPUT_FACES.toDouble()
            constant("kMaxDraftFaces") shouldBe OcctKernel.MAX_DRAFT_FACES.toDouble()
            constant("kMaxThickenInputFaces") shouldBe OcctKernel.MAX_THICKEN_INPUT_FACES.toDouble()
            constant("kMaxThickenFaces") shouldBe OcctKernel.MAX_THICKEN_FACES.toDouble()
            constant("kMinDraftAngleDeg") shouldBe OcctKernel.MIN_DRAFT_ANGLE_DEGREES
            constant("kMaxDraftAngleDeg") shouldBe OcctKernel.MAX_DRAFT_ANGLE_DEGREES
        }

        // The one case with real signal on a machine without the OCCT dev packages: valid arguments reach the
        // availability guard and fail predictably instead of with an UnsatisfiedLinkError.
        val unavailable = OcctKernel.availability() !is OcctAvailability.Available

        "pocket fails predictably when the native bridge is unavailable".config(enabled = unavailable) {
            shouldThrow<OcctUnavailableException> { OcctKernel.pocket(never, 0, rect, PocketDepth.Through) }
        }

        "draft fails predictably when the native bridge is unavailable".config(enabled = unavailable) {
            shouldThrow<OcctUnavailableException> { OcctKernel.draft(never, listOf(0), 5.0, neutral) }
        }

        "thicken fails predictably when the native bridge is unavailable".config(enabled = unavailable) {
            shouldThrow<OcctUnavailableException> { OcctKernel.thicken(never, listOf(0), 1.0) }
        }

        // ---- feature structure ----

        "Q2 features must refer to an earlier feature" {
            val bad =
                listOf(
                    PocketFeature(0, top, rect, PocketDepth.Through),
                    DraftFeature(0, top, 5.0, neutral),
                    ThickenFeature(2, top, 1.0),
                )
            for (feature in bad) {
                shouldThrow<IllegalArgumentException> {
                    FeatureRebuilder.validateStructure(FeatureSequence(listOf(feature)))
                }.message!! shouldContain "baseIndex"
            }
            FeatureRebuilder.validateStructure(
                FeatureSequence(
                    listOf(
                        BoxFeature(1.0, 1.0, 1.0),
                        PocketFeature(0, top, rect, PocketDepth.Through),
                        DraftFeature(1, top, 5.0, neutral),
                        ThickenFeature(2, top, 1.0),
                    ),
                ),
            )
        }

        "remapping rewrites the base index and leaves face indices alone" {
            val pocket = PocketFeature(2, FaceIndices(listOf(4)), rect, PocketDepth.Blind(2.0))
            pocket.remapIndices { it + 3 } shouldBe
                PocketFeature(5, FaceIndices(listOf(4)), rect, PocketDepth.Blind(2.0))
            val draft = DraftFeature(1, FaceIndices(listOf(0, 1)), 5.0, neutral)
            draft.remapIndices { it + 1 } shouldBe DraftFeature(2, FaceIndices(listOf(0, 1)), 5.0, neutral)
            val thicken = ThickenFeature(1, FaceIndices(listOf(2)), 1.0)
            thicken.remapIndices { it + 1 } shouldBe ThickenFeature(2, FaceIndices(listOf(2)), 1.0)
            pocket.baseIndices shouldBe listOf(2)
        }

        "undo and redo carry pocket and draft features through the history without a rebuild" {
            val box = BoxFeature(40.0, 30.0, 20.0)
            val pocket = PocketFeature(0, top, rect, PocketDepth.Blind(5.0))
            val draft = DraftFeature(1, top, 5.0, neutral)
            val h = FeatureHistory(FeatureSequence(listOf(box)))
            h.add(pocket)
            h.add(draft)
            h.current.features shouldBe listOf(box, pocket, draft)
            h.undo().features shouldBe listOf(box, pocket)
            h.undo().features shouldBe listOf(box)
            h.redo().features shouldBe listOf(box, pocket)
            h.redo().features shouldBe listOf(box, pocket, draft)
            h.update(1) { (it as PocketFeature).copy(depth = PocketDepth.Through) }
            (h.current.features[1] as PocketFeature).depth shouldBe PocketDepth.Through
        }

        "tracking through a pocket is planned, through a draft or thicken it is refused with the reason" {
            fun seq(middle: dev.kstep.geometry.feature.DerivedFeature) =
                FeatureSequence(
                    listOf(
                        BoxFeature(40.0, 30.0, 20.0),
                        middle,
                        SelectedFilletFeature(1, TrackedEdges(0, Edges.parallelTo(Direction3.Z), expected = 4), 1.0),
                    ),
                )
            FeatureRebuilder.validateStructure(seq(PocketFeature(0, top, rect, PocketDepth.Blind(5.0))))
            shouldThrow<IllegalArgumentException> {
                FeatureRebuilder.validateStructure(seq(DraftFeature(0, top, 5.0, neutral)))
            }.message!! shouldContain "ADR-0040"
            shouldThrow<IllegalArgumentException> {
                FeatureRebuilder.validateStructure(seq(ThickenFeature(0, top, 1.0)))
            }.message!! shouldContain "ADR-0040"
        }

        "GeneratedEdges naming a pocket, draft or thicken as generator is refused with the follow-up hint" {
            for (
            middle in
            listOf(
                PocketFeature(0, top, rect, PocketDepth.Blind(5.0)),
                DraftFeature(0, top, 5.0, neutral),
                ThickenFeature(0, top, 1.0),
            )
            ) {
                val seq =
                    FeatureSequence(
                        listOf(
                            BoxFeature(40.0, 30.0, 20.0),
                            middle,
                            SelectedFilletFeature(1, GeneratedEdges(1, listOf(ProfileVertex(0))), 1.0),
                        ),
                    )
                shouldThrow<IllegalArgumentException> {
                    FeatureRebuilder.validateStructure(
                        seq,
                    )
                }.message!! shouldContain
                    "follow-up"
            }
        }
    })
