package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

/**
 * Subprocess coverage of the `kstep import` failure paths that are decided BEFORE OCCT is involved
 * (ADR-0030): they behave identically with and without the native bridge, so they run in every CI job.
 */
class CliImportErrorPathsIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-import-error-paths").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)

        fun fixture(name: String): File {
            val target = File(workDir, name.substringAfterLast('/'))
            val input =
                requireNotNull(CliImportErrorPathsIntegrationTest::class.java.getResourceAsStream("/$name")) { name }
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            return target
        }

        fun errorOf(vararg args: String): Pair<String, String> {
            val result = runner.run(*args, "--output", "json")
            result.exitCode shouldBe 1
            result.stderr shouldNotContain "Exception in thread"
            result.stderr shouldNotContain "\tat "
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "error"
            json["command"]!!.jsonPrimitive.content shouldBe "import"
            return json["errorKind"]!!.jsonPrimitive.content to json["code"]!!.jsonPrimitive.content
        }

        "a missing file reports file_not_found as JSON with exit 1" {
            errorOf("import", "does-not-exist.step") shouldBe ("file_not_found" to "KSTEP-I-002")
        }

        "a directory reports not_a_regular_file" {
            File(workDir, "a-directory").mkdirs()
            errorOf("import", "a-directory") shouldBe ("not_a_regular_file" to "KSTEP-I-004")
        }

        "a symbolic link is rejected" {
            val target = fixture("import/not-step.txt")
            val link = File(workDir, "link.step")
            Files.deleteIfExists(link.toPath())
            val created =
                try {
                    Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath())
                    true
                } catch (e: Exception) {
                    false
                }
            if (created) errorOf("import", link.name) shouldBe ("symlink_rejected" to "KSTEP-I-003")
        }

        "an empty file is rejected" {
            fixture("import/empty.step")
            errorOf("import", "empty.step") shouldBe ("empty_file" to "KSTEP-I-005")
        }

        "a text file is not_step" {
            fixture("import/not-step.txt")
            errorOf("import", "not-step.txt") shouldBe ("not_step" to "KSTEP-I-007")
        }

        "a file with an extern-document reference is rejected before OCCT reads it" {
            fixture("import/external-ref.step")
            errorOf("import", "external-ref.step") shouldBe ("external_reference" to "KSTEP-I-008")
        }

        "text mode prints a one-line error on stdout and exits 1" {
            fixture("import/not-step.txt")
            val result = runner.run("import", "not-step.txt")
            result.exitCode shouldBe 1
            result.stdout.trim() shouldBe "Error: not an ISO 10303-21 STEP file"
        }

        "an import never writes a file next to its input" {
            fixture("import/not-step.txt")
            val before = workDir.list()!!.toSet()
            runner.run("import", "not-step.txt")
            workDir.list()!!.toSet() shouldBe before
        }

        "a malformed command line prints the usage and exits 1" {
            val result = runner.run("import")
            result.exitCode shouldBe 1
            result.stdout shouldContain "kstep import <file.step>"
        }

        "import fails predictably when the native bridge is unavailable (geometry_unavailable)".config(
            enabled = OcctKernel.availability() !is OcctAvailability.Available,
        ) {
            fixture("import/box-20x30x40.step")
            errorOf("import", "box-20x30x40.step") shouldBe ("geometry_unavailable" to "KSTEP-S-007")
        }
    })
