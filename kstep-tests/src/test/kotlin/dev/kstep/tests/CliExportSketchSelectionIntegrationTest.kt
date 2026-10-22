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
 * Subprocess-level coverage of Welle L (ADR-0031): the example scripts through the real `kstep export`
 * and `kstep import`, plus the structured failure paths. Fixtures are copied into `build/` first, so no
 * artefact ever lands in `src/test/resources`.
 */
class CliExportSketchSelectionIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-sketch-selection-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val occt = OcctKernel.availability() is OcctAvailability.Available
        val both = occt && PlaneGcsSolver.availability() is PlaneGcsAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            requireNotNull(CliExportSketchSelectionIntegrationTest::class.java.getResourceAsStream("/$name")) {
                "test resource /$name not found on classpath"
            }.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        // script, expected volume, expected faces
        val cases =
            listOf(
                Triple("export-box-fillet-z-edges", 15965.664, 10),
                Triple("export-box-chamfer-top-edges", 15921.333, 10),
                Triple("export-pocket-tracked-fillet", 14965.664, 14),
            )

        fun exportAndImport(
            name: String,
            volume: Double,
            faces: Int,
        ) {
            val script = copyFixture("$name.kstep.kts")
            val out = File(workDir, "$name.step").apply { delete() }
            val export = runner.run("export", script.name, "--out", out.name)
            export.exitCode shouldBe 0
            export.stdout.trim() shouldBe "Exported 1 root(s) with 1 geometric shape(s) to ${out.name}"
            out.readText() shouldContain "MANIFOLD_SOLID_BREP"
            val imported = runner.run("import", out.name, "--output", "json")
            imported.exitCode shouldBe 0
            val json = Json.parseToJsonElement(imported.stdout.trim()).jsonObject
            val part = json["parts"]!!.jsonArray.single().jsonObject
            part["volume"]!!.jsonPrimitive.double shouldBe (volume plusOrMinus 1e-2)
            part["topology"]!!.jsonObject["faces"]!!.jsonPrimitive.int shouldBe faces
        }

        cases.forEach { (name, volume, faces) ->
            "kstep export of $name writes a STEP solid that re-imports with V = $volume".config(enabled = occt) {
                exportAndImport(name, volume, faces)
            }
        }

        "kstep export of the sketch plate re-imports with V = 7717.257".config(enabled = both) {
            exportAndImport("export-sketch-plate-with-hole", 7717.257, 7)
        }

        "an under-constrained sketch exits non-zero with KSTEP-S-010 and no stack trace" {
            val script = copyFixture("hello-sketch-unconstrained.kstep.kts")
            val result = runner.run("export", script.name, "--out", "never.step")
            (result.exitCode != 0) shouldBe true
            val output = result.stdout + result.stderr
            output shouldContain "KSTEP-S-010"
            output shouldContain "UNDER_CONSTRAINED"
            output shouldNotContain "\tat "
            File(workDir, "never.step").exists() shouldBe false
        }

        "--output json carries the structured sketch code" {
            val script = copyFixture("hello-sketch-unconstrained.kstep.kts")
            val result = runner.run("export", script.name, "--out", "never.step", "--output", "json")
            (result.exitCode != 0) shouldBe true
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "error"
            json["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-010"
            json["message"]!!.jsonPrimitive.content shouldContain "UNDER_CONSTRAINED"
        }

        "sketch solving with an unavailable PlaneGCS bridge exits non-zero with KSTEP-S-009 and no stack trace" {
            val script = copyFixture("export-sketch-plate-with-hole.kstep.kts")
            val result =
                runner.run(
                    "export",
                    script.name,
                    "--out",
                    "never-solved.step",
                    jvmArgs = listOf("-Dkstep.planegcs.bridge.library=/nonexistent/libkstep_planegcs_bridge.so"),
                )
            (result.exitCode != 0) shouldBe true
            val output = result.stdout + result.stderr
            output shouldContain "KSTEP-S-009"
            output shouldContain "PlaneGCS"
            output shouldNotContain "\tat "
            File(workDir, "never-solved.step").exists() shouldBe false
        }

        "an edge selection that matches nothing exits non-zero with KSTEP-S-011 and the candidate list".config(
            enabled = occt,
        ) {
            val script = File(workDir, "bad-selection.kstep.kts")
            script.writeText(
                copyFixture("export-box-fillet-z-edges.kstep.kts").readText().replace(
                    "Edges.parallelTo(Direction3.Z), 2.0, expected = 4",
                    "Edges.ofType(EdgeKind.CIRCLE), 2.0",
                ),
            )
            val result = runner.run("export", script.name, "--out", "bad-selection.step")
            (result.exitCode != 0) shouldBe true
            val output = result.stdout + result.stderr
            output shouldContain "KSTEP-S-011"
            output shouldContain "matched no edge"
            output shouldContain "#0 LINE"
            output shouldNotContain "\tat "
        }

        "no generated artefact is left in src/test/resources" {
            val strays =
                File("src/test/resources")
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension in setOf("step", "svg", "glb", "stp") }
                    .map { it.name }
                    .filterNot { it == "export-golden-assembly.step" }
            strays shouldBe emptyList()
        }
    })
