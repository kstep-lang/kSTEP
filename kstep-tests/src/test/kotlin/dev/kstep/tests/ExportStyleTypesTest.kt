package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.style.EdgeStyle
import dev.kstep.geometry.style.FaceStyle
import dev.kstep.geometry.style.LayerNames
import dev.kstep.geometry.style.ShapeStyle
import dev.kstep.geometry.style.StepColour
import dev.kstep.geometry.style.StyleErrorKind
import dev.kstep.geometry.style.StyleException
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Colour and layer types and their script error codes (ADR-0039). Pure Kotlin: runs without OCCT. */
class ExportStyleTypesTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "colour components must be finite numbers in 0..1" {
            StepColour.rgb(0.0, 0.5, 1.0).g shouldBe 0.5
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
                shouldThrow<StyleException> { StepColour.rgb(bad, 0.0, 0.0) }.kind shouldBe
                    StyleErrorKind.INVALID_COLOUR
                shouldThrow<StyleException> { StepColour.rgb(0.0, bad, 0.0) }
                shouldThrow<StyleException> { StepColour.rgb(0.0, 0.0, bad) }
            }
        }

        "hex colours are parsed as sRGB and malformed ones are rejected" {
            val c = StepColour.hex("#FF8000")
            c.r shouldBe 1.0
            c.g shouldBe (128.0 / 255.0 plusOrMinus 1e-12)
            c.b shouldBe 0.0
            StepColour.hex("#ff8000") shouldBe c
            for (bad in listOf("FF8000", "#FF80", "#GG0000", "#FF80000", "#FF800080FF", "", " #FF8000")) {
                shouldThrow<StyleException> { StepColour.hex(bad) }.kind shouldBe StyleErrorKind.INVALID_COLOUR
            }
        }

        "a hex colour with an alpha channel carries the opacity (ADR-0042, replaces the old rejection)" {
            val c = StepColour.hex("#FF800080")
            c.r shouldBe 1.0
            c.a shouldBe (128.0 / 255.0 plusOrMinus 1e-12)
            c.isOpaque shouldBe false
            StepColour.hex("#FF8000FF") shouldBe StepColour.hex("#FF8000")
            StepColour.hex("#FF800000").a shouldBe 0.0
        }

        "alpha must be a finite number in 0..1 and defaults to opaque" {
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.5)) {
                val e = shouldThrow<StyleException> { StepColour.rgba(1.0, 0.5, 0.0, bad) }
                e.kind shouldBe StyleErrorKind.INVALID_COLOUR
                e.message!!.contains("alpha") shouldBe true
            }
            StepColour(1.0, 0.5, 0.0) shouldBe StepColour.rgba(1.0, 0.5, 0.0, 1.0)
            StepColour.rgba(1.0, 0.5, 0.0, 0.5).isOpaque shouldBe false
            StepColour.rgba(1.0, 0.5, 0.0, 0.5).toArray().size shouldBe 3
            StepColour.rgba(1.0, 0.5, 0.0, 0.5).toRgbaArray().toList() shouldBe listOf(1.0, 0.5, 0.0, 0.5)
        }

        "edge styles: opaque colours only, non-negative sorted unique indices, a limit, isEmpty counts them" {
            val red = StepColour.rgb(1.0, 0.0, 0.0)
            shouldThrow<StyleException> { EdgeStyle(-1, red) }.kind shouldBe StyleErrorKind.TARGET
            val transparent = shouldThrow<StyleException> { EdgeStyle(0, StepColour.rgba(1.0, 0.0, 0.0, 0.5)) }
            transparent.kind shouldBe StyleErrorKind.INVALID_COLOUR
            ShapeStyle(edges = listOf(EdgeStyle(1, red), EdgeStyle(4, red))).isEmpty shouldBe false
            ShapeStyle().isEmpty shouldBe true
            shouldThrow<StyleException> {
                ShapeStyle(
                    edges = listOf(EdgeStyle(4, red), EdgeStyle(1, red)),
                )
            }.kind shouldBe
                StyleErrorKind.CONFLICT
            shouldThrow<StyleException> {
                ShapeStyle(
                    edges = listOf(EdgeStyle(1, red), EdgeStyle(1, red)),
                )
            }.kind shouldBe
                StyleErrorKind.CONFLICT
            shouldThrow<StyleException> {
                ShapeStyle(edges = List(ShapeStyle.MAX_STYLED_EDGES + 1) { EdgeStyle(it, red) })
            }.kind shouldBe StyleErrorKind.TARGET
        }

        "layer names: 1 to 128 printable ASCII characters, not blank, no backslash" {
            LayerNames.require("x".repeat(128)) shouldBe "x".repeat(128)
            LayerNames.require("O'Neil \"quoted\" \$ % & (1) #2") shouldBe "O'Neil \"quoted\" \$ % & (1) #2"
            val bad =
                listOf(
                    "",
                    "   ",
                    "x".repeat(129),
                    "a\u0000b",
                    "a\u0007b",
                    "a\u007Fb",
                    "a\u0085b",
                    "a\u2028b",
                    "a\u2029b",
                    "a\nb",
                    "a\tb",
                    "back\\slash",
                    "Fr\u00e4se",
                    "emoji \uD83D\uDE00",
                )
            for (name in bad) {
                shouldThrow<StyleException> { LayerNames.require(name) }.kind shouldBe StyleErrorKind.INVALID_LAYER
            }
        }

        "a shape style is empty without any colour or layer" {
            ShapeStyle.NONE.isEmpty shouldBe true
            ShapeStyle(faces = listOf(FaceStyle(0))).isEmpty shouldBe true
            ShapeStyle(layer = "L").isEmpty shouldBe false
            ShapeStyle(faces = listOf(FaceStyle(3, StepColour.rgb(1.0, 0.0, 0.0)))).isEmpty shouldBe false
        }

        "face styles must be sorted and unique, and are capped" {
            shouldThrow<StyleException> { ShapeStyle(faces = listOf(FaceStyle(2), FaceStyle(1))) }
                .kind shouldBe StyleErrorKind.CONFLICT
            shouldThrow<StyleException> { ShapeStyle(faces = listOf(FaceStyle(1), FaceStyle(1))) }
            shouldThrow<StyleException> { FaceStyle(-1) }.kind shouldBe StyleErrorKind.TARGET
            ShapeStyle(faces = List(ShapeStyle.MAX_STYLED_FACES) { FaceStyle(it) }).faces.size shouldBe
                ShapeStyle.MAX_STYLED_FACES
            shouldThrow<StyleException> {
                ShapeStyle(faces = List(ShapeStyle.MAX_STYLED_FACES + 1) { FaceStyle(it) })
            }.kind shouldBe StyleErrorKind.TARGET
        }

        "distinct layer names are capped at 256" {
            ShapeStyle(faces = List(256) { FaceStyle(it, layer = "L$it") }).faces.size shouldBe 256
            shouldThrow<StyleException> {
                ShapeStyle(faces = List(257) { FaceStyle(it, layer = "L$it") })
            }.kind shouldBe StyleErrorKind.INVALID_LAYER
        }

        "a script with an invalid colour fails with KSTEP-S-012 and no stack trace" {
            val outcome = KStepScriptHost.eval("StepColour.rgb(1.5, 0.0, 0.0)\n")
            val error = outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
            error.code shouldBe "KSTEP-S-012"
            error.message.contains("\tat ") shouldBe false
        }

        "a script with an invalid layer name fails with KSTEP-S-013" {
            val outcome = KStepScriptHost.eval("LayerNames.require(\"\")\n")
            outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>().code shouldBe "KSTEP-S-013"
        }

        "a script with an invalid hex colour fails with KSTEP-S-012" {
            val outcome = KStepScriptHost.eval("StepColour.hex(\"red\")\n")
            outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>().code shouldBe "KSTEP-S-012"
        }

        "a script with an invalid alpha fails with KSTEP-S-012 and a message naming alpha" {
            for (bad in listOf("Double.NaN", "-0.1", "1.5")) {
                val outcome = KStepScriptHost.eval("StepColour.rgba(1.0, 0.0, 0.0, $bad)\n")
                val error = outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
                error.code shouldBe "KSTEP-S-012"
                error.message.contains("alpha") shouldBe true
            }
        }

        "styleEdges refuses a transparent colour with KSTEP-S-012 and a missing shape with KSTEP-S-015" {
            val prelude =
                """
                val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
                val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
                val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
                val p = product("P") { name = "P"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                val f = productDefinitionFormation("P-F") { ofProduct = p }.getOrThrow()
                val pd = productDefinition("P-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
                """.trimIndent()
            val transparent =
                KStepScriptHost.eval(
                    "$prelude\nstepFile(fileName = \"s.step\") { styleEdges(pd, Edges.atMax(Direction3.Z), " +
                        "StepColour.rgba(1.0, 0.0, 0.0, 0.5)) }\n",
                )
            val transparentError = transparent.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
            transparentError.code shouldBe "KSTEP-S-012"
            transparentError.message.contains("transparent") shouldBe true
            val noShape =
                KStepScriptHost.eval(
                    "$prelude\nstepFile(fileName = \"s.step\") { styleEdges(pd, Edges.atMax(Direction3.Z), " +
                        "StepColours.RED) }\n",
                )
            noShape.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>().code shouldBe "KSTEP-S-015"
        }

        "styled export fails predictably when the native bridge is unavailable".config(enabled = !available) {
            shouldThrow<OcctUnavailableException> { OcctKernel.makeBox(1.0, 1.0, 1.0) }
        }
    })
