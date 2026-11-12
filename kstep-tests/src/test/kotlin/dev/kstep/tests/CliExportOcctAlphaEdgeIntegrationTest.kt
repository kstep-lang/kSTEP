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
 * Subprocess-level coverage of transparency and edge colours in `kstep export` and `kstep import` (ADR-0042). The class
 * name carries `Occt` so the CI skip gate (which inspects `TEST-*Occt*.xml`) covers these tests.
 */
class CliExportOcctAlphaEdgeIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-alpha-edge-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun resourceText(name: String): String =
            requireNotNull(CliExportOcctAlphaEdgeIntegrationTest::class.java.getResourceAsStream("/$name")) { name }
                .use { String(it.readBytes(), Charsets.UTF_8) }

        fun copyFixture(
            name: String,
            transform: (String) -> String = { it },
        ): File = File(workDir, name).also { it.writeText(transform(resourceText(name))) }

        fun importJson(step: File): JsonObject {
            val result = runner.run("import", step.name, "--output", "json")
            result.exitCode shouldBe 0
            return Json.parseToJsonElement(result.stdout.trim()).jsonObject
        }

        fun errorCode(result: CliInvocationResult): String =
            Json
                .parseToJsonElement(result.stdout.trim())
                .jsonObject["code"]!!
                .jsonPrimitive.content

        fun repoFile(relative: String): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null && !File(dir, relative).isFile) dir = dir.parentFile
            return requireNotNull(
                dir,
            ) { "$relative not found above ${System.getProperty("user.dir")}" }.resolve(relative)
        }

        "a translucent part and a translucent top face come back with their alpha, in JSON and in text".config(
            enabled = available,
        ) {
            val script = copyFixture("export-alpha-box.kstep.kts")
            val out = File(workDir, "alpha-box.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0

            val part = importJson(out)["parts"]!!.jsonArray.single().jsonObject
            part["volume"]!!.jsonPrimitive.double shouldBe (6000.0 plusOrMinus 1e-6)
            val colour = part["colour"]!!.jsonObject
            colour["r"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            colour["g"]!!.jsonPrimitive.double shouldBe (0.5 plusOrMinus 1e-6)
            colour["alpha"]!!.jsonPrimitive.double shouldBe (0.5 plusOrMinus 1e-6)
            val face =
                part["faceStyles"]!!
                    .jsonArray
                    .single()
                    .jsonObject["colour"]!!
                    .jsonObject
            face["b"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            face["alpha"]!!.jsonPrimitive.double shouldBe (0.25 plusOrMinus 1e-6)
            part["edgeStyles"]!!.jsonArray.size shouldBe 0

            val text = runner.run("import", out.name)
            text.exitCode shouldBe 0
            text.stdout shouldContain "colour (1, 0.5, 0) alpha 0.5"
            text.stdout shouldContain "face 5  colour (0, 0, 1) alpha 0.25"
        }

        "the four top edges come back as four edge styles, in JSON and in text".config(enabled = available) {
            val script = copyFixture("export-edge-colour-top.kstep.kts")
            val out = File(workDir, "edge-colour-top.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            out.readText() shouldContain "CURVE_STYLE"

            val json = importJson(out)
            val part = json["parts"]!!.jsonArray.single().jsonObject
            part["volume"]!!.jsonPrimitive.double shouldBe (6000.0 plusOrMinus 1e-6)
            part["colour"]!!.toString() shouldBe "null"
            val edges = part["edgeStyles"]!!.jsonArray.map { it.jsonObject }
            edges.size shouldBe 4
            edges.map { it["edge"]!!.jsonPrimitive.int }.toSet().size shouldBe 4
            for (e in edges) {
                val c = e["colour"]!!.jsonObject
                c["r"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
                c["alpha"]!!.jsonPrimitive.double shouldBe 1.0
            }
            json["presentation"]!!.jsonObject["unattached"]!!.jsonPrimitive.int shouldBe 0
            json["presentation"]!!.jsonObject["coloredTargets"]!!.jsonPrimitive.int shouldBe 4

            val text = runner.run("import", out.name)
            text.exitCode shouldBe 0
            text.stdout.lines().count { it.trim().startsWith("edge ") && it.contains("colour (1, 0, 0)") } shouldBe 4
            text.stdout shouldNotContain "alpha"
        }

        "alpha 1.0 through rgba is byte-identical to the pre-ADR-0042 golden file".config(enabled = available) {
            val script = copyFixture("export-golden-part-colour-rgba1.kstep.kts")
            val out = File(workDir, "golden-part-colour-rgba1.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            out.readText() shouldBe resourceText("export-golden-part-colour.step")
        }

        "the alpha and edge example in docs/usage.adoc exports and reads back".config(enabled = available) {
            val doc = repoFile("docs/usage.adoc").readText()
            val section = doc.substring(doc.indexOf("[[alpha-edge-colours]]"))
            val start = section.indexOf("----\n", section.indexOf("[source,kotlin]")) + 5
            val block = section.substring(start, section.indexOf("----\n", start))
            // Drift guard: the fixture contains the documentation block verbatim.
            copyFixture("export-docs-alpha-edges.kstep.kts").readText() shouldContain block

            val out = File(workDir, "docs-alpha-edges.step").apply { delete() }
            runner.run("export", "export-docs-alpha-edges.kstep.kts", "--out", out.name).exitCode shouldBe 0
            val part = importJson(out)["parts"]!!.jsonArray.single().jsonObject
            part["colour"]!!.jsonObject["alpha"]!!.jsonPrimitive.double shouldBe (0.4 plusOrMinus 1e-6)
            part["colour"]!!.jsonObject["b"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            val face =
                part["faceStyles"]!!
                    .jsonArray
                    .single()
                    .jsonObject["colour"]!!
                    .jsonObject
            face["r"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
            face["alpha"]!!.jsonPrimitive.double shouldBe (0xC0 / 255.0 plusOrMinus 1e-6)
            part["edgeStyles"]!!.jsonArray.size shouldBe 4
        }

        "an alpha outside 0..1 or not a number exits with KSTEP-S-012 in text and JSON and writes no file".config(
            enabled = available,
        ) {
            for (bad in listOf("Double.NaN", "-0.1", "1.5")) {
                val script =
                    copyFixture("export-alpha-box.kstep.kts") {
                        it.replace("StepColour.rgba(1.0, 0.5, 0.0, 0.5)", "StepColour.rgba(1.0, 0.5, 0.0, $bad)")
                    }
                val out = File(workDir, "bad-alpha.step").apply { delete() }
                val text = runner.run("export", script.name, "--out", out.name)
                (text.exitCode != 0) shouldBe true
                (text.stdout + text.stderr) shouldContain "KSTEP-S-012"
                (text.stdout + text.stderr) shouldContain "alpha"
                (text.stdout + text.stderr) shouldNotContain "\tat "
                out.exists() shouldBe false
                val json = runner.run("export", script.name, "--out", out.name, "--output", "json")
                json.exitCode shouldBe 1
                errorCode(json) shouldBe "KSTEP-S-012"
            }
        }

        "an edge selection that matches nothing, a wrong count, a second colour and a translucent edge colour fail"
            .config(
                enabled = available,
            ) {
                fun codeFor(
                    replacement: String,
                    extra: String = "",
                ): Pair<CliInvocationResult, CliInvocationResult> {
                    val script =
                        copyFixture("export-edge-colour-top.kstep.kts") {
                            it.replace(
                                "    styleEdges(box, Edges.atMax(Direction3.Z), StepColours.RED, expected = 4)",
                                "    $replacement\n$extra",
                            )
                        }
                    val out = File(workDir, "edge-error.step").apply { delete() }
                    val text = runner.run("export", script.name, "--out", out.name)
                    out.exists() shouldBe false
                    return text to runner.run("export", script.name, "--out", out.name, "--output", "json")
                }

                val (noText, noJson) =
                    codeFor("styleEdges(box, Edges.seams(), StepColours.RED)")
                (noText.stdout + noText.stderr) shouldContain "KSTEP-S-011"
                noJson.exitCode shouldBe 1
                errorCode(noJson) shouldBe "KSTEP-S-011"

                val (countText, countJson) =
                    codeFor(
                        "styleEdges(box, Edges.atMax(Direction3.Z), StepColours.RED, expected = 3)",
                    )
                (countText.stdout + countText.stderr) shouldContain "KSTEP-S-011"
                errorCode(countJson) shouldBe "KSTEP-S-011"

                val (twiceText, twiceJson) =
                    codeFor(
                        "styleEdges(box, Edges.atMax(Direction3.Z), StepColours.RED, expected = 4)",
                        "    styleEdges(box, Edges.atMax(Direction3.Z), StepColours.BLUE, expected = 4)",
                    )
                (twiceText.stdout + twiceText.stderr) shouldContain "KSTEP-S-014"
                errorCode(twiceJson) shouldBe "KSTEP-S-014"

                val (glassText, glassJson) =
                    codeFor(
                        "styleEdges(box, Edges.atMax(Direction3.Z), StepColour.rgba(1.0, 0.0, 0.0, 0.5), expected = 4)",
                    )
                (glassText.stdout + glassText.stderr) shouldContain "KSTEP-S-012"
                (glassText.stdout + glassText.stderr) shouldContain "transparent"
                errorCode(glassJson) shouldBe "KSTEP-S-012"
            }

        "a foreign file with edge colours reports them as edge styles instead of unattached".config(
            enabled = available,
        ) {
            val step = File(workDir, "foreign-edge.step")
            step.writeText(resourceText("foreign/import-foreign-edge-colours.step"))
            val json = importJson(step)
            val part = json["parts"]!!.jsonArray.single().jsonObject
            part["edgeStyles"]!!.jsonArray.size shouldBe 4
            json["presentation"]!!.jsonObject["unattached"]!!.jsonPrimitive.int shouldBe 0
        }

        "a foreign file with transparency on the part and a face reports the alpha".config(enabled = available) {
            val step = File(workDir, "foreign-alpha.step")
            step.writeText(resourceText("foreign/import-foreign-alpha.step"))
            val part = importJson(step)["parts"]!!.jsonArray.single().jsonObject
            part["colour"]!!.jsonObject["alpha"]!!.jsonPrimitive.double shouldBe (0.5 plusOrMinus 1e-6)
            part["faceStyles"]!!
                .jsonArray
                .single()
                .jsonObject["colour"]!!
                .jsonObject["alpha"]!!
                .jsonPrimitive.double shouldBe (0.25 plusOrMinus 1e-6)
        }

        "a translucent part renders like an opaque one (the preview ignores the opacity)".config(enabled = available) {
            val script = copyFixture("export-alpha-box.kstep.kts")
            runner.run("render", script.name, "-f", "svg", "-o", "alpha.svg").exitCode shouldBe 0
            File(workDir, "alpha.svg").readText() shouldContain "<svg"
        }
    })
