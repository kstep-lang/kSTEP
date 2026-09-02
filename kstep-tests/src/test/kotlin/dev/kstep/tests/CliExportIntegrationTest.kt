package dev.kstep.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

// Prepended to every fixture script below that builds a product/product_definition: as of kSTEP
// M2 Welle 10 both need a real context, kstep-core no longer invents one (see
// docs/adr/ADR-0004-core-on-generated-types.adoc).
private const val CONTEXT_PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    """

/**
 * Subprocess-level, out-of-process integration coverage for `kstep export` (`kstep-cli`'s
 * `Main.kt`) -- deliberately run as a genuine child `java` process rather than calling `main()`
 * in this test JVM, since `main()`'s error path calls `exitProcess`, which would tear down the
 * whole test JVM (see [CliMainTest]'s own KDoc for why that class only exercises
 * `resolveCommand`). Reuses *this* test JVM's own `java.class.path` system property as the
 * child's `-cp`, so no `installDist`/distribution build step is a precondition for these tests --
 * every dependency `kstep export` needs (kstep-script's scripting host, kstep-step21's
 * Part21Writer, kotlinx-serialization-json) is already on this module's own test runtime
 * classpath.
 *
 * [CliMainTest] only covers the pure `resolveCommand`/`resolveExportCommand` argument-parsing
 * branch, and [KStepScriptExportTest] drives `KStepScriptHost` + `Part21Writer` directly,
 * bypassing `Main.kt` entirely -- neither exercises `Main.kt`'s own `--out` path-derivation
 * logic (`deriveOutputPath`), its human-readable text rendering (`errorText`)/the success
 * `println`, its `--output json` document shape (`errorJson`/the success JSON object), or the
 * `Part21WriteException`/`Part21EncodingException` -> `RuntimeError` conversion branch in
 * `writeExport`. This class closes that gap.
 *
 * `Part21LimitExceededException` (the third exception `writeExport` converts) is deliberately
 * not exercised end-to-end here: it only trips at a reachability-walk depth of 64, and the twelve
 * `kstep-core` AP242 entity types have a true max chain depth of 6 (see `Part21Writer`'s own KDoc)
 * -- there is no way to reach it through a genuine script without directly faking the writer's
 * internals, which would defeat the point of a black-box subprocess test. The three `catch`
 * branches in `writeExport` are structurally identical (`printError(jsonOutput,
 * e.toRuntimeError())`), so the `Part21WriteException`/`Part21EncodingException` coverage below
 * already exercises that shared conversion path.
 */
class CliExportIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-integration-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)

        fun runCli(vararg args: String): CliInvocationResult = runner.run(*args)

        "export with no --out derives the output path from the script name and prints human-readable success" {
            val script = File(workDir, "bracket-default-out.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val bracket = product("BRK-001") { name = "Bracket"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val bracketFormation = productDefinitionFormation("BRK-001-F") { ofProduct = bracket }.getOrThrow()
                    val definition = productDefinition("BRK-001-D") { formation = bracketFormation; frameOfReference = defCtx }.getOrThrow()

                    stepFile(fileName = "bracket.step") {
                        root(definition)
                    }
                    """.trimIndent(),
            )

            val result = runCli("export", script.name)

            result.exitCode shouldBe 0
            result.stdout.trim() shouldBe "Exported 1 root(s) to bracket-default-out.step"
            File(workDir, "bracket-default-out.step").exists() shouldBe true
        }

        "\"--out\" overrides the derived output path" {
            val script = File(workDir, "bracket-explicit-out.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val bracket = product("BRK-002") { name = "Bracket"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val bracketFormation = productDefinitionFormation("BRK-002-F") { ofProduct = bracket }.getOrThrow()
                    val definition = productDefinition("BRK-002-D") { formation = bracketFormation; frameOfReference = defCtx }.getOrThrow()

                    stepFile(fileName = "bracket.step") {
                        root(definition)
                    }
                    """.trimIndent(),
            )

            val result = runCli("export", script.name, "--out", "custom-name.step")

            result.exitCode shouldBe 0
            result.stdout.trim() shouldBe "Exported 1 root(s) to custom-name.step"
            File(workDir, "custom-name.step").exists() shouldBe true
        }

        "\"--output json\" renders the success document with status/outPath/rootCount" {
            val script = File(workDir, "bracket-json-success.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val bracket = product("BRK-003") { name = "Bracket"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                    val bracketFormation = productDefinitionFormation("BRK-003-F") { ofProduct = bracket }.getOrThrow()
                    val definition = productDefinition("BRK-003-D") { formation = bracketFormation; frameOfReference = defCtx }.getOrThrow()

                    stepFile(fileName = "bracket.step") {
                        root(definition)
                    }
                    """.trimIndent(),
            )

            val result = runCli("export", "--output", "json", script.name)

            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]?.jsonPrimitive?.content shouldBe "success"
            json["outPath"]?.jsonPrimitive?.content shouldBe "bracket-json-success.step"
            json["rootCount"]?.jsonPrimitive?.int shouldBe 1
        }

        "a WHERE-rule violation renders as \"--output json\"'s validation_failed document" {
            val script = File(workDir, "invalid-empty-id.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    stepFile(fileName = "invalid.step") {
                        root(product(id = "") { name = "Nameless"; frameOfReference = setOf(prodCtx) })
                    }
                    """.trimIndent(),
            )

            val result = runCli("export", "--output", "json", script.name)

            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]?.jsonPrimitive?.content shouldBe "error"
            json["errorKind"]?.jsonPrimitive?.content shouldBe "validation_failed"
            val violations = json["violations"]?.jsonArray
            violations?.size shouldBe 1
            violations!![0].jsonObject["code"]?.jsonPrimitive?.content shouldBe "KSTEP-W-001"
        }

        "a script that fails to compile renders human-readable text starting with \"Script failed to compile:\"" {
            val script = File(workDir, "does-not-compile.kstep.kts")
            script.writeText("this is not valid kotlin {{{")

            val result = runCli("export", script.name)

            result.exitCode shouldBe 1
            result.stdout shouldContain "Script failed to compile:"
        }

        "a root that is not one of the twelve supported entity types renders Part21WriteException as a runtime_error" {
            val script = File(workDir, "unsupported-root-type.kstep.kts")
            script.writeText(
                """
                stepFile(fileName = "unsupported.step") {
                    root("just a plain string, not a kstep-core entity")
                }
                """.trimIndent(),
            )

            val result = runCli("export", "--output", "json", script.name)

            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]?.jsonPrimitive?.content shouldBe "error"
            json["errorKind"]?.jsonPrimitive?.content shouldBe "runtime_error"
            json["exceptionClass"]?.jsonPrimitive?.content shouldBe "dev.kstep.step21.Part21WriteException"
            json["message"]?.jsonPrimitive?.content shouldContain "not one of the twelve supported"
        }

        "a non-ASCII field value renders Part21EncodingException as human-readable runtime-error text" {
            val script = File(workDir, "non-ascii-name.kstep.kts")
            script.writeText(
                CONTEXT_PRELUDE +
                    """
                    val bracket = product("BRK-004") { name = "Bräcket"; frameOfReference = setOf(prodCtx) }.getOrThrow()

                    stepFile(fileName = "non-ascii.step") {
                        root(bracket)
                    }
                    """.trimIndent(),
            )

            val result = runCli("export", script.name)

            result.exitCode shouldBe 1
            result.stdout shouldContain "Script export failed (dev.kstep.step21.Part21EncodingException):"
            result.stdout shouldContain "unsupported character"
        }
    })
