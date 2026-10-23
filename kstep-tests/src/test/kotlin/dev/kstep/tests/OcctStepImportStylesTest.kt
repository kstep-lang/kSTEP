package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.shape.importer.ImportedStepModel
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImporter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Colours and layers of an imported STEP file (ADR-0038): solid, face and layer assignments, the sRGB value of
 * the file (not OCCT's linear RGB), the face-index mapping onto `OcctShape.faces`, and the CLI documents.
 */
class OcctStepImportStylesTest :
    StringSpec({
        val workDir = File("build/step-import-styles").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun bytes(name: String): ByteArray =
            requireNotNull(OcctStepImportStylesTest::class.java.getResourceAsStream("/import/$name")) { name }
                .use { it.readBytes() }

        fun <T> withModel(
            name: String,
            block: (ImportedStepModel) -> T,
        ): T {
            val result = StepImporter.read(bytes(name))
            check(result is StepImportResult.Success) { "import failed: $result" }
            return result.model.use(block)
        }

        "styled-box: the part colour is the file's sRGB value (1, 0.5, 0), not OCCT's linear 0.214".config(
            enabled = available,
        ) {
            withModel("styled-box.step") { model ->
                val colour = model.parts[0].color!!
                colour.red shouldBe (1.0 plusOrMinus 1e-4)
                colour.green shouldBe (0.5 plusOrMinus 1e-4)
                colour.blue shouldBe (0.0 plusOrMinus 1e-4)
                model.parts[0].volume!! shouldBe (24000.0 plusOrMinus 1e-6)
            }
        }

        "styled-box: exactly one face is blue, and its index addresses the +Z face of area 600 in OcctShape.faces"
            .config(
                enabled = available,
            ) {
                withModel("styled-box.step") { model ->
                    val part = model.parts[0]
                    part.faceStyles.size shouldBe 1
                    val style = part.faceStyles[0]
                    style.color!!.blue shouldBe (1.0 plusOrMinus 1e-4)
                    style.color!!.red shouldBe (0.0 plusOrMinus 1e-4)
                    style.layers shouldBe listOf("Layer-F")
                    // The index is 0-based in OCCT's face enumeration of the part, the same list as OcctShape.faces.
                    val face = part.shape!!.faces[style.faceIndex]
                    face.area shouldBe (600.0 plusOrMinus 1e-6)
                    face.normal!!.z shouldBe (1.0 plusOrMinus 1e-9)
                    face.centroid.z shouldBe (40.0 plusOrMinus 1e-6)
                }
            }

        "styled-box: layers sit on the part (Layer-A) and the face (Layer-F)".config(enabled = available) {
            withModel("styled-box.step") { model ->
                model.parts[0].layers shouldBe listOf("Layer-A")
                val presentation = model.presentation!!
                presentation.layerNames shouldBe listOf("Layer-A", "Layer-F")
                presentation.coloredTargets shouldBe 2
                presentation.layerAssignments shouldBe 2
                presentation.unattached shouldBe 0
                presentation.truncated shouldBe 0
                model.warnings.none { it.contains("could not be attached") } shouldBe true
            }
        }

        "a part with two solids carries one colour per body, in solid order".config(enabled = available) {
            withModel("two-body-styled.step") { model ->
                val bodies = model.parts[0].bodies!!
                bodies.size shouldBe 2
                // The bodies have different volumes, so this pins the solid order of the styles to the summaries.
                bodies[0].volume shouldBe (6000.0 plusOrMinus 1e-6)
                bodies[0].color!!.red shouldBe (1.0 plusOrMinus 1e-4)
                bodies[0].color!!.green shouldBe (0.0 plusOrMinus 1e-4)
                bodies[1].volume shouldBe (64.0 plusOrMinus 1e-6)
                bodies[1].color!!.green shouldBe (1.0 plusOrMinus 1e-4)
                bodies[1].color!!.red shouldBe (0.0 plusOrMinus 1e-4)
                model.parts[0].color shouldBe null
            }
        }

        "an unstyled file has no colour, no layers and zero presentation counts".config(enabled = available) {
            withModel("box-20x30x40.step") { model ->
                model.parts[0].color shouldBe null
                model.parts[0].layers shouldBe emptyList()
                model.parts[0].faceStyles shouldBe emptyList()
                model.presentation!!.coloredTargets shouldBe 0
                model.presentation!!.layerAssignments shouldBe 0
            }
        }

        "colours and layers are no longer reported as ignored; the occurrence counters keep their meaning".config(
            enabled = available,
        ) {
            withModel("styled-box.step") { model ->
                model.warnings.none { it.contains("ignored (not imported)") } shouldBe true
                model.warnings.none { it.contains("colours") } shouldBe true
                model.prescan.ignoredCategories["colours"] shouldBe 2
                model.prescan.ignoredCategories["layers"] shouldBe 2
            }
        }

        "CLI JSON for styled-box carries colour, layers, faceStyles and presentation".config(enabled = available) {
            File(workDir, "styled-box.step").writeBytes(bytes("styled-box.step"))
            val result = runner.run("import", "styled-box.step", "--output", "json")
            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            val part = json["parts"]!!.jsonArray[0].jsonObject
            part["colour"]!!
                .jsonObject["g"]!!
                .jsonPrimitive.content
                .toDouble() shouldBe (0.5 plusOrMinus 1e-4)
            part["layers"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Layer-A")
            val face = part["faceStyles"]!!.jsonArray[0].jsonObject
            face["face"]!!.jsonPrimitive.content shouldBe "5"
            face["layers"]!!.jsonArray[0].jsonPrimitive.content shouldBe "Layer-F"
            json["presentation"]!!.jsonObject["layerAssignments"]!!.jsonPrimitive.content shouldBe "2"
            json["ignored"]!!.jsonObject["colours"]!!.jsonPrimitive.content shouldBe "2"
            json["sourceLengthUnit"]!!.jsonObject["state"]!!.jsonPrimitive.content shouldBe "declared"
        }

        "CLI JSON for an unstyled box has a null colour and empty layers".config(enabled = available) {
            File(workDir, "box-20x30x40.step").writeBytes(bytes("box-20x30x40.step"))
            val result = runner.run("import", "box-20x30x40.step", "--output", "json")
            result.exitCode shouldBe 0
            val part =
                Json
                    .parseToJsonElement(result.stdout.trim())
                    .jsonObject["parts"]!!
                    .jsonArray[0]
                    .jsonObject
            part["colour"] shouldBe JsonNull
            part["layers"]!!.jsonArray.size shouldBe 0
        }

        "CLI text for styled-box shows the colour, layers and the styled face".config(enabled = available) {
            File(workDir, "styled-box.step").writeBytes(bytes("styled-box.step"))
            val result = runner.run("import", "styled-box.step")
            result.exitCode shouldBe 0
            result.stdout shouldContain "colour (1, 0.5, 0)  layers [Layer-A]"
            result.stdout shouldContain "face 5  colour (0, 0, 1)  layers [Layer-F]"
            result.stdout shouldContain "unit mm (file: millimetre)"
            result.stdout shouldNotContain "ignored (not imported)"
        }
    })
