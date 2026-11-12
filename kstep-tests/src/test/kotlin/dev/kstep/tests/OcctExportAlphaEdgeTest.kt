package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctStepDocument
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.XcafNodeKind
import dev.kstep.geometry.XcafStyleTarget
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.shape.Ap242ShapeExporter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

private val PRELUDE =
    """
    val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    fun part(id: String): ProductDefinition {
        val p = product(id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow()
        val f = productDefinitionFormation("${'$'}id-F") { ofProduct = p }.getOrThrow()
        return productDefinition("${'$'}id-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
    }
    """.trimIndent()

private const val FIXED_STAMP = "timestamp = \"2026-01-01T00:00:00\""

/**
 * Transparency and edge colours in the STEP export (ADR-0042), in-process: script -> export -> read back with OCCT's
 * own XCAF reader. The class name carries `Occt` so the CI guard that forbids silent skips covers it.
 */
class OcctExportAlphaEdgeTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun run(body: String): KStepScriptOutcome {
            val code = "$PRELUDE\nstepFile(fileName = \"s.step\", $FIXED_STAMP) {\n$body\n}\n"
            return KStepScriptHost.eval(code)
        }

        fun export(body: String): String {
            val outcome = run(body)
            outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            val model = (outcome as KStepScriptOutcome.Success).model
            return Ap242ShapeExporter.export(model.header, model.roots, model.shapes, model.placements)
        }

        fun read(text: String): OcctStepDocument = OcctKernel.readStep(text.toByteArray())

        "part alpha 0.5 and face alpha 0.25 read back on 1e-6, with the RGB unchanged and the volume intact".config(
            enabled = available,
        ) {
            val text =
                export(
                    """
                    val box = part("A")
                    root(box)
                    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgba(1.0, 0.5, 0.0, 0.5))
                    styleFaces(box, Faces.atMax(Direction3.Z), colour = StepColour.rgba(0.0, 0.0, 1.0, 0.25), expected = 1)
                    """.trimIndent(),
                )
            text shouldContain "SURFACE_STYLE_TRANSPARENT(0.5)"
            text shouldContain "SURFACE_STYLE_TRANSPARENT(0.75)"
            read(text).use { doc ->
                val part = doc.colors.single { it.target == XcafStyleTarget.PART }
                part.color.red shouldBe (1.0 plusOrMinus 1e-6)
                part.color.green shouldBe (0.5 plusOrMinus 1e-6)
                part.color.blue shouldBe (0.0 plusOrMinus 1e-6)
                part.color.alpha shouldBe (0.5 plusOrMinus 1e-6)
                val face = doc.colors.single { it.target == XcafStyleTarget.FACE }
                face.color.blue shouldBe (1.0 plusOrMinus 1e-6)
                face.color.alpha shouldBe (0.25 plusOrMinus 1e-6)
                val node = doc.nodes.single { it.kind == XcafNodeKind.PART }
                doc.partShape(node.index).use { shape ->
                    shape.volume shouldBe (6000.0 plusOrMinus 1e-6)
                    shape.faces
                        .single { it.index == face.subIndex }
                        .centroid.z shouldBe (30.0 plusOrMinus 1e-9)
                }
            }
        }

        "an awkward alpha (0.3), the extreme 0.0 and a hex colour with an alpha channel read back on 1e-6".config(
            enabled = available,
        ) {
            for (
            (expression, expected) in
            listOf(
                "StepColour.rgba(0.2, 0.4, 0.6, 0.3)" to 0.3,
                "StepColour.rgba(0.2, 0.4, 0.6, 0.0)" to 0.0,
                "StepColour.hex(\"#336699CC\")" to 0xCC / 255.0,
            )
            ) {
                val text =
                    export(
                        """
                        val box = part("A")
                        root(box)
                        shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = $expression)
                        """.trimIndent(),
                    )
                read(text).use { doc ->
                    doc.colors
                        .single()
                        .color.alpha shouldBe (expected plusOrMinus 1e-6)
                }
            }
        }

        "an opaque colour written through rgba(.., 1.0) is the same file as through rgb(..)".config(
            enabled = available,
        ) {
            fun file(colour: String) =
                export(
                    """
                    val box = part("A")
                    root(box)
                    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = $colour)
                    styleFaces(box, Faces.atMax(Direction3.Z), colour = $colour, layer = "L", expected = 1)
                    """.trimIndent(),
                )
            file("StepColour.rgba(1.0, 0.5, 0.0, 1.0)") shouldBe file("StepColour.rgb(1.0, 0.5, 0.0)")
            file("StepColour.hex(\"#FF8000FF\")") shouldBe file("StepColour.hex(\"#FF8000\")")
            file("StepColour.rgb(1.0, 0.5, 0.0)") shouldContain "COLOUR_RGB"
            (file("StepColour.rgb(1.0, 0.5, 0.0)").contains("SURFACE_STYLE_TRANSPARENT")) shouldBe false
        }

        "the four top edges get a colour that reads back, each on the geometric top edge".config(enabled = available) {
            val text =
                export(
                    """
                    val box = part("E")
                    root(box)
                    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))
                    styleEdges(box, Edges.atMax(Direction3.Z), StepColour.rgb(1.0, 0.0, 0.0), expected = 4)
                    """.trimIndent(),
                )
            text shouldContain "CURVE_STYLE"
            read(text).use { doc ->
                val edges = doc.colors.filter { it.target == XcafStyleTarget.EDGE }
                edges.size shouldBe 4
                doc.colors.size shouldBe 4
                val node = doc.nodes.single { it.kind == XcafNodeKind.PART }
                doc.partShape(node.index).use { shape ->
                    shape.volume shouldBe (6000.0 plusOrMinus 1e-6)
                    // The edge order may differ after the round trip, so each edge is identified by its geometry.
                    for (e in edges) {
                        e.color.red shouldBe (1.0 plusOrMinus 1e-6)
                        e.color.alpha shouldBe 1.0
                        val info = shape.edges.single { it.index == e.subIndex }
                        info.mid.z shouldBe (30.0 plusOrMinus 1e-9)
                    }
                    edges.map { it.subIndex }.toSet().size shouldBe 4
                }
                doc.styleStats.unattached shouldBe 0
            }
        }

        "part colour, face colour and edge colours together read back".config(enabled = available) {
            val text =
                export(
                    """
                    val box = part("C")
                    root(box)
                    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgba(1.0, 0.5, 0.0, 0.6))
                    styleFaces(box, Faces.atMax(Direction3.Z), colour = StepColours.BLUE, expected = 1)
                    styleEdges(box, Edges.atMax(Direction3.Z), StepColours.GREEN, expected = 4)
                    """.trimIndent(),
                )
            text shouldContain "OVER_RIDING_STYLED_ITEM"
            read(text).use { doc ->
                doc.colors.count { it.target == XcafStyleTarget.PART } shouldBe 1
                doc.colors
                    .single { it.target == XcafStyleTarget.PART }
                    .color.alpha shouldBe (0.6 plusOrMinus 1e-6)
                doc.colors.count { it.target == XcafStyleTarget.FACE } shouldBe 1
                doc.colors.count { it.target == XcafStyleTarget.EDGE } shouldBe 4
                doc.colors
                    .filter { it.target == XcafStyleTarget.EDGE }
                    .forEach { it.color.green shouldBe (0.6 plusOrMinus 1e-6) }
            }
        }

        "edge colours work on a part of an assembly with a second coloured part".config(enabled = available) {
            val text =
                export(
                    """
                    val a = part("A1")
                    val b = part("B1")
                    root(a)
                    root(b)
                    shape(a, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColours.RED)
                    shape(b, OcctKernel.makeBox(5.0, 5.0, 5.0))
                    styleEdges(b, Edges.atMax(Direction3.Z), StepColours.BLUE, expected = 4)
                    """.trimIndent(),
                )
            read(text).use { doc ->
                doc.colors.count { it.target == XcafStyleTarget.PART } shouldBe 1
                doc.colors.count { it.target == XcafStyleTarget.EDGE } shouldBe 4
            }
        }

        "styleEdges errors carry the documented codes".config(enabled = available) {
            fun code(body: String): String {
                val outcome =
                    run(
                        "val box = part(\"E\")\nroot(box)\n" +
                            "shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))\n$body",
                    )
                return outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>().code
            }
            // no edge matches / wrong count: KSTEP-S-011
            code("styleEdges(box, Edges.atMax(Direction3.Z), StepColours.RED, expected = 3)") shouldBe "KSTEP-S-011"
            // a second colour on the same edges: KSTEP-S-014
            code(
                "styleEdges(box, Edges.atMax(Direction3.Z), StepColours.RED, expected = 4)\n" +
                    "styleEdges(box, Edges.atMax(Direction3.Z), StepColours.BLUE, expected = 4)",
            ) shouldBe "KSTEP-S-014"
            // a transparent edge colour: KSTEP-S-012
            code("styleEdges(box, Edges.atMax(Direction3.Z), StepColour.rgba(1.0, 0.0, 0.0, 0.5))") shouldBe
                "KSTEP-S-012"
        }

        "fifty alpha and edge exports leak neither shape handles nor XCAF documents".config(enabled = available) {
            val shapesBefore = OcctBridge.nativeLiveShapeCount()
            val documentsBefore = OcctBridge.nativeXcafDocumentCount()
            repeat(50) {
                val text =
                    export(
                        """
                        val box = part("L")
                        root(box)
                        shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgba(1.0, 0.5, 0.0, 0.5))
                        styleEdges(box, Edges.atMax(Direction3.Z), StepColours.RED, expected = 4)
                        """.trimIndent(),
                    )
                read(text).use { doc -> doc.colors.size shouldBe 5 }
            }
            // The kernel releases script shapes with the model; slack for tests running in parallel in the process.
            (OcctBridge.nativeXcafDocumentCount() - documentsBefore) shouldBeLessThanOrEqual 2
            (OcctBridge.nativeLiveShapeCount() - shapesBefore) shouldBeLessThanOrEqual 100
        }

        "an edge index beyond the shape and a transparent edge style are refused by the writer".config(
            enabled = available,
        ) {
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { box ->
                val dir =
                    java.nio.file.Files
                        .createTempDirectory("kstep-edge-range")
                try {
                    val style =
                        dev.kstep.geometry.style.ShapeStyle(
                            edges =
                                listOf(
                                    dev.kstep.geometry.style
                                        .EdgeStyle(99, dev.kstep.geometry.style.StepColours.RED),
                                ),
                        )
                    val e =
                        shouldThrow<dev.kstep.geometry.style.StyleException> {
                            box.writeStepFile(dir.resolve("x.step"), style = style)
                        }
                    e.kind shouldBe dev.kstep.geometry.style.StyleErrorKind.TARGET
                    e.message!! shouldContain "edge index 99"
                    java.nio.file.Files
                        .exists(dir.resolve("x.step")) shouldBe false
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }

        "alpha export fails predictably when the native bridge is unavailable".config(enabled = !available) {
            shouldThrow<OcctUnavailableException> { OcctKernel.makeBox(1.0, 1.0, 1.0) }
        }

        "the edge colours of a styled shape come out as exactly the selected edges".config(enabled = available) {
            val text =
                export(
                    """
                    val box = part("S")
                    root(box)
                    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))
                    styleEdges(box, Edges.atMin(Direction3.Z), StepColours.YELLOW, expected = 4)
                    """.trimIndent(),
                )
            read(text).use { doc ->
                val node = doc.nodes.single { it.kind == XcafNodeKind.PART }
                doc.partShape(node.index).use { shape ->
                    doc.colors.map {
                        shape.edges
                            .single { e -> e.index == it.subIndex }
                            .mid.z
                    } shouldContainExactly
                        listOf(0.0, 0.0, 0.0, 0.0)
                }
            }
        }
    })
