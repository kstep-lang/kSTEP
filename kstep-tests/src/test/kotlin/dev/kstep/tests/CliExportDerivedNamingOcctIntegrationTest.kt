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

/**
 * Welle N2 (ADR-0034): the derived-edge-naming example scripts through the real `kstep export` /
 * `kstep import`, plus the structured failure path. The class name carries `Occt` so that the CI skip gate
 * covers it. Fixtures are copied into `build/`, never written to src/test.
 */
class CliExportDerivedNamingOcctIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-derived-naming-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val occt = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            requireNotNull(CliExportDerivedNamingOcctIntegrationTest::class.java.getResourceAsStream("/$name")) {
                "test resource /$name not found on classpath"
            }.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        fun exportAndImport(name: String) =
            run {
                val script = copyFixture("$name.kstep.kts")
                val out = File(workDir, "$name.step").apply { delete() }
                val export = runner.run("export", script.name, "--out", out.name)
                export.exitCode shouldBe 0
                out.readText() shouldContain "MANIFOLD_SOLID_BREP"
                val imported = runner.run("import", out.name, "--output", "json")
                imported.exitCode shouldBe 0
                Json
                    .parseToJsonElement(imported.stdout.trim())
                    .jsonObject["parts"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            }

        // script, expected volume, expected faces. Hand values: 16000 - 160 pi - 5.694718 = 15491.650;
        // 24000 - 160 pi - 5.694718 = 23491.650; 7000 + 2 (1 - pi/4) 2^2 10 = 7017.168 (10 + 2 fillet faces).
        listOf(
            Triple("export-hole-rim-fillet", 15491.650, 8),
            Triple("export-hole-rim-fillet-rebuild", 23491.650, 8),
            Triple("export-union-section-fillet", 7017.168, 12),
        ).forEach { (name, volume, faces) ->
            "kstep export of $name re-imports with V = $volume and $faces faces".config(enabled = occt) {
                val part = exportAndImport(name)
                part["volume"]!!.jsonPrimitive.double shouldBe (volume plusOrMinus 1e-2)
                part["topology"]!!.jsonObject["faces"]!!.jsonPrimitive.int shouldBe faces
            }
        }

        "an unresolved derived edge exits non-zero with KSTEP-S-011, the candidates and no stack trace".config(
            enabled = occt,
        ) {
            val script = copyFixture("hello-derived-edges-unresolved.kstep.kts")
            val result = runner.run("export", script.name, "--out", "never.step")
            (result.exitCode != 0) shouldBe true
            val output = result.stdout + result.stderr
            output shouldContain "KSTEP-S-011"
            output shouldContain "HoleWall"
            output shouldContain "candidate edges"
            output shouldNotContain "\tat "
            File(workDir, "never.step").exists() shouldBe false
        }
    })
