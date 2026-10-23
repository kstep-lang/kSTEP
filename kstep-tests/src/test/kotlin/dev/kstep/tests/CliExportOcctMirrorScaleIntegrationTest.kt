package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val MS_PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    fun pd(id: String) = productDefinition("${'$'}id-D") {
        formation = productDefinitionFormation("${'$'}id-F") { ofProduct = product(id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow() }.getOrThrow()
        frameOfReference = defCtx
    }.getOrThrow()
    """

/**
 * Subprocess-level coverage of Welle P (ADR-0036): the example scripts go through `kstep export --validate` and
 * `kstep import --output json`, and the numbers are the hand-computed ones. The class name carries `Occt` so that the
 * CI skip guard inspects these tests.
 */
class CliExportOcctMirrorScaleIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-mirror-scale-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            requireNotNull(CliExportOcctMirrorScaleIntegrationTest::class.java.getResourceAsStream("/$name")) {
                "test resource /$name not found on classpath"
            }.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        class Expected(
            val script: String,
            val volume: Double,
            val faces: Int,
            val bodies: Int,
            val min: List<Double>,
            val max: List<Double>,
        )

        val cases =
            listOf(
                Expected("export-mirrored-box", 6000.0, 6, 1, listOf(-10.0, 0.0, 0.0), listOf(0.0, 20.0, 30.0)),
                Expected(
                    "export-mirror-union-bracket",
                    24000.0,
                    10,
                    1,
                    listOf(-40.0, 0.0, 0.0),
                    listOf(40.0, 30.0, 20.0),
                ),
                Expected("export-scaled-box", 48000.0, 6, 1, listOf(0.0, 0.0, 0.0), listOf(20.0, 40.0, 60.0)),
                Expected("export-scaled-box-about-point", 750.0, 6, 1, listOf(2.5, 5.0, 7.5), listOf(7.5, 15.0, 22.5)),
                Expected(
                    "export-mirror-named-fillet",
                    6000.0 - (4.0 - Math.PI) * 30.0,
                    7,
                    1,
                    listOf(-10.0, 0.0, 0.0),
                    listOf(0.0, 20.0, 30.0),
                ),
                Expected(
                    "export-two-bodies-one-part",
                    12000.0,
                    12,
                    2,
                    listOf(-20.0, 0.0, 0.0),
                    listOf(10.0, 20.0, 30.0),
                ),
            )

        fun JsonObject.vec(key: String): List<Double> = this[key]!!.jsonArray.map { it.jsonPrimitive.double }

        cases.forEach { c ->
            "kstep export --validate of ${c.script} re-imports with the hand-computed volume, faces, bodies and box"
                .config(
                    enabled = available,
                ) {
                    val script = copyFixture("${c.script}.kstep.kts")
                    val out = File(workDir, "${c.script}.step").apply { delete() }
                    val export = runner.run("export", script.name, "--out", out.name, "--validate")
                    export.exitCode shouldBe 0
                    export.stdout shouldContain "Exported 1 root(s) with 1 geometric shape(s)"
                    val text = out.readText()
                    Regex("MANIFOLD_SOLID_BREP\\(").findAll(text).count() shouldBe c.bodies
                    val imported = runner.run("import", out.name, "--output", "json")
                    imported.exitCode shouldBe 0
                    val part =
                        Json
                            .parseToJsonElement(
                                imported.stdout.trim(),
                            ).jsonObject["parts"]!!
                            .jsonArray
                            .single()
                            .jsonObject
                    part["volume"]!!.jsonPrimitive.double shouldBe (c.volume plusOrMinus 1e-2)
                    part["topology"]!!.jsonObject["faces"]!!.jsonPrimitive.int shouldBe c.faces
                    part["topology"]!!.jsonObject["solids"]!!.jsonPrimitive.int shouldBe c.bodies
                    val box = part["boundingBox"]!!.jsonObject
                    box.vec("min").zip(c.min).forEach { (a, e) -> a shouldBe (e plusOrMinus 1e-6) }
                    box.vec("max").zip(c.max).forEach { (a, e) -> a shouldBe (e plusOrMinus 1e-6) }
                    val bodies: JsonArray? = part["bodies"]?.jsonArray
                    if (c.bodies > 1) {
                        bodies!!.size shouldBe c.bodies
                        bodies.sumOf { it.jsonObject["volume"]!!.jsonPrimitive.double } shouldBe
                            (c.volume plusOrMinus 1e-2)
                    }
                }
        }

        "the two-bodies example writes ONE shape representation holding two solids".config(enabled = available) {
            val script = copyFixture("export-two-bodies-one-part.kstep.kts")
            val out = File(workDir, "two-bodies.step").apply { delete() }
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            val text = out.readText()
            Regex("MANIFOLD_SOLID_BREP\\(").findAll(text).count() shouldBe 2
            // one SHAPE_REPRESENTATION whose item list holds the axis placement and both solids
            Regex("SHAPE_REPRESENTATION\\('',\\(#\\d+,#\\d+,#\\d+\\)").findAll(text).count() shouldBe 1
        }

        "two shape assignments on one product definition are refused and point at OcctKernel.compound".config(
            enabled = available,
        ) {
            val script = File(workDir, "two-assignments.kstep.kts")
            script.writeText(
                MS_PRELUDE +
                    """
                    val d = pd("TWO")
                    stepFile(fileName = "x.step") {
                        root(d)
                        shape(d, OcctKernel.makeBox(1.0, 1.0, 1.0))
                        shape(d, mirror(OcctKernel.makeBox(1.0, 1.0, 1.0), MirrorPlane.x(-5.0)))
                    }
                    """.trimIndent(),
            )
            val result = runner.run("export", script.name, "--out", "two-assignments.step")
            result.exitCode shouldBe 1
            (result.stdout + result.stderr) shouldContain "exactly one shape per product definition"
            (result.stdout + result.stderr) shouldContain "OcctKernel.compound"
        }

        "a scaled Rotation.fromAxes placement is refused with KSTEP-S-003 and a pointer to scale(...)".config(
            enabled = available,
        ) {
            val script = File(workDir, "scaled-placement.kstep.kts")
            script.writeText(
                MS_PRELUDE +
                    """
                    val a = pd("SP-A")
                    val b = pd("SP-B")
                    val u = nextAssemblyUsageOccurrence("SP-U") { name = "u"; relatingProductDefinition = a; relatedProductDefinition = b }.getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(u)
                        shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0))
                        place(u, rotation = Rotation.fromAxes(vec(0.0, 0.0, 2.0), vec(1.0, 0.0, 0.0)))
                    }
                    """.trimIndent(),
            )
            val result = runner.run("export", script.name, "--out", "scaled-placement.step", "--output", "json")
            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-003"
            json["message"]!!.jsonPrimitive.content shouldContain "scale("
        }

        "a scale factor of 0 and of NaN are script runtime errors with a message and write no file".config(
            enabled = available,
        ) {
            val zero = copyFixture("hello-scale-invalid.kstep.kts")
            val nan = File(workDir, "hello-scale-nan.kstep.kts")
            nan.writeText(zero.readText().replace("), 0.0)", "), Double.NaN)"))
            val zeroOut = File(workDir, "zero.step").apply { delete() }
            val nanOut = File(workDir, "nan.step").apply { delete() }
            val rz = runner.run("export", zero.name, "--out", zeroOut.name)
            rz.exitCode shouldBe 1
            (rz.stdout + rz.stderr) shouldContain "scale factor must not be 0"
            val rn = runner.run("export", nan.name, "--out", nanOut.name)
            rn.exitCode shouldBe 1
            (rn.stdout + rn.stderr) shouldContain "NaN"
            zeroOut.exists() shouldBe false
            nanOut.exists() shouldBe false
        }

        "no generated artefact is left in src/test/resources" {
            val strays =
                File("src/test/resources")
                    .listFiles()
                    .orEmpty()
                    .filter {
                        it.extension in setOf("step", "svg", "glb", "stp") &&
                            it.name.contains("mirror|scale|bodies".toRegex())
                    }.map { it.name }
            strays shouldBe emptyList()
        }

        val freecad: File? =
            listOfNotNull(System.getenv("FREECADCMD"), "/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd")
                .map(::File)
                .firstOrNull { it.isFile && it.canExecute() }

        "FreeCAD reads the mirrored and scaled solids with the hand-computed volumes (skipped when FreeCAD is not installed)"
            .config(enabled = available && freecad != null) {
                for (c in cases) {
                    val script = copyFixture("${c.script}.kstep.kts")
                    val out = File(workDir, "fc-${c.script}.step").apply { delete() }
                    runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
                    val py =
                        "import Part; s=Part.read('${out.absolutePath}'); " +
                            "print('RESULT', s.isValid(), round(s.Volume, 4), len(s.Faces), len(s.Solids))"
                    val process =
                        ProcessBuilder(freecad!!.path, "-c", py)
                            .redirectErrorStream(true)
                            .start()
                    val output = process.inputStream.bufferedReader().readText()
                    process.waitFor() shouldBe 0
                    val line = output.lines().first { it.startsWith("RESULT") }.split(" ")
                    line[1] shouldBe "True"
                    line[2].toDouble() shouldBe (c.volume plusOrMinus 1e-3)
                    line[3].toInt() shouldBe c.faces
                    line[4].toInt() shouldBe c.bodies
                }
            }
    })
