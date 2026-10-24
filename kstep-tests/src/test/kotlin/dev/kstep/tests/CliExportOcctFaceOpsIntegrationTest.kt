package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.PI

/**
 * Subprocess-level coverage of Welle Q (ADR-0037): the example scripts go through `kstep export --validate` and
 * `kstep import --output json`, and the numbers are the hand-computed ones. The class name carries `Occt` so that the
 * CI skip guard inspects these tests.
 */
class CliExportOcctFaceOpsIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-face-ops-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            requireNotNull(CliExportOcctFaceOpsIntegrationTest::class.java.getResourceAsStream("/$name")) {
                "test resource /$name not found on classpath"
            }.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        class Expected(
            val script: String,
            val volume: Double,
            val faces: Int,
            val min: List<Double>,
            val max: List<Double>,
        )

        val cases =
            listOf(
                Expected("export-shell-box", 7152.0, 11, listOf(0.0, 0.0, 0.0), listOf(40.0, 30.0, 20.0)),
                Expected("export-shell-box-outward", 8912.0, 11, listOf(-2.0, -2.0, -2.0), listOf(42.0, 32.0, 20.0)),
                Expected("export-shell-cylinder", 461.0 * PI, 5, listOf(-10.0, -10.0, 0.0), listOf(10.0, 10.0, 20.0)),
                Expected("export-shell-feature-rebuild", 9792.0, 11, listOf(0.0, 0.0, 0.0), listOf(40.0, 30.0, 30.0)),
                Expected("export-pocket-rect", 23500.0, 11, listOf(0.0, 0.0, 0.0), listOf(40.0, 30.0, 20.0)),
                Expected(
                    "export-pocket-hole-blind",
                    24000.0 - 96.0 * PI,
                    8,
                    listOf(0.0, 0.0, 0.0),
                    listOf(40.0, 30.0, 20.0),
                ),
                Expected(
                    "export-pocket-hole-through",
                    24000.0 - 320.0 * PI,
                    7,
                    listOf(0.0, 0.0, 0.0),
                    listOf(40.0, 30.0, 20.0),
                ),
                Expected("export-pocket-feature-rebuild", 35500.0, 11, listOf(0.0, 0.0, 0.0), listOf(40.0, 30.0, 30.0)),
                Expected("export-draft-box", 21631.962928, 6, listOf(0.0, 0.0, 0.0), listOf(40.0, 30.0, 20.0)),
                Expected("export-thicken-face", 26400.0, 6, listOf(0.0, 0.0, 0.0), listOf(40.0, 30.0, 22.0)),
                Expected(
                    "export-nearest-fillet",
                    6000.0 - 2.0 * (4.0 - PI) * 30.0,
                    8,
                    listOf(0.0, 0.0, 0.0),
                    listOf(10.0, 20.0, 30.0),
                ),
            )

        cases.forEach { c ->
            "kstep export --validate of ${c.script} re-imports with the hand-computed volume, faces and box"
                .config(enabled = available) {
                    val script = copyFixture("${c.script}.kstep.kts")
                    val out = File(workDir, "${c.script}.step").apply { delete() }
                    val export = runner.run("export", script.name, "--out", out.name, "--validate")
                    export.exitCode shouldBe 0
                    export.stdout shouldContain "Exported 1 root(s) with 1 geometric shape(s)"
                    Regex("MANIFOLD_SOLID_BREP\\(").findAll(out.readText()).count() shouldBe 1
                    val imported = runner.run("import", out.name, "--output", "json")
                    imported.exitCode shouldBe 0
                    val part =
                        Json
                            .parseToJsonElement(imported.stdout.trim())
                            .jsonObject["parts"]!!
                            .jsonArray
                            .single()
                            .jsonObject
                    part["volume"]!!.jsonPrimitive.double shouldBe (c.volume plusOrMinus 1e-2)
                    part["topology"]!!.jsonObject["faces"]!!.jsonPrimitive.int shouldBe c.faces
                    part["topology"]!!.jsonObject["solids"]!!.jsonPrimitive.int shouldBe 1
                    val box = part["boundingBox"]!!.jsonObject
                    box["min"]!!.jsonArray.map { it.jsonPrimitive.double }.zip(c.min).forEach { (a, e) ->
                        a shouldBe (e plusOrMinus 1e-6)
                    }
                    box["max"]!!.jsonArray.map { it.jsonPrimitive.double }.zip(c.max).forEach { (a, e) ->
                        a shouldBe (e plusOrMinus 1e-6)
                    }
                }
        }

        "a face selection that matches nothing exits non-zero with KSTEP-S-011, the candidate faces and no stack trace"
            .config(enabled = available) {
                val script = copyFixture("hello-face-selection-empty.kstep.kts")
                val out = File(workDir, "face-empty.step").apply { delete() }
                val result = runner.run("export", script.name, "--out", out.name)
                (result.exitCode != 0) shouldBe true
                val output = result.stdout + result.stderr
                output shouldContain "KSTEP-S-011"
                output shouldContain "matched no face"
                output shouldContain "#0 PLANE"
                output shouldNotContain "\tat "
                out.exists() shouldBe false
                val json = runner.run("export", script.name, "--out", out.name, "--output", "json")
                json.exitCode shouldBe 1
                val parsed = Json.parseToJsonElement(json.stdout.trim()).jsonObject
                parsed["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-011"
            }

        "a shell that is too thick exits non-zero with KSTEP-S-003 naming the thickness and the limit".config(
            enabled = available,
        ) {
            val script = copyFixture("hello-shell-invalid.kstep.kts")
            val out = File(workDir, "shell-invalid.step").apply { delete() }
            val result = runner.run("export", script.name, "--out", out.name, "--output", "json")
            result.exitCode shouldBe 1
            val parsed = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            parsed["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-003"
            parsed["message"]!!.jsonPrimitive.content shouldContain "thickness 12.0"
            parsed["message"]!!.jsonPrimitive.content shouldContain "half the smallest extent"
            out.exists() shouldBe false
        }

        "a pocket whose face selector matches two faces exits non-zero with KSTEP-S-011 and the candidates".config(
            enabled = available,
        ) {
            val script = copyFixture("hello-pocket-ambiguous.kstep.kts")
            val out = File(workDir, "pocket-ambiguous.step").apply { delete() }
            val result = runner.run("export", script.name, "--out", out.name)
            (result.exitCode != 0) shouldBe true
            val output = result.stdout + result.stderr
            output shouldContain "KSTEP-S-011"
            output shouldContain "#"
            output shouldNotContain "\tat "
            out.exists() shouldBe false
        }

        "a draft OCCT cannot build exits non-zero with KSTEP-S-003 naming the draft status".config(
            enabled = available,
        ) {
            val script = copyFixture("hello-draft-invalid.kstep.kts")
            val out = File(workDir, "draft-invalid.step").apply { delete() }
            val result = runner.run("export", script.name, "--out", out.name, "--output", "json")
            result.exitCode shouldBe 1
            val parsed = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            parsed["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-003"
            parsed["message"]!!.jsonPrimitive.content shouldContain "draft"
            out.exists() shouldBe false
        }

        "an ambiguous nearest edge with expected = 1 reports KSTEP-S-011 and two candidates".config(
            enabled = available,
        ) {
            val script = copyFixture("hello-nearest-ambiguous.kstep.kts")
            val result = runner.run("export", script.name, "--out", "nearest-ambiguous.step")
            (result.exitCode != 0) shouldBe true
            val output = result.stdout + result.stderr
            output shouldContain "KSTEP-S-011"
            output shouldContain "is ambiguous"
            output shouldContain "matched 2"
            output shouldNotContain "\tat "
        }

        "no generated artefact is left in src/test/resources" {
            val strays =
                File("src/test/resources")
                    .listFiles()
                    .orEmpty()
                    .filter {
                        it.extension in setOf("step", "svg", "glb", "stp") &&
                            it.name.contains("shell|nearest|face-selection".toRegex())
                    }.map { it.name }
            strays shouldBe emptyList()
        }

        val freecad: File? =
            listOfNotNull(System.getenv("FREECADCMD"), "/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd")
                .map(::File)
                .firstOrNull { it.isFile && it.canExecute() }

        "FreeCAD reads the hollowed solids with the hand-computed volumes (skipped when FreeCAD is not installed)"
            .config(enabled = available && freecad != null) {
                for (c in cases) {
                    val script = copyFixture("${c.script}.kstep.kts")
                    val out = File(workDir, "fc-${c.script}.step").apply { delete() }
                    runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
                    val py =
                        "import Part; s=Part.read('${out.absolutePath}'); " +
                            "print('RESULT', s.isValid(), round(s.Volume, 4), len(s.Faces), len(s.Solids))"
                    val process = ProcessBuilder(freecad!!.path, "-c", py).redirectErrorStream(true).start()
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor() shouldBe 0
                    val line = output.lines().first { it.startsWith("RESULT") }.split(" ")
                    line[1] shouldBe "True"
                    line[2].toDouble() shouldBe (c.volume plusOrMinus 1e-3)
                    line[3].toInt() shouldBe c.faces
                    line[4].toInt() shouldBe 1
                }
            }
    })
