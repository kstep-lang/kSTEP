package dev.kstep.tests

import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21Writer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Not a regression test in the usual sense — this writes a real, on-disk STEP Part 21 file so it
 * can be manually re-imported into a third-party STEP viewer (FreeCAD), which is ADR-0001's V1
 * acceptance criterion #2 ("validiert erfolgreich in einem gängigen STEP-Viewer"). The roundtrip
 * assertion below is the automated half of that criterion; the FreeCAD import is the manual half,
 * done separately against the file this test produces at `build/freecad-validation/`.
 */
class FreeCadValidationExportTest :
    StringSpec({
        "a 3-part assembly (housing containing bracket and screw) exports to a real .step file for FreeCAD import" {
            val bracket =
                product("BRK-001") {
                    name = "Bracket"
                    description = "Mounting bracket"
                }.getOrThrow()
            val bracketFormation =
                productDefinitionFormation("BRK-001-F") {
                    description = ""
                    ofProduct = bracket
                }.getOrThrow()
            val bracketDefinition =
                productDefinition("BRK-001-D") {
                    description = ""
                    formation = bracketFormation
                }.getOrThrow()

            val screw =
                product("SCR-001") {
                    name = "Screw"
                    description = "M4x10 fastener"
                }.getOrThrow()
            val screwFormation =
                productDefinitionFormation("SCR-001-F") {
                    description = ""
                    ofProduct = screw
                }.getOrThrow()
            val screwDefinition =
                productDefinition("SCR-001-D") {
                    description = ""
                    formation = screwFormation
                }.getOrThrow()

            val housing =
                product("HSG-001") {
                    name = "Housing"
                    description = "Enclosure housing"
                }.getOrThrow()
            val housingFormation =
                productDefinitionFormation("HSG-001-F") {
                    description = ""
                    ofProduct = housing
                }.getOrThrow()
            val housingDefinition =
                productDefinition("HSG-001-D") {
                    description = ""
                    formation = housingFormation
                }.getOrThrow()

            val bracketUsage =
                nextAssemblyUsageOccurrence("NAUO-001") {
                    name = ""
                    relatingProductDefinition = housingDefinition
                    relatedProductDefinition = bracketDefinition
                    referenceDesignator = "RD-1"
                }.getOrThrow()
            val screwUsage =
                nextAssemblyUsageOccurrence("NAUO-002") {
                    name = ""
                    relatingProductDefinition = housingDefinition
                    relatedProductDefinition = screwDefinition
                    referenceDesignator = "RD-2"
                }.getOrThrow()

            val header =
                Part21Header(
                    fileName = "kstep-assembly.step",
                    timestamp = "2026-07-21T12:00:00",
                    schemaIdentifiers = listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"),
                    description = listOf("kSTEP ADR-0001 acceptance-criterion-2 manual FreeCAD validation fixture"),
                    author = listOf("kSTEP"),
                    organization = listOf("kSTEP"),
                )
            val exported = Part21Writer.write(header, listOf(bracketUsage, screwUsage))

            // Automated half of the acceptance criterion: parse(export(model)) == model.
            val result = Part21Reader.read(exported)
            result.isFullySuccessful shouldBe true

            // Manual half: write to disk so it can be opened in a real STEP viewer (FreeCAD).
            val outDir = File("build/freecad-validation").apply { mkdirs() }
            File(outDir, "kstep-assembly.step").writeText(exported)
        }
    })
