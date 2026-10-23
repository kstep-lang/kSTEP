package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.UUID

/**
 * End-to-end coverage of the `kstep import` worker model (ADR-0038): the CLI runs the import in a worker subprocess
 * and translates every abnormal end into a structured error. Every test runs with and without the native OCCT bridge
 * (no skips): without it the worker reports `geometry_unavailable` as a regular error, which must also pass through.
 */
class CliImportWorkerIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-import-worker").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun box(name: String = "box-20x30x40.step"): File {
            val target = File(workDir, name)
            val input = requireNotNull(javaClass.getResourceAsStream("/import/box-20x30x40.step")) { "box" }
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            return target
        }

        /** Parses stdout as exactly one JSON object; throws if anything else (a banner, a second line) is there. */
        fun pureJson(stdout: String): JsonObject {
            stdout.trimEnd().lines().size shouldBe 1
            return Json.parseToJsonElement(stdout.trim()) as JsonObject
        }

        "a regular import through the worker prints exactly what the in-process import prints (JSON and text)" {
            box()
            for (args in listOf(listOf("--output", "json"), emptyList(), listOf("--validate", "--output", "json"))) {
                val viaWorker = runner.run("import", "box-20x30x40.step", *args.toTypedArray())
                val inProcess =
                    runner.run("box-20x30x40.step", *args.toTypedArray(), mainClass = "dev.kstep.cli.ImportWorker")
                viaWorker.stdout shouldBe inProcess.stdout
                viaWorker.exitCode shouldBe inProcess.exitCode
                viaWorker.stdout.isNotEmpty() shouldBe true
                if (available) viaWorker.exitCode shouldBe 0
            }
        }

        "a regular import error (missing file) passes through unchanged" {
            val viaWorker = runner.run("import", "missing.step", "--output", "json")
            val inProcess = runner.run("missing.step", "--output", "json", mainClass = "dev.kstep.cli.ImportWorker")
            viaWorker.stdout shouldBe inProcess.stdout
            viaWorker.exitCode shouldBe 1
            pureJson(viaWorker.stdout)["code"]!!.jsonPrimitive.content shouldBe "KSTEP-I-002"
        }

        // Audit of the worker wave: these four files ended the CLI process with exit 132 and no output at all.
        val crashers =
            mapOf(
                "oself.step" to { ImportFixtures.overrideSelf() },
                "ocd3000.step" to { ImportFixtures.overrideChain(3000) },
                "mc1000.step" to { ImportFixtures.mappedItemChain(1000) },
                "mcyc.step" to { ImportFixtures.mappedItemCycle() },
                // Not a crasher but a slow file (26 s): rejected by the shell-reference check before the transfer.
                "sh50k_0.step" to { ImportFixtures.repeatedShellFaces(50_000) },
            )
        for ((name, generate) in crashers) {
            "$name: a file that crashed the process now yields one structured JSON error" {
                File(workDir, name).writeBytes(generate())
                val start = System.nanoTime()
                val result = runner.run("import", name, "--output", "json")
                ((System.nanoTime() - start) / 1e9 < 30.0) shouldBe true
                result.exitCode shouldBe 1
                result.stdout shouldNotContain "A fatal error"
                result.stderr shouldNotContain "A fatal error"
                result.stderr shouldNotContain "hs_err"
                val json = pureJson(result.stdout)
                json["status"]!!.jsonPrimitive.content shouldBe "error"
                json["command"]!!.jsonPrimitive.content shouldBe "import"
                json["code"]!!.jsonPrimitive.content shouldStartWith "KSTEP-"
                if (available) json["errorKind"]!!.jsonPrimitive.content shouldBe "limit_exceeded"
                File(workDir, name).delete()
            }
        }

        "moderate override chains, mapped items and shells are still imported".config(enabled = available) {
            val cases =
                mapOf(
                    "ochain2.step" to ImportFixtures.overrideChain(2),
                    "mchain3.step" to ImportFixtures.mappedItemChain(3),
                    "shell2.step" to ImportFixtures.repeatedShellFaces(2),
                )
            for ((name, bytes) in cases) {
                File(workDir, name).writeBytes(bytes)
                val result = runner.run("import", name, "--output", "json")
                result.exitCode shouldBe 0
                pureJson(result.stdout)["status"]!!.jsonPrimitive.content shouldBe "success"
            }
        }

        "the wall-clock bound kills the worker and reports limit_exceeded, in JSON and in text" {
            // A 1 ms bound: the worker JVM cannot even start within it, so the supervisor's timeout path runs.
            val marker = "timeout-${UUID.randomUUID()}.step"
            box(marker)
            val bound = listOf("-Dkstep.import.timeoutMillis=1")
            val json = runner.run("import", marker, "--output", "json", jvmArgs = bound)
            json.exitCode shouldBe 1
            val doc = pureJson(json.stdout)
            doc["code"]!!.jsonPrimitive.content shouldBe "KSTEP-I-010"
            doc["errorKind"]!!.jsonPrimitive.content shouldBe "limit_exceeded"
            doc["message"]!!.jsonPrimitive.content shouldContain "within 1 ms"
            val text = runner.run("import", marker, jvmArgs = bound)
            text.exitCode shouldBe 1
            text.stdout shouldStartWith "Error: import did not finish within 1 ms"
            // No worker of these runs is left behind (the file name is unique to this test).
            val leftovers =
                ProcessHandle
                    .allProcesses()
                    .filter { p ->
                        p.isAlive &&
                            p
                                .info()
                                .commandLine()
                                .orElse("")
                                .contains(marker)
                    }.toList()
            leftovers.shouldBeEmpty()
        }
    })
