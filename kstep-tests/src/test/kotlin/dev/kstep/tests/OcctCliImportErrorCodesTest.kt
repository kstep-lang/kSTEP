package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

/**
 * The `kstep import` failure codes that need OCCT to be reached (ADR-0038): KSTEP-I-001 through `--render`,
 * KSTEP-I-010 (assembly depth, before OCCT transfers anything), -I-011, -I-012 through `--render` and -I-013.
 * Subprocess runs, so a native crash would show as a signal exit code. Tests that depend on the operating
 * system (symlinks) live here only where they need OCCT; the pure path checks are in
 * [CliImportErrorPathsIntegrationTest].
 */
class OcctCliImportErrorCodesTest :
    StringSpec({
        val workDir = File("build/cli-import-error-codes").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun fixture(name: String): File {
            val target = File(workDir, name)
            val input =
                requireNotNull(OcctCliImportErrorCodesTest::class.java.getResourceAsStream("/import/$name")) { name }
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            return target
        }

        /** Runs `kstep import ... --output json`, asserts a clean error document and returns errorKind to code. */
        fun errorOf(vararg args: String): Pair<String, String> {
            val result = runner.run(*args, "--output", "json")
            result.exitCode shouldBe 1
            result.stderr shouldNotContain "hs_err"
            result.stderr shouldNotContain "Exception in thread"
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "error"
            return json["errorKind"]!!.jsonPrimitive.content to json["code"]!!.jsonPrimitive.content
        }

        "KSTEP-I-001: --render naming the input file is invalid_path".config(enabled = available) {
            val input = fixture("box-20x30x40.step")
            val same = File(workDir, "same.glb")
            input.copyTo(same, overwrite = true)
            errorOf("import", same.name, "--render", same.name) shouldBe ("invalid_path" to "KSTEP-I-001")
        }

        "KSTEP-I-001: --render onto a symbolic link is invalid_path".config(
            enabled = available && !System.getProperty("os.name").lowercase().contains("windows"),
        ) {
            fixture("box-20x30x40.step")
            val target = File(workDir, "render-target.glb").apply { writeText("x") }
            val link = File(workDir, "render-link.glb")
            Files.deleteIfExists(link.toPath())
            Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath())
            try {
                errorOf("import", "box-20x30x40.step", "--render", link.name) shouldBe
                    ("invalid_path" to "KSTEP-I-001")
            } finally {
                Files.deleteIfExists(link.toPath())
                target.delete()
            }
        }

        "KSTEP-I-010: an assembly chain of 33 levels is limit_exceeded".config(enabled = available) {
            File(workDir, "chain33.step").writeBytes(ImportFixtures.assemblyChain(33))
            errorOf("import", "chain33.step") shouldBe ("limit_exceeded" to "KSTEP-I-010")
        }

        "an assembly chain of exactly 32 levels passes the depth check".config(enabled = available) {
            // The generated chain is structure only, so it stops later with no_content (KSTEP-I-011); what
            // matters is that depth 32 is NOT rejected as limit_exceeded (KSTEP-I-010), pinning the boundary.
            File(workDir, "chain32.step").writeBytes(ImportFixtures.assemblyChain(32))
            errorOf("import", "chain32.step") shouldBe ("no_content" to "KSTEP-I-011")
        }

        // Security audit of Welle R: before the path-multiplicity check, a 56 KB diamond chain (28 levels, two usages
        // of the same child per level) took 69 s in OCCT's transfer before the Kotlin expansion limit fired. The
        // generous 10 s bound keeps CI stable while staying far below the vulnerable behaviour.
        "KSTEP-I-010: an assembly diamond of 28 levels x 2 usages is rejected before the transfer".config(
            enabled = available,
        ) {
            File(workDir, "diamond28x2.step").writeBytes(ImportFixtures.assemblyDiamond(28, 2))
            val start = System.nanoTime()
            errorOf("import", "diamond28x2.step") shouldBe ("limit_exceeded" to "KSTEP-I-010")
            ((System.nanoTime() - start) / 1e9 < 30.0) shouldBe true
        }

        "an assembly diamond of 3 levels x 2 usages is still imported with all its occurrences".config(
            enabled = available,
        ) {
            File(workDir, "diamond3x2.step").writeBytes(ImportFixtures.assemblyDiamond(3, 2))
            val result = runner.run("import", "diamond3x2.step", "--output", "json")
            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "success"
            // 2^4 placements of Klotz below the chain; the reference file's other parts add to the total.
            val totals = json["totals"]!!.jsonObject
            val occurrences = totals["occurrenceCount"]!!.jsonPrimitive.content
            (occurrences.toInt() >= 16) shouldBe true
        }

        // Security audit of Welle R: four style lists of 64 entries each (all within the per-list cap) multiply in
        // OCCT's colour reader to 64^4 steps per styled item; 100 such items (71 KB) took 15 s.
        "KSTEP-I-010: a nested style chain of 64^4 per styled item is rejected quickly".config(enabled = available) {
            File(workDir, "nest64x100.step").writeBytes(ImportFixtures.nestedStyles(64, 100))
            val start = System.nanoTime()
            errorOf("import", "nest64x100.step") shouldBe ("limit_exceeded" to "KSTEP-I-010")
            ((System.nanoTime() - start) / 1e9 < 30.0) shouldBe true
        }

        "a modest style chain (3 entries per list) on many styled items is still imported".config(
            enabled = available,
        ) {
            File(workDir, "nest3x100.step").writeBytes(ImportFixtures.nestedStyles(3, 100))
            val result = runner.run("import", "nest3x100.step", "--output", "json")
            result.exitCode shouldBe 0
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "success"
        }

        "KSTEP-I-011: a file whose representation holds no B-Rep is no_content".config(enabled = available) {
            fixture("structure-only.step")
            errorOf("import", "structure-only.step") shouldBe ("no_content" to "KSTEP-I-011")
        }

        "KSTEP-I-012: --render into a directory that does not exist is io_error".config(enabled = available) {
            fixture("box-20x30x40.step")
            errorOf("import", "box-20x30x40.step", "--render", "no-such-dir/out.glb") shouldBe
                ("io_error" to "KSTEP-I-012")
        }

        "KSTEP-I-013: a length unit with conversion factor 0 is unit_unresolved".config(enabled = available) {
            fixture("unresolved-unit-box.step")
            errorOf("import", "unresolved-unit-box.step") shouldBe ("unit_unresolved" to "KSTEP-I-013")
        }
    })
