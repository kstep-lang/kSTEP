package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
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
 * Welle M (ADR-0032): the sketch extension example scripts through the real `kstep export` / `kstep import`,
 * plus the structured failure paths. Needs both native bridges for the positive cases. The class name carries
 * `Occt` so that the CI skip gate covers it. Fixtures are copied into `build/`, never written to src/test.
 */
class CliExportSketchExtensionsIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-sketch-extensions-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val both =
            OcctKernel.availability() is OcctAvailability.Available &&
                PlaneGcsSolver.availability() is PlaneGcsAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            requireNotNull(CliExportSketchExtensionsIntegrationTest::class.java.getResourceAsStream("/$name")) {
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

        // script, expected volume, expected faces
        listOf(
            Triple("export-sketch-equal-radius-holes", 7434.513, 8),
            Triple("export-sketch-radius-unknown", 7434.513, 8),
            Triple("export-sketch-tangent-circle", 6869.027, 7),
            Triple("export-sketch-feature-rebuild", 11717.257, 7),
        ).forEach { (name, volume, faces) ->
            "kstep export of $name re-imports with V = $volume and $faces faces".config(enabled = both) {
                val part = exportAndImport(name)
                part["volume"]!!.jsonPrimitive.double shouldBe (volume plusOrMinus 1e-2)
                part["topology"]!!.jsonObject["faces"]!!.jsonPrimitive.int shouldBe faces
            }
        }

        "two separate contours export as two bodies and re-import with per-body figures".config(enabled = both) {
            val part = exportAndImport("export-sketch-two-islands")
            part["volume"]!!.jsonPrimitive.double shouldBe (2000.0 plusOrMinus 1e-3)
            val topology = part["topology"]!!.jsonObject
            topology["solids"]!!.jsonPrimitive.int shouldBe 2
            topology["faces"]!!.jsonPrimitive.int shouldBe 12
            val bodies = part["bodies"]!!.jsonArray
            bodies.size shouldBe 2
            bodies.forEach {
                it.jsonObject["volume"]!!.jsonPrimitive.double shouldBe (1000.0 plusOrMinus 1e-3)
                it.jsonObject["faces"]!!.jsonPrimitive.int shouldBe 6
            }
        }

        "touching contours exit non-zero with KSTEP-S-010 and no stack trace".config(enabled = both) {
            val script = copyFixture("hello-sketch-islands-touching.kstep.kts")
            val result = runner.run("export", script.name, "--out", "never.step")
            (result.exitCode != 0) shouldBe true
            val output = result.stdout + result.stderr
            output shouldContain "KSTEP-S-010"
            output shouldContain "INVALID_PROFILE"
            output shouldNotContain "\tat "
            File(workDir, "never.step").exists() shouldBe false
        }

        "a sketch feature with an unavailable PlaneGCS bridge exits non-zero with KSTEP-S-009 and no stack trace"
            .config(
                enabled = OcctKernel.availability() is OcctAvailability.Available,
            ) {
                val script = copyFixture("export-sketch-feature-rebuild.kstep.kts")
                val result =
                    runner.run(
                        "export",
                        script.name,
                        "--out",
                        "never-built.step",
                        jvmArgs = listOf("-Dkstep.planegcs.bridge.library=/nonexistent/libkstep_planegcs_bridge.so"),
                    )
                (result.exitCode != 0) shouldBe true
                val output = result.stdout + result.stderr
                output shouldContain "KSTEP-S-009"
                output shouldNotContain "\tat "
                File(workDir, "never-built.step").exists() shouldBe false
            }
    })
