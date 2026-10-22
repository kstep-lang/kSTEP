package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val OCCT_OVERRIDE_PROPERTY = "kstep.occt.bridge.library"

private const val PRELUDE =
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
 * Subprocess-level coverage for `kstep export` with geometry (ADR-0027). OCCT-dependent cases are
 * gated on [OcctKernel.availability] like the other OCCT suites; the structure-only regression and
 * the failure paths that need no OCCT run everywhere.
 */
class CliExportGeometryIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-geometry-integration-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            val resource =
                requireNotNull(CliExportGeometryIntegrationTest::class.java.getResourceAsStream("/$name")) {
                    "test resource /$name not found on classpath"
                }
            resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        fun resourceText(name: String): String =
            requireNotNull(CliExportGeometryIntegrationTest::class.java.getResourceAsStream("/$name")) { name }
                .use { String(it.readBytes(), Charsets.UTF_8) }

        fun count(
            text: String,
            token: String,
        ): Int = Regex(Regex.escape(token)).findAll(text).count()

        "a 20x30x40 box is exported with its geometry merged and re-readable".config(enabled = available) {
            val script = copyFixture("export-box-20x30x40.kstep.kts")
            val out = File(workDir, "box.step").apply { delete() }

            val result = runner.run("export", script.name, "--out", out.name)

            result.exitCode shouldBe 0
            result.stdout.trim() shouldBe "Exported 1 root(s) with 1 geometric shape(s) to ${out.name}"
            val text = out.readText()
            text shouldContain "PRODUCT_DEFINITION_SHAPE"
            text shouldContain "ADVANCED_BREP_SHAPE_REPRESENTATION"
            count(text, "ADVANCED_FACE(") shouldBe 6
            Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT).instances.isNotEmpty() shouldBe true
        }

        "--output json reports geometry.merged for a box".config(enabled = available) {
            val script = copyFixture("export-box-20x30x40.kstep.kts")
            val result = runner.run("export", script.name, "--out", "box-json.step", "--output", "json")

            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "success"
            json["shapeCount"]!!.jsonPrimitive.int shouldBe 1
            val geometry = json["geometry"]!!.jsonObject
            geometry["merged"]!!.jsonPrimitive.boolean shouldBe true
            geometry["shapeCount"]!!.jsonPrimitive.int shouldBe 1
            geometry["occt"]!!.jsonObject["available"]!!.jsonPrimitive.boolean shouldBe true
        }

        "--require-geometry without any shape fails with geometry_required and writes nothing" {
            val script = File(workDir, "no-shape.kstep.kts")
            script.writeText(
                PRELUDE +
                    """
                    stepFile(fileName = "x.step") { root(pd("NS-1")) }
                    """.trimIndent(),
            )
            val out = File(workDir, "no-shape.step").apply { delete() }

            val result = runner.run("export", script.name, "--require-geometry", "--output", "json")

            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["errorKind"]!!.jsonPrimitive.content shouldBe "geometry_required"
            json["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-006"
            json["command"]!!.jsonPrimitive.content shouldBe "export"
            out.exists() shouldBe false
        }

        "without OCCT a geometry script fails in the script run and writes nothing (documented behaviour)" {
            val script = copyFixture("export-box-20x30x40.kstep.kts")
            val out = File(workDir, "no-occt.step").apply { delete() }

            val result =
                runner.run(
                    "export",
                    script.name,
                    "--out",
                    out.name,
                    jvmArgs = listOf("-D$OCCT_OVERRIDE_PROPERTY=${File(workDir, "does-not-exist.dylib").absolutePath}"),
                )

            result.exitCode shouldBe 1
            result.stdout shouldContain "Script export failed"
            out.exists() shouldBe false
        }

        "--validate together with geometry passes and the file carries both".config(enabled = available) {
            val script = copyFixture("export-box-20x30x40.kstep.kts")
            val out = File(workDir, "box-validate.step").apply { delete() }

            val result = runner.run("export", script.name, "--out", out.name, "--validate")

            result.exitCode shouldBe 0
            result.stdout shouldContain "Model validation passed"
            out.readText() shouldContain "ADVANCED_BREP_SHAPE_REPRESENTATION"
        }

        "a failing --validate with geometry exits 1, writes nothing and still terminates cleanly".config(
            enabled = available,
        ) {
            val script = File(workDir, "cyclic-geometry.kstep.kts")
            script.writeText(
                PRELUDE +
                    """
                    val a = pd("CY-A")
                    val b = pd("CY-B")
                    val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
                    stepFile(fileName = "x.step") {
                        root(nextAssemblyUsageOccurrence("N1") { name = "n1"; relatingProductDefinition = a; relatedProductDefinition = b; referenceDesignator = "r" }.getOrThrow())
                        root(nextAssemblyUsageOccurrence("N2") { name = "n2"; relatingProductDefinition = b; relatedProductDefinition = a; referenceDesignator = "r" }.getOrThrow())
                        shape(a, box)
                    }
                    """.trimIndent(),
            )
            val out = File(workDir, "cyclic-geometry.step").apply { delete() }

            val result = runner.run("export", script.name, "--validate")

            result.exitCode shouldBe 1
            out.exists() shouldBe false
        }

        "an assembly with two shapes merges both geometries once and keeps the structure".config(enabled = available) {
            val script = copyFixture("export-assembly-geometry.kstep.kts")
            val out = File(workDir, "assembly.step").apply { delete() }

            val result = runner.run("export", script.name, "--out", out.name)

            result.exitCode shouldBe 0
            result.stdout.trim() shouldBe "Exported 1 root(s) with 2 geometric shape(s) to ${out.name}"
            val text = out.readText()
            count(text, "NEXT_ASSEMBLY_USAGE_OCCURRENCE(") shouldBe 1
            count(text, "SHAPE_DEFINITION_REPRESENTATION(") shouldBe 2
            count(text, "APPLICATION_PROTOCOL_DEFINITION(") shouldBe 1
            count(text, "ADVANCED_FACE(") shouldBe 12
            val ids = Regex("^#(\\d+)\\s*=", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toList()
            ids.size shouldBe ids.toSet().size
            Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT).instances.isNotEmpty() shouldBe true
        }

        "a shape bound to a product definition not reachable from the roots fails closed".config(enabled = available) {
            val script = File(workDir, "unreachable.kstep.kts")
            script.writeText(
                PRELUDE +
                    """
                    val a = pd("UR-A")
                    val other = pd("UR-B")
                    val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
                    stepFile(fileName = "x.step") {
                        root(a)
                        shape(other, box)
                    }
                    """.trimIndent(),
            )
            val out = File(workDir, "unreachable.step").apply { delete() }

            val result = runner.run("export", script.name, "--output", "json")

            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["errorKind"]!!.jsonPrimitive.content shouldBe "geometry_export_failed"
            json["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-008"
            json["message"]!!.jsonPrimitive.content shouldContain "not reachable"
            out.exists() shouldBe false
        }

        "two shapes on one product definition fail closed".config(enabled = available) {
            val script = File(workDir, "duplicate-pd.kstep.kts")
            script.writeText(
                PRELUDE +
                    """
                    val a = pd("DP-A")
                    stepFile(fileName = "x.step") {
                        root(a)
                        shape(a, OcctKernel.makeBox(1.0, 1.0, 1.0))
                        shape(a, OcctKernel.makeBox(2.0, 2.0, 2.0))
                    }
                    """.trimIndent(),
            )

            val result = runner.run("export", script.name, "--output", "json")

            result.exitCode shouldBe 1
            Json
                .parseToJsonElement(result.stdout.trim())
                .jsonObject["errorKind"]!!
                .jsonPrimitive.content shouldBe "geometry_export_failed"
        }

        "an already-closed shape yields a clear error, not a stack trace".config(enabled = available) {
            val script = File(workDir, "closed-shape.kstep.kts")
            script.writeText(
                PRELUDE +
                    """
                    val a = pd("CL-A")
                    val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
                    box.close()
                    stepFile(fileName = "x.step") {
                        root(a)
                        shape(a, box)
                    }
                    """.trimIndent(),
            )
            val out = File(workDir, "closed-shape.step").apply { delete() }

            val result = runner.run("export", script.name, "--output", "json")

            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["errorKind"]!!.jsonPrimitive.content shouldBe "geometry_export_failed"
            json["message"]!!.jsonPrimitive.content shouldContain "closed"
            result.stdout shouldNotContain "\tat "
            out.exists() shouldBe false
        }

        "structure-only export is byte-identical to the pre-ADR-0027 golden file, text and JSON" {
            val script = copyFixture("export-golden-assembly.kstep.kts")
            val out = File(workDir, "golden-out.step").apply { delete() }

            val text = runner.run("export", script.name, "--out", out.name)
            text.exitCode shouldBe 0
            text.stdout.trim() shouldBe "Exported 2 root(s) to ${out.name}"
            out.readText() shouldBe resourceText("export-golden-assembly.step")

            val json = runner.run("export", script.name, "--out", out.name, "--output", "json")
            json.exitCode shouldBe 0
            val obj = Json.parseToJsonElement(json.stdout.trim()).jsonObject
            obj.keys shouldBe setOf("status", "outPath", "rootCount", "shapeCount")
        }
    })
