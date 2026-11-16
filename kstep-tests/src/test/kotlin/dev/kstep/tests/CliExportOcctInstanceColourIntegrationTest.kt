package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Subprocess-level coverage of instance colours in `kstep export` and `kstep import` (ADR-0044). The class name carries
 * `Occt` so the CI skip gate (which inspects `TEST-*Occt*.xml`) covers these tests.
 */
class CliExportOcctInstanceColourIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-instance-colour-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun resourceText(name: String): String =
            requireNotNull(
                CliExportOcctInstanceColourIntegrationTest::class.java.getResourceAsStream("/$name"),
            ) { name }
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

        fun components(json: JsonObject): List<JsonObject> =
            json["assemblies"]!!
                .jsonArray
                .single()
                .jsonObject["components"]!!
                .jsonArray
                .map { it.jsonObject }

        fun JsonElement.shouldBeRgb(
            r: Double,
            g: Double,
            b: Double,
        ) {
            val c = jsonObject
            c["r"]!!.jsonPrimitive.double shouldBe (r plusOrMinus 1e-6)
            c["g"]!!.jsonPrimitive.double shouldBe (g plusOrMinus 1e-6)
            c["b"]!!.jsonPrimitive.double shouldBe (b plusOrMinus 1e-6)
            c["alpha"]!!.jsonPrimitive.double shouldBe (1.0 plusOrMinus 1e-6)
        }

        val boxes = "export-instance-colour-boxes.kstep.kts"

        "one part used twice, red once and blue once, comes back as two instance colours in JSON and text".config(
            enabled = available,
        ) {
            val script = copyFixture(boxes)
            val out = File(workDir, "boxes.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0

            val json = importJson(out)
            val parts = json["parts"]!!.jsonArray
            parts.size shouldBe 1
            parts[0].jsonObject["volume"]!!.jsonPrimitive.double shouldBe (6000.0 plusOrMinus 1e-6)
            parts[0].jsonObject["colour"] shouldBe JsonNull
            val comps = components(json)
            comps[0]["colour"]!!.shouldBeRgb(1.0, 0.0, 0.0)
            comps[1]["colour"]!!.shouldBeRgb(0.0, 0.0, 1.0)
            val totals = json["totals"]!!.jsonObject
            totals["occurrenceCount"]!!.jsonPrimitive.int shouldBe 2
            totals["volume"]!!.jsonPrimitive.double shouldBe (12000.0 plusOrMinus 1e-6)
            val presentation = json["presentation"]!!.jsonObject
            presentation["coloredTargets"]!!.jsonPrimitive.int shouldBe 2
            presentation["instanceStylesIgnored"]!!.jsonPrimitive.int shouldBe 0
            presentation["unattached"]!!.jsonPrimitive.int shouldBe 0
            // the second occurrence sits 50 mm to the right of the first
            val boxesOfOccurrences =
                json["occurrences"]!!.jsonArray.map { it.jsonObject["boundingBox"]!!.jsonObject }
            val xs = boxesOfOccurrences.map { it["min"]!!.jsonArray[0].jsonPrimitive.double }.sorted()
            (xs[1] - xs[0]) shouldBe (50.0 plusOrMinus 1e-6)

            val text = runner.run("import", out.name)
            text.exitCode shouldBe 0
            text.stdout shouldContain "NAUO-1 -> Box [BOX-001]  at t=(0,0,0)  colour (1, 0, 0)"
            text.stdout shouldContain "NAUO-2 -> Box [BOX-001]  at t=(50,0,0)  colour (0, 0, 1)"
        }

        "an orange part and a blue instance stay separate: the part is orange, component 2 blue, component 1 none"
            .config(
                enabled = available,
            ) {
                val script =
                    copyFixture(boxes) {
                        it
                            .replace(
                                "shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))",
                                "shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.5, 0.0))",
                            ).replace("    styleInstance(u1, StepColour.rgb(1.0, 0.0, 0.0))\n", "")
                    }
                val out = File(workDir, "orange-blue.step").apply { delete() }
                runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
                val json = importJson(out)
                json["parts"]!!.jsonArray[0].jsonObject["colour"]!!.shouldBeRgb(1.0, 0.5, 0.0)
                val comps = components(json)
                comps[0]["colour"] shouldBe JsonNull
                comps[1]["colour"]!!.shouldBeRgb(0.0, 0.0, 1.0)
            }

        "a colour that is not exactly representable (192/255, 0.3, 0.25) survives the round trip within 1e-6".config(
            enabled = available,
        ) {
            val script =
                copyFixture(boxes) {
                    it.replace("StepColour.rgb(1.0, 0.0, 0.0)", "StepColour.rgb(0xC0 / 255.0, 0.3, 0.25)")
                }
            val out = File(workDir, "odd-colour.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            components(importJson(out))[0]["colour"]!!.shouldBeRgb(0xC0 / 255.0, 0.3, 0.25)
        }

        "without an instance colour the import output has colour null and no colour in the text report".config(
            enabled = available,
        ) {
            val script = copyFixture(boxes) { it.replace(Regex("    styleInstance\\(.*\\n"), "") }
            val out = File(workDir, "plain-boxes.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            components(importJson(out)).forEach { it["colour"] shouldBe JsonNull }
            val text = runner.run("import", out.name)
            text.stdout shouldNotContain "colour ("
        }

        "a file without instance colours is a byte prefix of the same file with them".config(enabled = available) {
            val plain = File(workDir, "golden-plain.step").apply { delete() }
            runner
                .run("export", copyFixture("export-golden-placed-assembly.kstep.kts").name, "--out", plain.name)
                .exitCode shouldBe 0
            plain.readText() shouldBe resourceText("export-golden-placed-assembly.step")

            val coloured = File(workDir, "golden-coloured.step").apply { delete() }
            runner
                .run("export", copyFixture("export-instance-colour-two.kstep.kts").name, "--out", coloured.name)
                .exitCode shouldBe 0
            val plainText = plain.readText()
            val prefix = plainText.substring(0, plainText.lastIndexOf("ENDSEC;"))
            val colouredText = coloured.readText()
            colouredText shouldStartWith prefix
            val appended = colouredText.substring(prefix.length, colouredText.lastIndexOf("ENDSEC;"))
            val names =
                appended.lines().filter { it.isNotBlank() }.map {
                    Regex(
                        "^#\\d+=([A-Z_0-9]+)\\(",
                    ).find(it)!!.groupValues[1]
                }
            names.toSet() shouldContainAll listOf("COLOUR_RGB", "PRESENTATION_STYLE_BY_CONTEXT", "STYLED_ITEM")
            val allowed =
                setOf(
                    "MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION",
                    "STYLED_ITEM",
                    "PRESENTATION_STYLE_BY_CONTEXT",
                    "SURFACE_STYLE_USAGE",
                    "SURFACE_SIDE_STYLE",
                    "SURFACE_STYLE_FILL_AREA",
                    "FILL_AREA_STYLE",
                    "FILL_AREA_STYLE_COLOUR",
                    "COLOUR_RGB",
                    "SHAPE_REPRESENTATION",
                    "SHAPE_DEFINITION_REPRESENTATION",
                )
            (names.toSet() - allowed) shouldBe emptySet()
            // two instances, ten entities each, plus one presentation representation
            names.size shouldBe 21

            val json = importJson(coloured)
            val comps = components(json)
            comps.map { it["colour"] }.map { it != JsonNull } shouldBe listOf(false, false, true, true)
            comps[2]["colour"]!!.shouldBeRgb(1.0, 0.0, 0.0)
            comps[3]["colour"]!!.shouldBeRgb(0.0, 0.0, 1.0)
        }

        "the existing unstyled and styled goldens stay byte-identical".config(enabled = available) {
            for (n in listOf("box", "multi-solid", "assembly", "placed-assembly", "part-colour", "face-colour")) {
                val script = copyFixture("export-golden-$n.kstep.kts")
                val out = File(workDir, "golden-$n.step").apply { delete() }
                runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
                out.readText() shouldBe resourceText("export-golden-$n.step")
            }
            val script = copyFixture("export-golden-part-colour-rgba1.kstep.kts")
            val out = File(workDir, "golden-rgba1.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            out.readText() shouldBe resourceText("export-golden-part-colour.step")
        }

        "an instance colour with an error exits with the right code in text and JSON and writes no file".config(
            enabled = available,
        ) {
            fun failure(
                name: String,
                code: String,
                mention: String,
                codeInText: Boolean = true,
                transform: (String) -> String,
            ) {
                val script = copyFixture(boxes, transform)
                val out = File(workDir, "$name.step").apply { delete() }
                val text = runner.run("export", script.name, "--out", out.name)
                (text.exitCode != 0) shouldBe true
                // a script error names its code in the text; a failed geometry merge reports it in JSON only
                if (codeInText) (text.stdout + text.stderr) shouldContain code
                (text.stdout + text.stderr) shouldContain mention
                (text.stdout + text.stderr) shouldNotContain "\tat "
                out.exists() shouldBe false
                val json = runner.run("export", script.name, "--out", out.name, "--output", "json")
                json.exitCode shouldBe 1
                errorCode(json) shouldBe code
                out.exists() shouldBe false
            }
            failure("alpha", "KSTEP-S-012", "opaque") {
                it.replace("StepColour.rgb(0.0, 0.0, 1.0)", "StepColour.rgba(0.0, 0.0, 1.0, 0.5)")
            }
            failure("twice", "KSTEP-S-014", "NAUO-1") {
                it.replace("styleInstance(u2,", "styleInstance(u1,")
            }
            failure("not-a-root", "KSTEP-S-008", "NAUO-2", codeInText = false) {
                it
                    .replace(
                        "roots(u1, u2)",
                        "roots(u1)",
                    ).replace("    place(u2, translation = vec(50.0, 0.0, 0.0))\n", "")
            }
            failure("no-shape", "KSTEP-S-008", "NAUO-1", codeInText = false) {
                it
                    .replace("    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))\n", "")
                    .replace(
                        "    place(u1, translation = vec(0.0, 0.0, 0.0))\n    place(u2, translation = vec(50.0, 0.0, 0.0))\n",
                        "",
                    ).replace(
                        "val box = part(\"BOX-001\", \"Box\")",
                        "val box = part(\"BOX-001\", \"Box\")\nval other = part(\"OTH-001\", \"Other\")",
                    ).replace(
                        "roots(u1, u2)",
                        "roots(u1, u2, usage(\"NAUO-O\", assembly, other))\n    shape(other, OcctKernel.makeBox(1.0, 1.0, 1.0))",
                    )
            }
        }

        "an instance colour on a sub-assembly occurrence is refused".config(enabled = available) {
            val script =
                copyFixture(boxes) {
                    it
                        .replace(
                            "val u1 = usage(\"NAUO-1\", assembly, box)",
                            "val sub = part(\"SUB-001\", \"Sub\")\nval top = part(\"TOP-001\", \"Top\")\n" +
                                "val u0 = usage(\"NAUO-0\", top, sub)\nval u1 = usage(\"NAUO-1\", sub, box)",
                        ).replace("val u2 = usage(\"NAUO-2\", assembly, box)", "val u2 = usage(\"NAUO-2\", sub, box)")
                        .replace("roots(u1, u2)", "roots(u0, u1, u2)")
                        .replace(
                            "    styleInstance(u1, StepColour.rgb(1.0, 0.0, 0.0))\n",
                            "    styleInstance(u0, StepColour.rgb(1.0, 0.0, 0.0))\n",
                        ).replace("    styleInstance(u2, StepColour.rgb(0.0, 0.0, 1.0))\n", "")
                }
            val out = File(workDir, "sub.step").apply { delete() }
            val r = runner.run("export", script.name, "--out", out.name)
            (r.exitCode != 0) shouldBe true
            (r.stdout + r.stderr) shouldContain "sub-assembly"
            out.exists() shouldBe false
        }

        "kstep render draws the instance colours (ADR-0045)".config(enabled = available) {
            val script = copyFixture(boxes)
            runner.run("render", script.name, "-f", "svg", "-o", "boxes.svg").exitCode shouldBe 0
            val svg = File(workDir, "boxes.svg").readText()
            svg shouldContain "<svg"
            val fills = Regex("fill=\"#([0-9a-f]{6})\"").findAll(svg).map { it.groupValues[1] }.toSet()
            fills.any { it.substring(2) == "0000" && it.substring(0, 2) != "00" } shouldBe true
            fills.any { it.substring(0, 4) == "0000" && it.substring(4) != "00" } shouldBe true
        }

        "the effective colour per occurrence round-trips: occurrence 1 red, occurrence 2 blue (ADR-0045)".config(
            enabled = available,
        ) {
            val script = copyFixture(boxes)
            val out = File(workDir, "effective.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            val json = importJson(out)
            val occurrences = json["occurrences"]!!.jsonArray.map { it.jsonObject }
            occurrences.size shouldBe 2
            occurrences[0]["colour"]!!.shouldBeRgb(1.0, 0.0, 0.0)
            occurrences[1]["colour"]!!.shouldBeRgb(0.0, 0.0, 1.0)
            // hand calculation: 2 x 6000 mm3 over two occurrences, the second 50 mm to the right
            json["totals"]!!.jsonObject["volume"]!!.jsonPrimitive.double shouldBe (12000.0 plusOrMinus 1e-6)
            val text = runner.run("import", out.name).stdout
            text shouldContain "bbox"
            text shouldContain "colour (1, 0, 0)"
        }

        "a file without any colour has occurrences[].colour null and an unchanged occurrence line".config(
            enabled = available,
        ) {
            val script = copyFixture(boxes) { it.replace(Regex("    styleInstance\\(.*\n"), "") }
            val out = File(workDir, "effective-plain.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            importJson(out)["occurrences"]!!.jsonArray.forEach { it.jsonObject["colour"] shouldBe JsonNull }
            val lines =
                runner
                    .run("import", out.name)
                    .stdout
                    .lines()
                    .filter { it.startsWith("  ") && "bbox" in it }
            lines.forEach { it shouldNotContain "colour" }
        }

        "the export fails predictably when the native bridge is unavailable".config(enabled = !available) {
            (OcctKernel.availability() is OcctAvailability.Available) shouldBe false
        }
    })
