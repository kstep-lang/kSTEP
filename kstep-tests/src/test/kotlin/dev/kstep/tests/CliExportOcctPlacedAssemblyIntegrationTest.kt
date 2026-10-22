package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.shape.Ap242PlacementReader
import dev.kstep.shape.RigidPlacement
import dev.kstep.shape.Rotation
import dev.kstep.shape.Vec3
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    fun pd(id: String) = productDefinition("${'$'}id-D") {
        formation = productDefinitionFormation("${'$'}id-F") { ofProduct = product(id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow() }.getOrThrow()
        frameOfReference = defCtx
    }.getOrThrow()
    """

/**
 * Subprocess-level coverage of `kstep export` with placed assemblies (ADR-0028). The class name
 * carries `Occt` so that the CI skip gate (which inspects `TEST-*Occt*.xml`) sees these tests.
 */
class CliExportOcctPlacedAssemblyIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-export-placed-assembly-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun copyFixture(name: String): File {
            val target = File(workDir, name)
            val resource =
                requireNotNull(
                    CliExportOcctPlacedAssemblyIntegrationTest::class.java.getResourceAsStream("/$name"),
                ) { name }
            resource.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            return target
        }

        "the placed-assembly fixture exports with four placed usages and prints them".config(enabled = available) {
            val script = copyFixture("export-placed-assembly.kstep.kts")
            val out = File(workDir, "placed.step").apply { delete() }

            val result = runner.run("export", script.name, "--out", out.name)

            result.exitCode shouldBe 0
            result.stdout.trim() shouldBe
                "Exported 4 root(s) with 3 geometric shape(s) and 4 placed usage(s) to ${out.name}"
            val placements =
                Ap242PlacementReader.read(
                    Part21Reader.readRawDocument(out.readText(), Part21ReadMode.TOLERANT),
                )
            placements.getValue("NAUO-B") shouldBe RigidPlacement(Vec3(30.0, 5.0, 10.0), Rotation.about(Vec3.Z, 90.0))
            placements.getValue("NAUO-S2").translation shouldBe Vec3(8.0, 30.0, 10.0)
            placements.getValue("NAUO-H") shouldBe RigidPlacement.IDENTITY
        }

        "--output json reports placementCount and usageCount".config(enabled = available) {
            val script = copyFixture("export-placed-assembly.kstep.kts")
            val result = runner.run("export", script.name, "--out", "placed-json.step", "--output", "json")

            result.exitCode shouldBe 0
            val geometry = Json.parseToJsonElement(result.stdout.trim()).jsonObject["geometry"]!!.jsonObject
            geometry["merged"]!!.jsonPrimitive.boolean shouldBe true
            geometry["shapeCount"]!!.jsonPrimitive.int shouldBe 3
            geometry["placementCount"]!!.jsonPrimitive.int shouldBe 3
            geometry["usageCount"]!!.jsonPrimitive.int shouldBe 4
        }

        "--validate together with placements passes".config(enabled = available) {
            val script = copyFixture("export-placed-assembly.kstep.kts")
            val result = runner.run("export", script.name, "--out", "placed-validate.step", "--validate")

            result.exitCode shouldBe 0
            result.stdout shouldContain "Model validation passed"
        }

        "a duplicate placement exits 1 with KSTEP-S-008, writes nothing and leaves an existing file untouched".config(
            enabled = available,
        ) {
            val script = File(workDir, "duplicate-placement.kstep.kts")
            script.writeText(
                PRELUDE +
                    """
                    val a = pd("DU-A")
                    val b = pd("DU-B")
                    val u = nextAssemblyUsageOccurrence("DU-U") { name = "u"; relatingProductDefinition = a; relatedProductDefinition = b }.getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(u)
                        shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0))
                        place(u, translation = vec(1.0, 0.0, 0.0))
                        place(u, translation = vec(2.0, 0.0, 0.0))
                    }
                    """.trimIndent(),
            )
            val out = File(workDir, "duplicate-placement.step")
            out.writeText("PRE-EXISTING")

            val result = runner.run("export", script.name, "--out", out.name, "--validate", "--output", "json")

            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-008"
            json["message"]!!.jsonPrimitive.content shouldContain "placed more than once"
            out.readText() shouldBe "PRE-EXISTING"
        }

        "a non-orthonormal Rotation.fromAxes is rejected with the cause named".config(enabled = available) {
            val script = File(workDir, "bad-rotation.kstep.kts")
            script.writeText(
                PRELUDE +
                    """
                    val a = pd("BR-A")
                    val b = pd("BR-B")
                    val u = nextAssemblyUsageOccurrence("BR-U") { name = "u"; relatingProductDefinition = a; relatedProductDefinition = b }.getOrThrow()
                    stepFile(fileName = "x.step") {
                        root(u)
                        shape(b, OcctKernel.makeBox(1.0, 1.0, 1.0))
                        place(u, rotation = Rotation.fromAxes(vec(0.0, 0.0, 1.0), vec(0.0, 0.0, 1.0)))
                    }
                    """.trimIndent(),
            )
            val result = runner.run("export", script.name, "--out", "bad-rotation.step", "--output", "json")

            result.exitCode shouldBe 1
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["code"]!!.jsonPrimitive.content shouldBe "KSTEP-S-003"
            json["message"]!!.jsonPrimitive.content shouldContain "orthogonal"
            result.stdout shouldNotContain "\tat "
        }

        "the CLI leaves nothing behind next to the script except its own output".config(enabled = available) {
            val script = copyFixture("export-placed-assembly.kstep.kts")
            val before = workDir.list().orEmpty().toSet()
            val out = File(workDir, "placed-listing.step")
            runner.run("export", script.name, "--out", out.name).exitCode shouldBe 0
            val after = workDir.list().orEmpty().toSet()
            (after - before) shouldBe setOf(out.name)
        }
    })
