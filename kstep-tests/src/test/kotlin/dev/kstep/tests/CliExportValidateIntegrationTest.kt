package dev.kstep.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// Shared script prelude: contexts plus a pd(id) helper building a product definition (see
// AssemblyTestModel for the in-process twin of this).
private const val VALIDATE_PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "e"; frameOfReference = appCtx; disciplineType = "m" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "e"; frameOfReference = appCtx; lifeCycleStage = "d" }.getOrThrow()
    fun pd(id: String): dev.kstep.generated.ap242v1.ProductDefinition {
        val prod = product("prod-" + id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow()
        val formation = productDefinitionFormation(id + "-F") { ofProduct = prod }.getOrThrow()
        return productDefinition(id) { this.formation = formation; frameOfReference = defCtx }.getOrThrow()
    }
    fun usage(id: String, relating: dev.kstep.generated.ap242v1.ProductDefinitionOrReference, related: dev.kstep.generated.ap242v1.ProductDefinitionOrReference) =
        nextAssemblyUsageOccurrence(id) {
            name = "usage " + id
            relatingProductDefinition = relating
            relatedProductDefinition = related
        }.getOrThrow()
    """

private const val CYCLE_BODY =
    """
    val a = pd("A")
    val b = pd("B")
    stepFile(fileName = "x.step") {
        root(usage("U1", a, b))
        root(usage("U2", b, a))
    }
    """

/**
 * Subprocess-level coverage for `kstep export --validate` (ADR-0024). Needs neither OCCT nor
 * PlaneGCS, so it runs on every platform and in CI.
 */
class CliExportValidateIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-validate-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)

        fun script(
            name: String,
            body: String,
        ): File {
            File(workDir, name.removeSuffix(".kstep.kts") + ".step").delete()
            return File(workDir, name).apply { writeText(VALIDATE_PRELUDE + body.trimIndent()) }
        }

        fun stepFileOf(script: File) = File(workDir, script.name.removeSuffix(".kstep.kts") + ".step")

        "a valid assembly with --validate exports and reports that validation passed" {
            val s =
                script(
                    "ok.kstep.kts",
                    """
                    stepFile(fileName = "x.step") { root(usage("U1", pd("TOP"), pd("LEAF"))) }
                    """,
                )
            val result = runner.run("export", s.name, "--validate")
            result.exitCode shouldBe 0
            result.stdout shouldContain "Exported 1 root(s) to ok.step"
            result.stdout shouldContain "Model validation passed"
            stepFileOf(s).exists() shouldBe true
        }

        "a usage cycle with --validate fails with KSTEP-W-002 and writes no file" {
            val s = script("cycle.kstep.kts", CYCLE_BODY)
            val result = runner.run("export", s.name, "--validate")
            result.exitCode shouldBe 1
            result.stdout shouldContain "Model validation failed"
            result.stdout shouldContain "KSTEP-W-002"
            result.stdout shouldContain "'A'"
            result.stdout shouldContain "Nothing was written."
            stepFileOf(s).exists() shouldBe false
        }

        "two usages of one relating definition with equal occurrence ids violate UR2" {
            val s =
                script(
                    "ur2.kstep.kts",
                    """
                    val top = pd("TOP")
                    val o1 = productDefinitionOccurrence("SAME") { }.getOrThrow()
                    val o2 = productDefinitionOccurrence("SAME") { }.getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(usage("U1", top, o1))
                        root(usage("U2", top, o2))
                    }
                    """,
                )
            val result = runner.run("export", s.name, "--validate")
            result.exitCode shouldBe 1
            result.stdout shouldContain "KSTEP-U-001"
            result.stdout shouldContain "UR2"
            stepFileOf(s).exists() shouldBe false
        }

        "a definition used within itself is reported as a cycle" {
            val s =
                script(
                    "self.kstep.kts",
                    """
                    val a = pd("A")
                    stepFile(fileName = "x.step") { root(usage("U1", a, a)) }
                    """,
                )
            val result = runner.run("export", s.name, "--validate")
            result.exitCode shouldBe 1
            result.stdout shouldContain "KSTEP-W-002"
        }

        "--output json --validate on a cycle renders the model_validation_failed document" {
            val s = script("cycle-json.kstep.kts", CYCLE_BODY)
            val result = runner.run("export", "--output", "json", "--validate", s.name)
            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]?.jsonPrimitive?.content shouldBe "error"
            json["errorKind"]?.jsonPrimitive?.content shouldBe "model_validation_failed"
            json["command"]?.jsonPrimitive?.content shouldBe "export"
            (json["instanceCount"]?.jsonPrimitive?.int ?: 0) shouldBeGreaterThan 0
            val codes =
                json["findings"]!!.jsonArray.flatMap { f ->
                    f.jsonObject["violations"]!!.jsonArray.map { it.jsonObject["code"]?.jsonPrimitive?.content }
                }
            codes.toSet() shouldBe setOf("KSTEP-W-002")
            stepFileOf(s).exists() shouldBe false
        }

        "--output json --validate on a valid model adds the validation object" {
            val s =
                script(
                    "ok-json.kstep.kts",
                    """
                    stepFile(fileName = "x.step") { root(usage("U1", pd("TOP"), pd("LEAF"))) }
                    """,
                )
            val result = runner.run("export", "--output", "json", "--validate", s.name)
            result.exitCode shouldBe 0
            val validation = Json.parseToJsonElement(result.stdout.trim()).jsonObject["validation"]!!.jsonObject
            validation["performed"]?.jsonPrimitive?.boolean shouldBe true
            validation["valid"]?.jsonPrimitive?.boolean shouldBe true
        }

        "regression: without --validate a cyclic script still exports (documents the known limitation, see ADR-0024)" {
            val s = script("cycle-noflag.kstep.kts", CYCLE_BODY)
            val result = runner.run("export", s.name)
            result.exitCode shouldBe 0
            stepFileOf(s).exists() shouldBe true
        }

        "without --validate the success output is unchanged (text and JSON)" {
            val s =
                script(
                    "plain.kstep.kts",
                    """
                    stepFile(fileName = "x.step") { root(usage("U1", pd("TOP"), pd("LEAF"))) }
                    """,
                )
            val text = runner.run("export", s.name)
            text.stdout shouldBe "Exported 1 root(s) to plain.step\n"
            val json = runner.run("export", "--output", "json", s.name)
            json.stdout shouldNotContain "validation"
        }

        "a pathologically deep usage chain makes --validate fail closed with KSTEP-S-005 and no file" {
            val s =
                script(
                    "deep.kstep.kts",
                    """
                    val defs = (0..300).map { pd("D" + it) }
                    stepFile(fileName = "x.step") {
                        for (i in 0 until 300) root(usage("U" + i, defs[i], defs[i + 1]))
                    }
                    """,
                )
            val result = runner.run("export", "--output", "json", "--validate", s.name, timeoutSeconds = 300)
            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["errorKind"]?.jsonPrimitive?.content shouldBe "model_validation_unavailable"
            json["code"]?.jsonPrimitive?.content shouldBe "KSTEP-S-005"
            json["reason"]?.jsonPrimitive?.content shouldBe "LIMIT_EXCEEDED"
            stepFileOf(s).exists() shouldBe false
        }
    })
