package dev.kstep.cli

import dev.kstep.geometry.Placement
import dev.kstep.geometry.StepReadStats
import dev.kstep.geometry.XcafNodeKind
import dev.kstep.shape.importer.ImportedAssembly
import dev.kstep.shape.importer.ImportedComponent
import dev.kstep.shape.importer.ImportedOccurrence
import dev.kstep.shape.importer.ImportedPart
import dev.kstep.shape.importer.ImportedPlacement
import dev.kstep.shape.importer.ImportedRoot
import dev.kstep.shape.importer.ImportedStepModel
import dev.kstep.shape.importer.ImportedTotals
import dev.kstep.shape.importer.StepPrescan
import dev.kstep.shape.importer.StructureSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How `kstep import` reports a non-rigid placement (scaling or reflection), on a hand-built model (no OCCT). */
class ImportNonRigidReportTest :
    StringSpec({
        val scaled = doubleArrayOf(2.0, 0.0, 0.0, 7.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val identityMatrix = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        val nonRigid = ImportedPlacement(null, scaled, false)
        val rigid = ImportedPlacement(Placement.IDENTITY, identityMatrix, true)

        val model =
            ImportedStepModel(
                prescan = StepPrescan(listOf("AUTOMOTIVE_DESIGN"), null, null, emptyMap()),
                stats = StepReadStats(10, 0, emptyList(), 1),
                lengthUnit = "mm",
                structureSource = StructureSource.XCAF,
                parts = listOf(ImportedPart(0, "Block", null, null, 1000.0, null, null)),
                assemblies =
                    listOf(
                        ImportedAssembly(
                            0,
                            "Top",
                            null,
                            listOf(
                                ImportedComponent("SCALED", null, XcafNodeKind.PART, 0, nonRigid),
                                ImportedComponent("PLAIN", null, XcafNodeKind.PART, 0, rigid),
                            ),
                        ),
                    ),
                roots = listOf(ImportedRoot(XcafNodeKind.ASSEMBLY, 0)),
                occurrences =
                    listOf(
                        ImportedOccurrence(listOf("SCALED"), 0, ImportedPlacement(null, identityMatrix, false), null),
                        ImportedOccurrence(listOf("PLAIN"), 0, rigid, null),
                    ),
                totals = ImportedTotals(2, null, null),
                warnings = emptyList(),
            )
        val command = CliCommand.Import("scaled.step", jsonOutput = false, validate = false, renderOut = null)

        "the text report marks a non-rigid component as not placed and a rigid one with its translation" {
            val text = importText(command, model, null, null)
            text shouldContain "SCALED -> Block  at non-rigid (not placed)"
            text shouldContain "PLAIN -> Block  at t=(0,0,0)"
            text shouldNotContain "t=(7,0,0)"
        }

        "the JSON report carries the raw matrix for a non-rigid placement and translation/rotation for a rigid one" {
            val json = importJson(command, model, null, null, validationFailed = false)
            val component =
                json["assemblies"]!!
                    .jsonArray[0]
                    .jsonObject["components"]!!
                    .jsonArray
            val scaledPlacement = component[0].jsonObject["placement"]!!.jsonObject
            scaledPlacement["rigid"]!!.jsonPrimitive.content shouldBe "false"
            val matrix = scaledPlacement["matrix"]!!.jsonArray
            matrix.size shouldBe 12
            matrix[0].jsonPrimitive.content shouldBe "2.0"
            scaledPlacement.containsKey("translation") shouldBe false

            val rigidPlacement = component[1].jsonObject["placement"]!!.jsonObject
            rigidPlacement["rigid"]!!.jsonPrimitive.content shouldBe "true"
            (rigidPlacement["translation"] is JsonArray) shouldBe true
            rigidPlacement.containsKey("matrix") shouldBe false

            json["totals"]!!.jsonObject["volume"] shouldBe JsonNull
        }
    })
