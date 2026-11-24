package dev.kstep.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private const val MODEL =
    """
val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
val bracket = product("BRK-001") { name = "Bracket"; frameOfReference = setOf(prodCtx) }.getOrThrow()
stepFile(fileName = "t.step") { root(bracket) }
"""

/**
 * The input files of a script in the render JSON (ADR-0080): the desktop worker reports them (also when empty and when
 * the script failed), the public `kstep render --output json` document never does. Real child processes, no OCCT.
 */
class DependencyReportingTest :
    StringSpec({
        val workDir = File("build/dependency-reporting-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)

        fun script(
            name: String,
            body: String,
        ): File = File(workDir, name).also { it.writeText(body + "\n" + MODEL) }

        fun worker(vararg args: String) =
            runner.run(*args, "--output", "json", mainClass = "dev.kstep.cli.DesktopRenderWorker").let {
                it.exitCode to
                    Json
                        .parseToJsonElement(
                            it.stdout
                                .trim()
                                .lines()
                                .last { l -> l.isNotBlank() },
                        ).jsonObject
            }

        "the desktop worker reports the inputs of a successful run, sorted" {
            File(workDir, "in-b.txt").writeText("b")
            File(workDir, "in-a.txt").writeText("a")
            val s = script("deps-ok.kstep.kts", "readInput(\"in-b.txt\"); readInput(\"in-a.txt\")")
            val (code, json) = worker(s.name, "--format", "glb", "--out", File(workDir, "o1.glb").absolutePath)
            code shouldBe 0
            json["dependencies"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("in-a.txt", "in-b.txt")
        }

        "a script without inputs reports an empty list" {
            val s = script("deps-none.kstep.kts", "")
            val (code, json) = worker(s.name, "--format", "glb", "--out", File(workDir, "o2.glb").absolutePath)
            code shouldBe 0
            json["dependencies"] shouldBe JsonArray(emptyList())
        }

        "a failed script still reports the missing input and the structured error" {
            val s = script("deps-missing.kstep.kts", "readInput(\"gone.txt\")")
            val (code, json) = worker(s.name, "--format", "glb", "--out", File(workDir, "o3.glb").absolutePath)
            code shouldBe 1
            json["status"]!!.jsonPrimitive.content shouldBe "error"
            json["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-017"
            json["dependencies"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("gone.txt")
        }

        "the public render JSON has no dependencies key, success or failure".config(timeout = 240.seconds) {
            File(workDir, "pub.txt").writeText("x")
            val ok = script("pub-ok.kstep.kts", "readInput(\"pub.txt\")")
            val r1 = runner.run("render", "--output", "json", ok.name)
            r1.exitCode shouldBe 0
            val j1 = Json.parseToJsonElement(r1.stdout.trim()).jsonObject
            j1.containsKey("dependencies") shouldBe false
            j1["status"]!!.jsonPrimitive.content shouldBe "success"

            val bad = script("pub-bad.kstep.kts", "readInput(\"nothing-here.txt\")")
            val r2 = runner.run("render", "--output", "json", bad.name)
            r2.exitCode shouldBe 1
            val j2 = Json.parseToJsonElement(r2.stdout.trim()).jsonObject
            j2.containsKey("dependencies") shouldBe false
            j2["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-017"
        }

        "relative reading is anchored at the script folder, not the working directory" {
            val sub = File(workDir, "anchor").apply { mkdirs() }
            File(sub, "near.txt").writeText("near")
            val s = File(sub, "anchor.kstep.kts")
            s.writeText("check(readInput(\"near.txt\") == \"near\")\n$MODEL")
            val (code, json) = worker(s.absolutePath, "--format", "glb", "--out", File(workDir, "o5.glb").absolutePath)
            code shouldBe 0
            json["dependencies"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("near.txt")
            Files.exists(File(workDir, "near.txt").toPath()) shouldBe false
        }
    })
