package dev.kstep.cli

import dev.kstep.geometry.LengthUnitState
import dev.kstep.geometry.RgbColor
import dev.kstep.geometry.StepLengthUnitInfo
import dev.kstep.geometry.StepReadStats
import dev.kstep.geometry.XcafNodeKind
import dev.kstep.shape.importer.ImportPresentation
import dev.kstep.shape.importer.ImportedBody
import dev.kstep.shape.importer.ImportedFaceStyle
import dev.kstep.shape.importer.ImportedOccurrence
import dev.kstep.shape.importer.ImportedPart
import dev.kstep.shape.importer.ImportedPlacement
import dev.kstep.shape.importer.ImportedRoot
import dev.kstep.shape.importer.ImportedStepModel
import dev.kstep.shape.importer.ImportedTotals
import dev.kstep.shape.importer.StepPrescan
import dev.kstep.shape.importer.StructureSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pins the key sets of the `kstep import` JSON document (ADR-0030, ADR-0038): the keys that existed before
 * Welle R must stay, new keys are additive. Hand-built models, no OCCT.
 */
class ImportJsonSchemaTest :
    StringSpec({
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val rigid = ImportedPlacement(null, identity, true)
        val command = CliCommand.Import("x.step", jsonOutput = true, validate = false, renderOut = null)

        fun model(
            part: ImportedPart,
            presentation: ImportPresentation? = null,
            unit: StepLengthUnitInfo? = null,
        ) = ImportedStepModel(
            prescan = StepPrescan(listOf("AUTOMOTIVE_DESIGN"), null, null, mapOf("pmi" to 1)),
            stats = StepReadStats(10, 0, emptyList(), 1),
            lengthUnit = "mm",
            structureSource = StructureSource.XCAF,
            parts = listOf(part),
            assemblies = emptyList(),
            roots = listOf(ImportedRoot(XcafNodeKind.PART, 0)),
            occurrences = listOf(ImportedOccurrence(emptyList(), 0, rigid, null)),
            totals = ImportedTotals(1, null, null),
            warnings = emptyList(),
            sourceLengthUnit = unit,
            presentation = presentation,
        )

        fun json(m: ImportedStepModel): JsonObject = importJson(command, m, null, null, validationFailed = false)

        val plain = ImportedPart(0, "Plain", null, null, 1000.0, null, null)

        "the top-level key set is the pre-Welle-R set plus sourceLengthUnit and presentation" {
            json(model(plain)).keys shouldBe
                setOf(
                    "status",
                    "command",
                    "file",
                    "header",
                    "occt",
                    "lengthUnit",
                    "entityCount",
                    "structureSource",
                    "unknownEntities",
                    "ignored",
                    "parts",
                    "assemblies",
                    "roots",
                    "occurrences",
                    "totals",
                    "warnings",
                    "sourceLengthUnit",
                    "presentation",
                )
        }

        "the ignored block keeps exactly colours, layers and pmi with their occurrence counts" {
            val ignored = json(model(plain))["ignored"]!!.jsonObject
            ignored.keys shouldBe setOf("colours", "layers", "pmi")
            ignored["pmi"]!!.jsonPrimitive.content shouldBe "1"
            ignored["colours"]!!.jsonPrimitive.content shouldBe "0"
        }

        "the part key set is the pre-Welle-R set plus colour, layers and faceStyles" {
            json(model(plain))["parts"]!!.jsonArray[0].jsonObject.keys shouldBe
                setOf(
                    "index",
                    "name",
                    "productId",
                    "volume",
                    "topology",
                    "boundingBox",
                    "colour",
                    "layers",
                    "faceStyles",
                )
        }

        "without styles colour is null and layers and faceStyles are empty" {
            val part = json(model(plain))["parts"]!!.jsonArray[0].jsonObject
            part["colour"] shouldBe JsonNull
            part["layers"] shouldBe JsonArray(emptyList())
            part["faceStyles"] shouldBe JsonArray(emptyList())
            json(model(plain))["sourceLengthUnit"] shouldBe JsonNull
            json(model(plain))["presentation"] shouldBe JsonNull
        }

        "styles come out with their values, in the given order, and bodies carry colour and layers" {
            val styled =
                ImportedPart(
                    0,
                    "Styled",
                    null,
                    null,
                    1000.0,
                    null,
                    null,
                    bodies = listOf(ImportedBody(6.0, 6, RgbColor(1.0, 0.0, 0.0), listOf("B"))),
                    color = RgbColor(1.0, 0.5, 0.0),
                    layers = listOf("Layer-A", "Layer-B"),
                    faceStyles =
                        listOf(
                            ImportedFaceStyle(2, RgbColor(0.0, 0.0, 1.0), listOf("F")),
                            ImportedFaceStyle(5, null, listOf("G")),
                        ),
                    surfaceTypes = mapOf("plane" to 5, "bsplineSurface" to 1),
                )
            val unit = StepLengthUnitInfo(LengthUnitState.DECLARED, listOf("INCH"), 25.4)
            val doc =
                json(model(styled, ImportPresentation(2, 3, listOf("A", "B"), 1, 0, 0), unit))
            val part = doc["parts"]!!.jsonArray[0].jsonObject
            part["colour"]!!
                .jsonObject["g"]!!
                .jsonPrimitive.content
                .toDouble() shouldBe (0.5 plusOrMinus 1e-12)
            part["layers"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Layer-A", "Layer-B")
            val faces = part["faceStyles"]!!.jsonArray.map { it.jsonObject }
            faces.map { it["face"]!!.jsonPrimitive.content } shouldBe listOf("2", "5")
            faces[1]["colour"] shouldBe JsonNull
            part["surfaceTypes"]!!.jsonObject.keys shouldBe setOf("plane", "bsplineSurface")
            val body = part["bodies"]!!.jsonArray[0].jsonObject
            body.keys shouldBe setOf("volume", "faces", "colour", "layers")
            val u = doc["sourceLengthUnit"]!!.jsonObject
            u.keys shouldBe setOf("state", "names", "factorToMm")
            u["state"]!!.jsonPrimitive.content shouldBe "declared"
            u["factorToMm"]!!.jsonPrimitive.content.toDouble() shouldBe 25.4
            doc["presentation"]!!.jsonObject.keys shouldBe
                setOf(
                    "coloredTargets",
                    "layerAssignments",
                    "layers",
                    "unattached",
                    "instanceStylesIgnored",
                    "truncated",
                )
        }

        "a missing unit reports state missing with a null factor" {
            val doc = json(model(plain, unit = StepLengthUnitInfo(LengthUnitState.MISSING, emptyList(), null)))
            doc["sourceLengthUnit"]!!.jsonObject["factorToMm"] shouldBe JsonNull
            doc["sourceLengthUnit"]!!.jsonObject["state"]!!.jsonPrimitive.content shouldBe "missing"
        }

        "the text report shows unit, colour, layers and styled faces on short lines" {
            val styled =
                ImportedPart(
                    0,
                    "Styled",
                    null,
                    null,
                    1000.0,
                    null,
                    null,
                    color = RgbColor(1.0, 0.5, 0.0),
                    layers = listOf("Layer-A"),
                    faceStyles = listOf(ImportedFaceStyle(3, RgbColor(0.0, 0.0, 1.0), listOf("Layer-F"))),
                )
            val unit = StepLengthUnitInfo(LengthUnitState.DECLARED, listOf("INCH"), 25.4)
            val text = importText(command, model(styled, unit = unit), null, null)
            text.contains("unit mm (file: INCH)") shouldBe true
            text.contains("    colour (1, 0.5, 0)  layers [Layer-A]") shouldBe true
            text.contains("    face 3  colour (0, 0, 1)  layers [Layer-F]") shouldBe true
            text.lines().all { it.length <= 160 } shouldBe true
            val missing =
                importText(
                    command,
                    model(plain, unit = StepLengthUnitInfo(LengthUnitState.MISSING, emptyList(), null)),
                    null,
                    null,
                )
            missing.contains("unit mm (file: none declared)") shouldBe true
        }

        "control characters in layer names never reach the output (they are removed natively)" {
            // The native layer drops control characters; the JSON writer additionally escapes any that remain.
            val part = ImportedPart(0, "L", null, null, 1.0, null, null, layers = listOf("a\u0007b"))
            val text = json(model(part)).toString()
            text shouldNotContain "\u0007"
        }
    })
