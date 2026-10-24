package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Subprocess-level coverage of the Welle-J geometry operations (ADR-0029) through the real
 * `kstep export` / `kstep render` CLI. Fixtures are copied into `build/` first, so no artefact
 * ever lands in `src/test/resources`.
 */
class CliExportGeometryOperationsIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-geometry-operations-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            requireNotNull(CliExportGeometryOperationsIntegrationTest::class.java.getResourceAsStream("/$name")) {
                "test resource /$name not found on classpath"
            }.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        listOf(
            "export-plate-with-hole",
            "export-boolean-union",
            "export-boolean-common",
            "export-boolean-cut",
            "export-revolved-sleeve",
            "export-chamfered-prism",
        ).forEach { name ->
            "kstep export of $name writes a STEP file with a manifold solid".config(enabled = available) {
                val script = copyFixture("$name.kstep.kts")
                val out = File(workDir, "$name.step").apply { delete() }
                val result = runner.run("export", script.name, "--out", out.name)
                result.exitCode shouldBe 0
                result.stdout.trim() shouldBe "Exported 1 root(s) with 1 geometric shape(s) to ${out.name}"
                out.readText() shouldContain "MANIFOLD_SOLID_BREP"
            }
        }

        "kstep render of the plate with hole writes a non-empty SVG".config(enabled = available) {
            val script = copyFixture("hello-plate-with-hole.kstep.kts")
            val out = File(workDir, "plate.svg").apply { delete() }
            val result = runner.run("render", script.name, "--format", "svg", "-o", out.name)
            result.exitCode shouldBe 0
            (out.length() > 0) shouldBe true
            out.readText() shouldContain "<svg"
        }

        "kstep render of the plate with hole writes a structurally valid GLB".config(enabled = available) {
            val script = copyFixture("hello-plate-with-hole.kstep.kts")
            val out = File(workDir, "plate.glb").apply { delete() }
            val result = runner.run("render", script.name, "--format", "glb", "-o", out.name)
            result.exitCode shouldBe 0
            val bytes = out.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buf.getInt(0) shouldBe 0x46546C67 // "glTF"
            buf.getInt(4) shouldBe 2
            buf.getInt(8) shouldBe bytes.size
            val jsonLength = buf.getInt(12)
            val json = String(bytes, 20, jsonLength, Charsets.UTF_8)
            val count = Regex("\"count\"\\s*:\\s*(\\d+)").findAll(json).map { it.groupValues[1].toInt() }.max()
            (count > 0 && count <= OcctKernel.MAX_TRIANGLES * 3) shouldBe true
        }

        "no generated artefact is left in src/test/resources" {
            val resources = File("src/test/resources")
            val strays =
                resources
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension in setOf("step", "svg", "glb", "stp") }
                    .map { it.name }
                    .filterNot { it.startsWith("export-golden-") } // the committed golden files
            strays shouldBe emptyList()
        }
    })
