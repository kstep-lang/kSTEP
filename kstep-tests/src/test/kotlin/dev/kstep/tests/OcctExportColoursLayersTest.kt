package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctStepDocument
import dev.kstep.geometry.XcafNodeKind
import dev.kstep.geometry.XcafStyleTarget
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.style.FaceStyle
import dev.kstep.geometry.style.ShapeStyle
import dev.kstep.geometry.style.StepColour
import dev.kstep.geometry.style.StyleErrorKind
import dev.kstep.geometry.style.StyleException
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
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

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
 * Colours and layers in the STEP export (ADR-0039, Welle S), in-process: script -> export -> read back with OCCT's own
 * XCAF reader. The class name carries `Occt` so the CI guard that forbids silent skips covers it.
 */
class OcctExportColoursLayersTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun export(body: String): String {
            val code = "$PRELUDE\nstepFile(fileName = \"s.step\", $FIXED_STAMP) {\n$body\n}\n"
            val outcome = KStepScriptHost.eval(code)
            outcome.shouldBeInstanceOf<KStepScriptOutcome.Success>()
            val model = (outcome as KStepScriptOutcome.Success).model
            return Ap242ShapeExporter.export(model.header, model.roots, model.shapes, model.placements)
        }

        fun failure(body: String): KStepScriptOutcome.RuntimeError {
            val code = "$PRELUDE\nstepFile(fileName = \"s.step\", $FIXED_STAMP) {\n$body\n}\n"
            val outcome = KStepScriptHost.eval(code)
            outcome.shouldBeInstanceOf<KStepScriptOutcome.RuntimeError>()
            return outcome as KStepScriptOutcome.RuntimeError
        }

        fun read(text: String): OcctStepDocument = OcctKernel.readStep(text.toByteArray())

        "a coloured part with a layer reads back with the colour on 1e-6, the layer and the unchanged volume".config(
            enabled = available,
        ) {
            val text =
                export(
                    """
                    val box = part("B")
                    root(box)
                    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.5, 0.0), layer = "Layer-A")
                    """.trimIndent(),
                )
            read(text).use { doc ->
                doc.colors.size shouldBe 1
                val colour = doc.colors.single()
                colour.target shouldBe XcafStyleTarget.PART
                colour.color.red shouldBe (1.0 plusOrMinus 1e-6)
                colour.color.green shouldBe (0.5 plusOrMinus 1e-6)
                colour.color.blue shouldBe (0.0 plusOrMinus 1e-6)
                doc.layers.map { it.layer } shouldContainExactly listOf("Layer-A")
                val part = doc.nodes.single { it.kind == XcafNodeKind.PART }
                doc.partShape(part.index).use { it.volume shouldBe (6000.0 plusOrMinus 1e-6) }
            }
        }

        "a styled face is the geometric top face, not just an index, also after the height changes".config(
            enabled = available,
        ) {
            for (height in listOf(30.0, 40.0)) {
                val text =
                    export(
                        """
                        val box = part("F")
                        root(box)
                        shape(box, OcctKernel.makeBox(10.0, 20.0, $height))
                        styleFaces(box, Faces.atMax(Direction3.Z), colour = StepColour.rgb(0.0, 0.0, 1.0), layer = "Layer-F", expected = 1)
                        """.trimIndent(),
                    )
                read(text).use { doc ->
                    val colour = doc.colors.single()
                    colour.target shouldBe XcafStyleTarget.FACE
                    colour.color.blue shouldBe (1.0 plusOrMinus 1e-6)
                    doc.layers.single().layer shouldBe "Layer-F"
                    doc.layers.single().target shouldBe XcafStyleTarget.FACE
                    val part = doc.nodes.single { it.kind == XcafNodeKind.PART }
                    doc.partShape(part.index).use { shape ->
                        shape.volume shouldBe (10.0 * 20.0 * height plusOrMinus 1e-6)
                        // subIndex is 0-based; FaceInfo.index is the same enumeration.
                        val face = shape.faces.single { it.index == colour.subIndex }
                        face.area shouldBe (200.0 plusOrMinus 1e-6)
                        face.normal!!.z shouldBe (1.0 plusOrMinus 1e-9)
                        face.centroid.z shouldBe (height plusOrMinus 1e-9)
                    }
                }
            }
        }

        "two coloured parts of a placed assembly keep their colours and their placement".config(enabled = available) {
            val text =
                export(
                    """
                    val asm = part("ASM")
                    val red = part("RED")
                    val green = part("GREEN")
                    val nr = nextAssemblyUsageOccurrence("NR") { name = "r"; relatingProductDefinition = asm; relatedProductDefinition = red }.getOrThrow()
                    val ng = nextAssemblyUsageOccurrence("NG") { name = "g"; relatingProductDefinition = asm; relatedProductDefinition = green }.getOrThrow()
                    roots(nr, ng)
                    shape(red, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.0, 0.0))
                    shape(green, OcctKernel.makeBox(10.0, 10.0, 10.0), colour = StepColour.rgb(0.0, 0.6, 0.0))
                    place(ng, translation = vec(30.0, 0.0, 0.0))
                    """.trimIndent(),
                )
            read(text).use { doc ->
                doc.colors.size shouldBe 2
                val parts = doc.nodes.filter { it.kind == XcafNodeKind.PART }
                val byName = parts.associateBy { it.name }
                val redNode = byName.getValue("RED")
                val greenNode = byName.getValue("GREEN")
                doc.colors
                    .single { it.nodeIndex == redNode.index }
                    .color.red shouldBe (1.0 plusOrMinus 1e-6)
                doc.colors
                    .single { it.nodeIndex == greenNode.index }
                    .color.green shouldBe (0.6 plusOrMinus 1e-6)
                val greenUsage =
                    doc.nodes.single {
                        it.kind == XcafNodeKind.COMPONENT &&
                            it.referredIndex == greenNode.index
                    }
                greenUsage.localMatrix[3] shouldBe (30.0 plusOrMinus 1e-9)
            }
        }

        "a shape without any style is exported without a single style entity".config(enabled = available) {
            val text =
                export(
                    """
                    val box = part("P")
                    root(box)
                    shape(box, OcctKernel.makeBox(1.0, 2.0, 3.0))
                    """.trimIndent(),
                )
            for (name in listOf("STYLED_ITEM", "PRESENTATION_LAYER_ASSIGNMENT", "COLOUR_RGB", "MECHANICAL_DESIGN")) {
                text shouldNotContain name
            }
        }

        "a two-body part gives every solid the part colour and the part layer".config(enabled = available) {
            val text =
                export(
                    """
                    val two = part("TWO")
                    root(two)
                    val shape = OcctKernel.compound(listOf(OcctKernel.makeBox(10.0, 20.0, 30.0), mirror(OcctKernel.makeBox(4.0, 4.0, 4.0), MirrorPlane.x(-5.0))))
                    shape(two, shape, colour = StepColour.rgb(0.2, 0.4, 0.6), layer = "Both")
                    """.trimIndent(),
                )
            read(text).use { doc ->
                doc.colors.map { it.target }.toSet() shouldBe setOf(XcafStyleTarget.SOLID)
                doc.colors.size shouldBe 2
                doc.layers.map { it.layer }.toSet() shouldBe setOf("Both")
            }
        }

        "hostile layer names survive the Part 21 round trip: apostrophes, quotes, 128 characters".config(
            enabled = available,
        ) {
            val names = listOf("O'Neil", "''", "say \"hi\"", "a b  c", "#1 (x) & y; z", "x".repeat(128))
            for (name in names) {
                val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                val text =
                    export(
                        """
                        val box = part("H")
                        root(box)
                        shape(box, OcctKernel.makeBox(1.0, 1.0, 1.0), layer = "$escaped")
                        """.trimIndent(),
                    )
                read(text).use { doc -> doc.layers.single().layer shouldBe name }
            }
        }

        "layer names with a backslash, umlaut or emoji are rejected with KSTEP-S-013 before any export".config(
            enabled = available,
        ) {
            for (name in listOf("back\\\\slash", "Fr\\u00e4se", "emoji \\uD83D\\uDE00")) {
                failure("val b = part(\"A\"); root(b); shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0), layer = \"$name\")")
                    .code shouldBe "KSTEP-S-013"
            }
        }

        "style errors carry their own code: colour, layer, conflict, unknown target, empty face selection".config(
            enabled = available,
        ) {
            failure(
                "val b = part(\"A\"); root(b); shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0), colour = StepColour.rgb(Double.NaN, 0.0, 0.0))",
            ).code shouldBe "KSTEP-S-012"
            failure("val b = part(\"A\"); root(b); shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0), layer = \"\")")
                .code shouldBe "KSTEP-S-013"
            failure(
                "val b = part(\"A\"); root(b); shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0), layer = \"x\".repeat(129))",
            ).code shouldBe "KSTEP-S-013"
            failure("val b = part(\"A\"); root(b); shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0), layer = \"a\\u0007b\")")
                .code shouldBe "KSTEP-S-013"
            failure(
                "val b = part(\"A\"); root(b); " +
                    "shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0), colour = StepColour.rgb(1.0, 0.0, 0.0)); " +
                    "style(b, colour = StepColour.rgb(0.0, 1.0, 0.0))",
            ).code shouldBe "KSTEP-S-014"
            failure(
                "val b = part(\"A\"); root(b); shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0)); " +
                    "styleFaces(b, Faces.atMax(Direction3.Z), layer = \"L1\"); styleFaces(b, Faces.atMax(Direction3.Z), layer = \"L2\")",
            ).code shouldBe "KSTEP-S-014"
            failure("val b = part(\"A\"); root(b); style(b, colour = StepColour.rgb(1.0, 0.0, 0.0))")
                .code shouldBe "KSTEP-S-015"
            failure("val b = part(\"A\"); root(b); styleFaces(b, Faces.atMax(Direction3.Z), layer = \"L\")")
                .code shouldBe "KSTEP-S-015"
            val empty =
                failure(
                    "val b = part(\"A\"); root(b); shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0)); " +
                        "styleFaces(b, Faces.normal(Direction3(0.3, 1.0, 0.7)), colour = StepColour.rgb(1.0, 0.0, 0.0))",
                )
            empty.code shouldBe "KSTEP-S-011"
            empty.message shouldContain "matched no face"
            empty.message shouldNotContain "\tat "
        }

        "a face index outside the shape is rejected before any native write".config(enabled = available) {
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { box ->
                val dir = Files.createTempDirectory("kstep-style-test")
                try {
                    val e =
                        shouldThrow<StyleException> {
                            box.writeStepFile(
                                dir.resolve("x.step"),
                                style = ShapeStyle(faces = listOf(FaceStyle(6, StepColour.rgb(1.0, 0.0, 0.0)))),
                            )
                        }
                    e.kind shouldBe StyleErrorKind.TARGET
                    Files.exists(dir.resolve("x.step")) shouldBe false
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }

        "the native writer rejects hostile arguments without crashing the JVM".config(enabled = available) {
            val handle = OcctBridge.nativeMakeBox(1.0, 1.0, 1.0)
            try {
                val dir = Files.createTempDirectory("kstep-style-hostile")
                try {
                    val path = dir.resolve("h.step").toString()

                    fun call(
                        partColour: DoubleArray? = null,
                        partLayer: String? = null,
                        indices: IntArray = IntArray(0),
                        colours: DoubleArray = DoubleArray(0),
                        layers: Array<String?> = emptyArray(),
                    ) = OcctBridge.nativeWriteStepStyled(
                        handle,
                        path,
                        "AP242DIS",
                        partColour,
                        partLayer,
                        indices,
                        colours,
                        layers,
                    )
                    shouldThrow<IllegalArgumentException> { call(partColour = doubleArrayOf(1.0, 0.0)) }
                    shouldThrow<IllegalArgumentException> { call(partColour = doubleArrayOf(1.0, 0.0, 1.5)) }
                    shouldThrow<IllegalArgumentException> { call(partColour = doubleArrayOf(Double.NaN, 0.0, 0.0)) }
                    shouldThrow<IllegalArgumentException> { call(partLayer = "") }
                    shouldThrow<IllegalArgumentException> { call(partLayer = "bad\u0000name") }
                    shouldThrow<IllegalArgumentException> { call(partLayer = "y".repeat(129)) }
                    shouldThrow<IllegalArgumentException> {
                        call(indices = intArrayOf(0), colours = DoubleArray(0), layers = arrayOf(null))
                    }
                    shouldThrow<IllegalArgumentException> {
                        call(
                            indices = intArrayOf(1, 1),
                            colours = DoubleArray(6) { Double.NaN },
                            layers = arrayOf("a", "b"),
                        )
                    }
                    shouldThrow<IllegalArgumentException> {
                        call(
                            indices = intArrayOf(-1),
                            colours = doubleArrayOf(Double.NaN, 0.0, 0.0),
                            layers = arrayOf("a"),
                        )
                    }
                    shouldThrow<IllegalArgumentException> {
                        call(
                            indices = intArrayOf(99),
                            colours = doubleArrayOf(Double.NaN, 0.0, 0.0),
                            layers = arrayOf("a"),
                        )
                    }
                    shouldThrow<IllegalArgumentException> {
                        call(indices = intArrayOf(0), colours = doubleArrayOf(0.5, 2.0, 0.5), layers = arrayOf(null))
                    }
                    Files.exists(dir.resolve("h.step")) shouldBe false
                    // The shape stays usable afterwards.
                    OcctBridge.nativeShapeVolume(handle) shouldBe (1.0 plusOrMinus 1e-9)
                } finally {
                    dir.toFile().deleteRecursively()
                }
            } finally {
                OcctBridge.nativeReleaseShape(handle)
            }
        }

        "fifty styled exports leak neither shape handles nor XCAF documents".config(enabled = available) {
            val shapesBefore = OcctBridge.nativeLiveShapeCount()
            val documentsBefore = OcctBridge.nativeXcafDocumentCount()
            val dir = Files.createTempDirectory("kstep-style-leak")
            try {
                OcctKernel.makeBox(10.0, 20.0, 30.0).use { box ->
                    val style =
                        ShapeStyle(
                            colour = StepColour.rgb(0.1, 0.2, 0.3),
                            layer = "L",
                            faces = listOf(FaceStyle(2, StepColour.rgb(1.0, 0.0, 0.0), "F")),
                        )
                    repeat(50) { i -> box.writeStepFile(dir.resolve("leak-$i.step"), style = style) }
                    box.volume shouldBe (6000.0 plusOrMinus 1e-6)
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
            // Slack for tests running in parallel inside the same process.
            (OcctBridge.nativeLiveShapeCount() - shapesBefore) shouldBeLessThanOrEqual 2
            (OcctBridge.nativeXcafDocumentCount() - documentsBefore) shouldBeLessThanOrEqual 2
        }

        "a failing styled write still closes its XCAF document".config(enabled = available) {
            val documentsBefore = OcctBridge.nativeXcafDocumentCount()
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { box ->
                val missing = Files.createTempDirectory("kstep-style-missing").resolve("a").resolve("b.step")
                // The parent check fires in Kotlin; a native write to an unwritable path reports a status instead.
                shouldThrow<IllegalArgumentException> {
                    box.writeStepFile(missing, style = ShapeStyle(colour = StepColour.rgb(1.0, 0.0, 0.0)))
                }
            }
            (OcctBridge.nativeXcafDocumentCount() - documentsBefore) shouldBeLessThanOrEqual 2
        }

        "the styled writer reports an unwritable path as a geometry exception".config(enabled = available) {
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { box ->
                val dir = Files.createTempDirectory("kstep-style-ro")
                try {
                    val target = dir.resolve("sub")
                    Files.createDirectory(target)
                    // A directory as the output file: the native write fails.
                    shouldThrow<OcctGeometryException> {
                        box.writeStepFile(target, style = ShapeStyle(colour = StepColour.rgb(1.0, 0.0, 0.0)))
                    }
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
        }
    })
