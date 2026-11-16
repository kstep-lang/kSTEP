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
import kotlin.time.Duration.Companion.seconds

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
            failure("alpha", "KSTEP-S-012", "alpha") {
                it.replace("StepColour.rgb(0.0, 0.0, 1.0)", "StepColour.rgba(0.0, 0.0, 1.0, 1.5)")
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

        val subScript = "export-golden-subassembly-colour.kstep.kts"

        fun occurrenceColours(json: JsonObject): Map<Int, JsonElement> =
            json["occurrences"]!!.jsonArray.associate {
                val o = it.jsonObject
                o["boundingBox"]!!
                    .jsonObject["min"]!!
                    .jsonArray[0]
                    .jsonPrimitive.double
                    .toInt() to o["colour"]!!
            }

        // Welle Y (ADR-0044 amendment): translucent instance colours, export -> import round trip
        fun JsonElement.shouldBeRgba(
            r: Double,
            g: Double,
            b: Double,
            a: Double,
        ) {
            val c = jsonObject
            c["r"]!!.jsonPrimitive.double shouldBe (r plusOrMinus 1e-6)
            c["g"]!!.jsonPrimitive.double shouldBe (g plusOrMinus 1e-6)
            c["b"]!!.jsonPrimitive.double shouldBe (b plusOrMinus 1e-6)
            c["alpha"]!!.jsonPrimitive.double shouldBe (a plusOrMinus 1e-6)
        }

        val transScript = "export-golden-instance-transparency.kstep.kts"

        "a translucent instance colour is exported and read back with its alpha in JSON and text (Welle Y)".config(
            enabled = available,
            timeout = 120.seconds,
        ) {
            val script = copyFixture(transScript)
            val out = File(workDir, "trans.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            val json = importJson(out)
            json["parts"]!!.jsonArray.single().jsonObject["colour"] shouldBe JsonNull
            val comps = components(json)
            comps[0]["colour"] shouldBe JsonNull
            comps[1]["colour"]!!.shouldBeRgba(0.0, 0.0, 1.0, 0.5)
            val colours = occurrenceColours(json)
            colours.getValue(0) shouldBe JsonNull
            colours.getValue(50).shouldBeRgba(0.0, 0.0, 1.0, 0.5)
            json["totals"]!!.jsonObject["volume"]!!.jsonPrimitive.double shouldBe (12000.0 plusOrMinus 1e-6)
            json["presentation"]!!.jsonObject["instanceStylesIgnored"]!!.jsonPrimitive.int shouldBe 0
            runner.run("import", out.name).stdout shouldContain "colour (0, 0, 1) alpha 0.5"
        }

        "alpha 0.0, 192/255, 0.3 and 0.999999 survive the export and import round trip within 1e-6 (Welle Y, Y6)"
            .config(
                enabled = available,
                timeout = 180.seconds,
            ) {
                for ((i, alpha) in listOf(0.0, 0xC0 / 255.0, 0.3, 0.999999).withIndex()) {
                    val script =
                        copyFixture(transScript) {
                            it.replace("StepColour.rgba(0.0, 0.0, 1.0, 0.5)", "StepColour.rgba(0.0, 0.0, 1.0, $alpha)")
                        }
                    val out = File(workDir, "alpha-$i.step").apply { delete() }
                    runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
                    components(importJson(out))[1]["colour"]!!.shouldBeRgba(0.0, 0.0, 1.0, alpha)
                }
            }

        "a translucent colour on a sub-assembly occurrence is exported and read back with its alpha (ADR-0046)".config(
            enabled = available,
            timeout = 120.seconds,
        ) {
            val script =
                copyFixture(subScript) {
                    it.replace(
                        "styleInstance(u2, StepColour.rgb(0.0, 0.0, 1.0))",
                        "styleInstance(u2, StepColour.rgba(0.0, 0.0, 1.0, 0.5))",
                    )
                }
            val out = File(workDir, "sub-trans.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            val json = importJson(out)
            json["totals"]!!.jsonObject["volume"]!!.jsonPrimitive.double shouldBe (24000.0 plusOrMinus 1e-6)
            val colours = occurrenceColours(json)
            colours.getValue(0).shouldBeRgb(1.0, 0.0, 0.0) // own component u3 still beats the outer u2
            colours.getValue(20) shouldBe JsonNull
            colours.getValue(100).shouldBeRgb(1.0, 0.0, 0.0)
            colours.getValue(120).shouldBeRgba(0.0, 0.0, 1.0, 0.5) // u4 inside the translucent SUB occurrence
        }

        "the translucent export matches its golden byte for byte and the plain export is its byte prefix (Welle Y)"
            .config(enabled = available, timeout = 120.seconds) {
                fun export(
                    name: String,
                    transform: (String) -> String,
                ): String {
                    val script =
                        File(
                            workDir,
                            "$name.kstep.kts",
                        ).also { it.writeText(transform(resourceText(transScript))) }
                    val out = File(workDir, "$name.step").apply { delete() }
                    runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
                    return out.readText()
                }
                val full = export("trans-full") { it }
                full shouldBe resourceText("export-golden-instance-transparency.step")
                val plain = export("trans-plain") { it.replace(Regex("    styleInstance\\(.*\n"), "") }
                val opaque = export("trans-opaque") { it.replace("rgba(0.0, 0.0, 1.0, 0.5)", "rgb(0.0, 0.0, 1.0)") }

                fun prefixOf(text: String) = text.substring(0, text.lastIndexOf("ENDSEC;"))
                opaque shouldStartWith prefixOf(plain)
                full shouldStartWith prefixOf(plain)
                // two extra entities for the translucent colour, nothing else
                Regex("SURFACE_STYLE_TRANSPARENT\\(0\\.5\\)").findAll(full).count() shouldBe 1
                Regex("SURFACE_STYLE_RENDERING_WITH_PROPERTIES").findAll(full).count() shouldBe 1
                opaque.contains("SURFACE_STYLE_TRANSPARENT") shouldBe false
            }

        "an instance colour on a sub-assembly occurrence is exported and read back per occurrence (ADR-0046)".config(
            enabled = available,
        ) {
            val script = copyFixture(subScript)
            val out = File(workDir, "sub.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            val json = importJson(out)
            // hand calculation: four boxes of 6000 mm3 at x = 0, 20, 100, 120
            json["totals"]!!.jsonObject["occurrenceCount"]!!.jsonPrimitive.int shouldBe 4
            json["totals"]!!.jsonObject["volume"]!!.jsonPrimitive.double shouldBe (24000.0 plusOrMinus 1e-6)
            val colours = occurrenceColours(json)
            colours.keys shouldBe setOf(0, 20, 100, 120)
            colours.getValue(0).shouldBeRgb(1.0, 0.0, 0.0) // own component u3 beats the outer u2
            colours.getValue(20) shouldBe JsonNull // inside the first SUB, nothing colours u4
            colours.getValue(100).shouldBeRgb(1.0, 0.0, 0.0)
            colours.getValue(120).shouldBeRgb(0.0, 0.0, 1.0) // u4 inside the blue SUB occurrence
            val presentation = json["presentation"]!!.jsonObject
            presentation["coloredTargets"]!!.jsonPrimitive.int shouldBe 2
            presentation["instanceStylesIgnored"]!!.jsonPrimitive.int shouldBe 0
            json["parts"]!!.jsonArray.forEach { it.jsonObject["colour"] shouldBe JsonNull }
            val text = runner.run("import", out.name).stdout
            text shouldContain "colour (0, 0, 1)"
        }

        "the sub-assembly export matches its golden byte for byte, in three prefix stages (ADR-0046)".config(
            enabled = available,
        ) {
            fun export(
                name: String,
                transform: (String) -> String,
            ): String {
                val script = File(workDir, "$name.kstep.kts").also { it.writeText(transform(resourceText(subScript))) }
                val out = File(workDir, "$name.step").apply { delete() }
                runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
                return out.readText()
            }
            val full = export("sub-full") { it }
            full shouldBe resourceText("export-golden-subassembly-colour.step")
            val partsOnly = export("sub-parts") { it.replace(Regex("    styleInstance\\(u2.*\n"), "") }
            val plain = export("sub-plain") { it.replace(Regex("    styleInstance\\(.*\n"), "") }

            fun prefixOf(text: String) = text.substring(0, text.lastIndexOf("ENDSEC;"))
            partsOnly shouldStartWith prefixOf(plain)
            full shouldStartWith prefixOf(partsOnly)
            // the sub-assembly block has its own presentation representation, behind the block of the parts
            val addedBySub = full.substring(prefixOf(partsOnly).length, full.lastIndexOf("ENDSEC;"))
            Regex("MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION").findAll(addedBySub).count() shouldBe 1
            Regex("MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION").findAll(full).count() shouldBe 2
        }

        "an instance colour on a sub-assembly without any shape below it is refused with KSTEP-S-008 and writes no file"
            .config(enabled = available) {
                val script =
                    copyFixture(subScript) {
                        it
                            .replace(
                                "val box = part(\"BOX-001\", \"Box\")",
                                "val box = part(\"BOX-001\", \"Box\")\nval hollow = part(\"HOL-001\", \"Hollow\")\nval empty = part(\"EMP-001\", \"Empty\")",
                            ).replace(
                                "val u4 = usage(\"NAUO-4\", sub, box)",
                                "val u4 = usage(\"NAUO-4\", sub, box)\nval u5 = usage(\"NAUO-5\", asm, hollow)\nval u6 = usage(\"NAUO-6\", hollow, empty)",
                            ).replace("roots(u1, u2, u3, u4)", "roots(u1, u2, u3, u4, u5, u6)")
                            .replace(
                                "    styleInstance(u3, StepColour.rgb(1.0, 0.0, 0.0))\n",
                                "    styleInstance(u5, StepColour.rgb(1.0, 0.0, 0.0))\n",
                            )
                    }
                val out = File(workDir, "hollow.step").apply { delete() }
                val text = runner.run("export", script.name, "--out", out.name)
                (text.exitCode != 0) shouldBe true
                (text.stdout + text.stderr) shouldContain "NAUO-5"
                out.exists() shouldBe false
                val json = runner.run("export", script.name, "--out", out.name, "--output", "json")
                json.exitCode shouldBe 1
                errorCode(json) shouldBe "KSTEP-S-008"
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
