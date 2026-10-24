package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Subprocess-level coverage of colours and layers in `kstep export`, `kstep import` and `kstep render` (ADR-0039). The
 * class name carries `Occt` so the CI skip gate (which inspects `TEST-*Occt*.xml`) covers these tests.
 */
class CliExportOcctColoursLayersIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-colours-layers-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(
            name: String,
            transform: (String) -> String = { it },
        ): File {
            val target = File(workDir, name)
            val resource =
                requireNotNull(
                    CliExportOcctColoursLayersIntegrationTest::class.java.getResourceAsStream("/$name"),
                ) { name }
            target.writeText(transform(resource.use { String(it.readBytes(), Charsets.UTF_8) }))
            return target
        }

        fun importJson(step: File): JsonObject {
            val result = runner.run("import", step.name, "--output", "json")
            result.exitCode shouldBe 0
            return Json.parseToJsonElement(result.stdout.trim()).jsonObject
        }

        "a part colour and layer exported by the CLI come back through kstep import".config(enabled = available) {
            val script = copyFixture("export-colour-layer-box.kstep.kts")
            val out = File(workDir, "colour-layer-box.step").apply { delete() }
            val exported = runner.run("export", script.name, "--out", out.name)
            exported.exitCode shouldBe 0

            val part = importJson(out)["parts"]!!.jsonArray.single().jsonObject
            part["volume"]!!.jsonPrimitive.double shouldBe (6000.0 plusOrMinus 1e-6)
            val colour = part["colour"]!!.jsonObject
            colour["r"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            colour["g"]!!.jsonPrimitive.double shouldBe (0.5 plusOrMinus 1e-6)
            colour["b"]!!.jsonPrimitive.double shouldBe (0.0 plusOrMinus 1e-6)
            part["layers"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Layer-A")
        }

        "the top face colour follows the face when the height changes in the script".config(enabled = available) {
            for (height in listOf("30.0", "40.0")) {
                val script =
                    copyFixture(
                        "export-colour-face-top.kstep.kts",
                    ) { it.replace("val height = 30.0", "val height = $height") }
                val out = File(workDir, "face-top-$height.step").apply { delete() }
                runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0

                val part = importJson(out)["parts"]!!.jsonArray.single().jsonObject
                part["volume"]!!.jsonPrimitive.double shouldBe (200.0 * height.toDouble() plusOrMinus 1e-6)
                val faceStyle = part["faceStyles"]!!.jsonArray.single().jsonObject
                faceStyle["colour"]!!.jsonObject["b"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
                faceStyle["layers"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Layer-F")
                // The coloured face is the +Z face of area 200, whatever its index is in the re-read shape.
                faceStyle["face"]!!.jsonPrimitive.int shouldBe 5
            }
        }

        "the two-part assembly keeps both colours and the placement through export and import".config(
            enabled = available,
        ) {
            val script = copyFixture("export-colour-assembly-two-parts.kstep.kts")
            val out = File(workDir, "assembly.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0

            val json = importJson(out)
            val parts = json["parts"]!!.jsonArray.map { it.jsonObject }
            val red = parts.single { it["productId"]!!.jsonPrimitive.content == "RED" }
            val green = parts.single { it["productId"]!!.jsonPrimitive.content == "GREEN" }
            red["colour"]!!.jsonObject["r"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            green["colour"]!!.jsonObject["g"]!!.jsonPrimitive.double shouldBe (0.6 plusOrMinus 1e-6)
            val box = json["totals"]!!.jsonObject["boundingBox"]!!.jsonObject
            box["max"]!!.jsonArray.map { it.jsonPrimitive.double } shouldBe listOf(40.0, 20.0, 30.0)
            json["totals"]!!.jsonObject["volume"]!!.jsonPrimitive.double shouldBe (7000.0 plusOrMinus 1e-6)
        }

        fun repoFile(relative: String): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, relative).isFile) dir = dir.parentFile
            return requireNotNull(
                dir,
            ) { "$relative not found above ${System.getProperty("user.dir")}" }.resolve(relative)
        }

        fun assertPartAndFace(
            part: JsonObject,
            height: Double,
        ) {
            part["volume"]!!.jsonPrimitive.double shouldBe (200.0 * height plusOrMinus 1e-6)
            val colour = part["colour"]!!.jsonObject
            colour["r"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            colour["g"]!!.jsonPrimitive.double shouldBe (0.5 plusOrMinus 1e-6)
            colour["b"]!!.jsonPrimitive.double shouldBe (0.0 plusOrMinus 1e-6)
            val faceStyle = part["faceStyles"]!!.jsonArray.single().jsonObject
            faceStyle["colour"]!!.jsonObject["b"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            faceStyle["colour"]!!.jsonObject["r"]!!.jsonPrimitive.double shouldBe (0.0 plusOrMinus 1e-6)
        }

        "a part colour together with a top face colour exports and comes back through kstep import".config(
            enabled = available,
        ) {
            for (height in listOf("30.0", "40.0")) {
                val script =
                    copyFixture(
                        "export-colour-part-and-face.kstep.kts",
                    ) { it.replace("val height = 30.0", "val height = $height") }
                val out = File(workDir, "part-and-face-$height.step").apply { delete() }
                val exported = runner.run("export", script.name, "--out", out.name)
                exported.exitCode shouldBe 0
                out.readText() shouldContain "OVER_RIDING_STYLED_ITEM"

                val part = importJson(out)["parts"]!!.jsonArray.single().jsonObject
                assertPartAndFace(part, height.toDouble())
                part["faceStyles"]!!
                    .jsonArray
                    .single()
                    .jsonObject["layers"]!!
                    .jsonArray
                    .map { it.jsonPrimitive.content } shouldBe listOf("Layer-F")
                part["faceStyles"]!!
                    .jsonArray
                    .single()
                    .jsonObject["face"]!!
                    .jsonPrimitive.int shouldBe 5
            }
        }

        "an assembly where only one part has a face colour keeps every part colour".config(enabled = available) {
            val script = copyFixture("export-colour-assembly-part-face.kstep.kts")
            val out = File(workDir, "assembly-part-face.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0

            val parts = importJson(out)["parts"]!!.jsonArray.map { it.jsonObject }
            val red = parts.single { it["productId"]!!.jsonPrimitive.content == "RED" }
            val green = parts.single { it["productId"]!!.jsonPrimitive.content == "GREEN" }
            red["colour"]!!.jsonObject["r"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            red["faceStyles"]!!.jsonArray.size shouldBe 1
            green["colour"]!!.jsonObject["g"]!!.jsonPrimitive.double shouldBe (0.6 plusOrMinus 1e-6)
            (green["faceStyles"]?.jsonArray?.size ?: 0) shouldBe 0
        }

        "the colours-and-layers example in docs/usage.adoc exports and reads back".config(enabled = available) {
            val doc = repoFile("docs/usage.adoc").readText()
            val section = doc.substring(doc.indexOf("[[colours-layers]]"))
            val start = section.indexOf("----\n", section.indexOf("[source,kotlin]")) + 5
            val block = section.substring(start, section.indexOf("----\n", start))
            // Drift guard: the fixture contains the documentation block verbatim.
            copyFixture("export-docs-colours-layers.kstep.kts").readText() shouldContain block

            val out = File(workDir, "docs-example.step").apply { delete() }
            runner.run("export", "export-docs-colours-layers.kstep.kts", "--out", out.name).exitCode shouldBe 0
            val part = importJson(out)["parts"]!!.jsonArray.single().jsonObject
            part["colour"]!!.jsonObject["r"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            part["layers"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Housing")
            val face = part["faceStyles"]!!.jsonArray.single().jsonObject
            face["colour"]!!.jsonObject["b"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            face["layers"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Top")
        }

        "an invalid colour exits non-zero with KSTEP-S-012 in text and JSON and writes no file".config(
            enabled = available,
        ) {
            val script =
                copyFixture("export-colour-layer-box.kstep.kts") {
                    it.replace("StepColour.rgb(1.0, 0.5, 0.0)", "StepColour.rgb(1.0, 1.5, 0.0)")
                }
            val out = File(workDir, "bad-colour.step").apply { delete() }
            val text = runner.run("export", script.name, "--out", out.name)
            (text.exitCode != 0) shouldBe true
            (text.stdout + text.stderr) shouldContain "KSTEP-S-012"
            (text.stdout + text.stderr) shouldNotContain "\tat "
            out.exists() shouldBe false
            val json = runner.run("export", script.name, "--out", out.name, "--output", "json")
            json.exitCode shouldBe 1
            Json
                .parseToJsonElement(json.stdout.trim())
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe
                "KSTEP-S-012"
        }

        "an invalid layer name and a second colour exit with KSTEP-S-013 and KSTEP-S-014 in JSON".config(
            enabled = available,
        ) {
            val badLayer = copyFixture("export-colour-layer-box.kstep.kts") { it.replace("\"Layer-A\"", "\"\"") }
            val a = runner.run("export", badLayer.name, "--out", "bad-layer.step", "--output", "json")
            a.exitCode shouldBe 1
            Json
                .parseToJsonElement(a.stdout.trim())
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe "KSTEP-S-013"

            val conflict =
                copyFixture("export-colour-layer-box.kstep.kts") {
                    it.replace("    shape(box,", "    style(box, colour = StepColours.RED)\n    shape(box,")
                }
            val b = runner.run("export", conflict.name, "--out", "conflict.step", "--output", "json")
            b.exitCode shouldBe 1
            Json
                .parseToJsonElement(b.stdout.trim())
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe "KSTEP-S-014"
        }

        "a face selection that matches nothing in styleFaces exits with KSTEP-S-011 and the candidates".config(
            enabled = available,
        ) {
            val script =
                copyFixture("export-colour-face-top.kstep.kts") {
                    it.replace("Faces.atMax(Direction3.Z)", "Faces.normal(Direction3(0.3, 1.0, 0.7))")
                }
            val result = runner.run("export", script.name, "--out", "no-face.step", "--output", "json")
            result.exitCode shouldBe 1
            val parsed = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            parsed["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-011"
            parsed["message"]!!.jsonPrimitive.content shouldContain "matched no face"
        }

        "style(...) for a part without a shape exits with KSTEP-S-015".config(enabled = available) {
            val script =
                copyFixture("export-colour-layer-box.kstep.kts") {
                    it.replace(
                        "    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.5, 0.0), layer = \"Layer-A\")",
                        "    style(box, colour = StepColours.RED)",
                    )
                }
            val result = runner.run("export", script.name, "--out", "no-shape.step", "--output", "json")
            result.exitCode shouldBe 1
            Json
                .parseToJsonElement(result.stdout.trim())
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe
                "KSTEP-S-015"
        }

        "a coloured part renders to a GLB with a COLOR_0 attribute, an uncoloured one without".config(
            enabled = available,
        ) {
            val coloured = copyFixture("export-colour-layer-box.kstep.kts")
            val plain = copyFixture("export-golden-box.kstep.kts")
            runner.run("render", coloured.name, "-f", "glb", "-o", "coloured.glb").exitCode shouldBe 0
            runner.run("render", plain.name, "-f", "glb", "-o", "plain.glb").exitCode shouldBe 0
            String(File(workDir, "coloured.glb").readBytes(), Charsets.ISO_8859_1) shouldContain "COLOR_0"
            String(File(workDir, "plain.glb").readBytes(), Charsets.ISO_8859_1) shouldNotContain "COLOR_0"
        }

        "a coloured part renders to an SVG that differs from the uncoloured rendering".config(enabled = available) {
            val coloured = copyFixture("export-colour-layer-box.kstep.kts")
            val plain = File(workDir, "uncoloured.kstep.kts")
            plain.writeText(
                coloured.readText().replace(", colour = StepColour.rgb(1.0, 0.5, 0.0), layer = \"Layer-A\"", ""),
            )
            runner.run("render", coloured.name, "-f", "svg", "-o", "coloured.svg").exitCode shouldBe 0
            runner.run("render", plain.name, "-f", "svg", "-o", "uncoloured.svg").exitCode shouldBe 0
            (File(workDir, "coloured.svg").readText() == File(workDir, "uncoloured.svg").readText()) shouldBe false
        }
    })
