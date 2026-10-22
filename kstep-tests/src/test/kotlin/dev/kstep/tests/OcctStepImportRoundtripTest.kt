package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.Placement
import dev.kstep.geometry.XcafNodeKind
import dev.kstep.script.KStepModel
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.shape.Ap242ShapeExporter
import dev.kstep.shape.importer.ImportedStepModel
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImporter
import dev.kstep.shape.importer.StructureSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.abs

/**
 * Round trip kSTEP script -> STEP (the same merge path `kstep export` uses) -> [StepImporter], compared
 * against the ORIGINAL shapes of the script and against hand-computed constants (ADR-0030). The class
 * name carries `Occt` so the CI skip gate sees these tests.
 */
class OcctStepImportRoundtripTest :
    StringSpec({
        val workDir = File("build/step-import-roundtrip").apply { mkdirs() }
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyResource(
            resource: String,
            target: String = resource.substringAfterLast('/'),
        ): File {
            val file = File(workDir, target)
            val input =
                requireNotNull(OcctStepImportRoundtripTest::class.java.getResourceAsStream("/$resource")) { resource }
            input.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            return file
        }

        fun evalScript(name: String): KStepModel {
            val script = copyResource(name)
            val outcome = KStepScriptHost.eval(script)
            return (outcome as? KStepScriptOutcome.Success)?.model ?: error("script $name failed: $outcome")
        }

        /** Exports [model] exactly like `kstep export` does and imports the resulting file. */
        fun exportAndImport(
            model: KStepModel,
            stepName: String,
        ): ImportedStepModel {
            val text = Ap242ShapeExporter.export(model.header, model.roots, model.shapes, model.placements)
            val out = File(workDir, stepName)
            out.writeText(text)
            val result = StepImporter.read(out.path)
            return (result as? StepImportResult.Success)?.model ?: error("import of $stepName failed: $result")
        }

        fun matches(
            actual: Double,
            expected: Double,
            absTol: Double,
        ) = abs(actual - expected) <= absTol

        /** Single-part fixtures: compare with the script's own shape and with hand-computed constants. */
        fun singlePart(
            script: String,
            expectedVolume: Double,
            expectedFaces: Int,
        ) {
            val model = evalScript(script)
            try {
                exportAndImport(model, script.removeSuffix(".kstep.kts") + ".step").use { imported ->
                    imported.structureSource shouldBe StructureSource.XCAF
                    imported.parts.size shouldBe 1
                    imported.occurrences.size shouldBe 1
                    val original = model.shapes.single().shape
                    val part = imported.parts.single()
                    val product =
                        model.shapes
                            .single()
                            .productDefinition.formation.ofProduct
                    part.productId shouldBe product.id
                    part.name shouldBe product.name
                    // against the script's own shape
                    (abs(part.volume!! - original.volume) / original.volume <= 1e-6) shouldBe true
                    part.topology!!.faces shouldBe original.topology.faces
                    val a = part.boundingBox.shouldNotBeNull()
                    val b = original.boundingBox.shouldNotBeNull()
                    listOf(
                        a.minX to b.minX,
                        a.minY to b.minY,
                        a.minZ to b.minZ,
                        a.maxX to b.maxX,
                        a.maxY to b.maxY,
                        a.maxZ to b.maxZ,
                    ).forEach { (x, y) -> matches(x, y, 1e-4) shouldBe true }
                    // against hand computation
                    part.volume!! shouldBe (expectedVolume plusOrMinus 1e-3)
                    part.topology!!.faces shouldBe expectedFaces
                }
            } finally {
                model.shapes.forEach { it.shape.close() }
            }
        }

        "export-box-20x30x40 round-trips: volume 24000, 6 faces, bbox 20x30x40".config(enabled = available) {
            singlePart("export-box-20x30x40.kstep.kts", 24000.0, 6)
        }

        "export-plate-with-hole round-trips: volume 15497.346, 7 faces".config(enabled = available) {
            singlePart("export-plate-with-hole.kstep.kts", 15497.346, 7)
        }

        "export-revolved-sleeve round-trips: volume 4712.389, 4 faces".config(enabled = available) {
            singlePart("export-revolved-sleeve.kstep.kts", 4712.389, 4)
        }

        "export-boolean-union round-trips: volume 7000, 10 faces".config(enabled = available) {
            singlePart("export-boolean-union.kstep.kts", 7000.0, 10)
        }

        "export-chamfered-prism round-trips: volume 23920, 7 faces".config(enabled = available) {
            singlePart("export-chamfered-prism.kstep.kts", 23920.0, 7)
        }

        "export-placed-assembly round-trips structure, ids, placements and global boxes".config(enabled = available) {
            val model = evalScript("export-placed-assembly.kstep.kts")
            try {
                exportAndImport(model, "placed-assembly.step").use { imported ->
                    imported.assemblies.size shouldBe 1
                    imported.parts.size shouldBe 3
                    imported.occurrences.size shouldBe 4
                    imported.roots.single().kind shouldBe XcafNodeKind.ASSEMBLY
                    val assembly = imported.assemblies.single()
                    assembly.name shouldBe "Baugruppe"
                    assembly.productId shouldBe "ASM-001"
                    assembly.components.map { it.name } shouldBe listOf("NAUO-H", "NAUO-B", "NAUO-S1", "NAUO-S2")
                    assembly.components.map { it.usageId } shouldBe listOf("NAUO-H", "NAUO-B", "NAUO-S1", "NAUO-S2")
                    imported.parts.map { it.name }.toSet() shouldBe setOf("Gehaeuse", "Halter", "Schraube")
                    imported.parts.map { it.productId }.toSet() shouldBe setOf("HSG-001", "BRK-001", "SCR-001")

                    // lokale Platzierungen elementweise gegen die Skript-Platzierungen
                    val bracket = assembly.components.single { it.name == "NAUO-B" }
                    val expected = Placement.rotationZ(90.0).then(Placement.translation(30.0, 5.0, 10.0)).rowMajor3x4()
                    expected.indices.forEach { i -> matches(bracket.local.matrix[i], expected[i], 1e-9) shouldBe true }

                    // Schraube kommt zweimal mit demselben Teil vor
                    val screwIndex = imported.parts.single { it.name == "Schraube" }.index
                    imported.occurrences.count { it.partIndex == screwIndex } shouldBe 2

                    // Halter global [10,30]x[5,15]x[10,15]
                    val halter =
                        imported.occurrences
                            .single {
                                it.path ==
                                    listOf(
                                        "NAUO-B",
                                    )
                            }.boundingBox
                            .shouldNotBeNull()
                    listOf(
                        halter.minX to 10.0,
                        halter.maxX to 30.0,
                        halter.minY to 5.0,
                        halter.maxY to 15.0,
                        halter.minZ to 10.0,
                        halter.maxZ to 15.0,
                    ).forEach { (x, y) -> matches(x, y, 1e-4) shouldBe true }

                    // Summen
                    imported.totals.occurrenceCount shouldBe 4
                    imported.totals.volume!! shouldBe (17384.0 plusOrMinus 1e-3)
                    val total = imported.totals.boundingBox.shouldNotBeNull()
                    listOf(
                        total.minX to 0.0,
                        total.maxX to 40.0,
                        total.minY to 0.0,
                        total.maxY to 40.0,
                        total.minZ to 0.0,
                        total.maxZ to 22.0,
                    ).forEach { (x, y) -> matches(x, y, 1e-4) shouldBe true }
                }
            } finally {
                model.shapes.forEach { it.shape.close() }
            }
        }

        "export-placed-assembly-own-geometry imports the helper part OCCT creates for an assembly with geometry".config(
            enabled = available,
        ) {
            val model = evalScript("export-placed-assembly-own-geometry.kstep.kts")
            try {
                exportAndImport(model, "placed-assembly-own-geometry.step").use { imported ->
                    // Housing carries its own box AND a component: XCAF models that as an assembly whose
                    // geometry sits in a helper part named SOLID (ADR-0028). Not an error.
                    imported.occurrences.size shouldBe 2
                    imported.totals.volume!! shouldBe (60000.0 + 1000.0 plusOrMinus 1e-3)
                    val total = imported.totals.boundingBox.shouldNotBeNull()
                    matches(total.minX, 0.0, 1e-4) shouldBe true
                    matches(total.maxX, 60.0, 1e-4) shouldBe true
                }
            } finally {
                model.shapes.forEach { it.shape.close() }
            }
        }

        "a nested OCCT-authored assembly composes the placements innermost first".config(enabled = available) {
            val file = copyResource("import/nested-occt-reference.step")
            val result = StepImporter.read(file.path)
            val imported = (result as StepImportResult.Success).model
            imported.use {
                it.occurrences.size shouldBe 1
                val occurrence = it.occurrences.single()
                occurrence.path shouldBe listOf("NAUO-SUB", "NAUO-KLOTZ")
                // Wrong order would give [-5,5]x[80,100]x[0,5].
                val box = occurrence.boundingBox.shouldNotBeNull()
                listOf(
                    box.minX to 90.0,
                    box.maxX to 100.0,
                    box.minY to -15.0,
                    box.maxY to 5.0,
                    box.minZ to 0.0,
                    box.maxZ to 5.0,
                ).forEach { (x, y) -> matches(x, y, 1e-4) shouldBe true }
                it.totals.volume!! shouldBe (1000.0 plusOrMinus 1e-6)
                it.assemblies.size shouldBe 2
            }
        }
    })
