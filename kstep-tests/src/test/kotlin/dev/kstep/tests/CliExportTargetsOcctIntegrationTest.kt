package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

private const val BLOCK =
    "export { step(\"box.step\"); glb(\"box.glb\"); stl(\"box.stl\"); threeMf(\"box.3mf\") }"

/**
 * Subprocess-level coverage of the mesh targets of `kstep export --target/--all-targets` (ADR-0065). The class name
 * carries `Occt` so the CI skip gate (which inspects `TEST-*Occt*.xml`) covers these tests.
 */
class CliExportTargetsOcctIntegrationTest :
    StringSpec({
        val root =
            File(
                "build/cli-export-targets-occt-test",
            ).absoluteFile.apply { deleteRecursively() }.apply { mkdirs() }
        val runner = CliProcessRunner(root)
        val available = OcctKernel.availability() is OcctAvailability.Available
        var counter = 0

        fun case(block: String = BLOCK): File {
            val dir = File(root, "case-${counter++}").apply { mkdirs() }
            val text =
                requireNotNull(
                    CliExportTargetsOcctIntegrationTest::class.java.getResourceAsStream(
                        "/export-box-20x30x40.kstep.kts",
                    ),
                ).use { String(it.readBytes(), Charsets.UTF_8) }
            File(
                dir,
                "s.kstep.kts",
            ).writeText(text.replace("    root(definition)\n", "    root(definition)\n    $block\n"))
            return dir
        }

        fun json(stdout: String): JsonObject = Json.parseToJsonElement(stdout.trim()).jsonObject

        "all four declared targets are written with the right magic bytes".config(enabled = available) {
            val dir = case()
            val r = runner.run("export", File(dir, "s.kstep.kts").path, "--all-targets", "--output", "json")
            r.exitCode shouldBe 0
            val targets = json(r.stdout)["targets"]!!.jsonArray.map { it.jsonObject["format"]!!.jsonPrimitive.content }
            targets shouldBe listOf("step", "glb", "stl", "3mf")
            File(dir, "box.step").readText().lines().first() shouldBe "ISO-10303-21;"
            File(dir, "box.glb").readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII) shouldBe "glTF"
            val stl = File(dir, "box.stl").readBytes()
            val triangles =
                (stl[80].toInt() and 0xFF) or ((stl[81].toInt() and 0xFF) shl 8) or
                    ((stl[82].toInt() and 0xFF) shl 16) or ((stl[83].toInt() and 0xFF) shl 24)
            stl.size shouldBe 84 + 50 * triangles
            File(dir, "box.3mf").readBytes().copyOfRange(0, 2).toString(Charsets.US_ASCII) shouldBe "PK"
            ZipFile(File(dir, "box.3mf")).use { (it.getEntry("3D/3dmodel.model") != null) shouldBe true }
            dir.list().orEmpty().filter { it.endsWith(".tmp") } shouldBe emptyList()
        }

        "the bytes of a mesh target equal those of kstep render, 3MF and GLB included".config(enabled = available) {
            val dir = case()
            val script = File(dir, "s.kstep.kts").path
            runner.run("export", script, "--all-targets").exitCode shouldBe 0
            for ((format, name) in listOf("stl" to "box.stl", "3mf" to "box.3mf", "glb" to "box.glb")) {
                val ref = File(dir, "ref.$format")
                runner.run("render", script, "-f", format, "-o", ref.path).exitCode shouldBe 0
                File(dir, name).readBytes().contentEquals(ref.readBytes()) shouldBe true
            }
        }

        "--tolerance changes the STL and is reported in meshExport".config(enabled = available) {
            val dir = case(block = "export { stl(\"box.stl\") }")
            val script = File(dir, "s.kstep.kts").path
            runner.run("export", script, "--target", "stl").exitCode shouldBe 0
            val coarse = File(dir, "box.stl").readBytes()
            val fine =
                runner.run(
                    "export",
                    script,
                    "--target",
                    "stl",
                    "--tolerance",
                    "0.001",
                    "--force",
                    "--output",
                    "json",
                )
            fine.exitCode shouldBe 0
            val mesh =
                json(fine.stdout)["targets"]!!
                    .jsonArray
                    .single()
                    .jsonObject["meshExport"]!!
                    .jsonObject
            mesh["toleranceMm"]!!.jsonPrimitive.double shouldBe 0.001
            mesh["format"]!!.jsonPrimitive.content shouldBe "stl"
            (File(dir, "box.stl").readBytes().size >= coarse.size) shouldBe true
        }

        "an existing second target stops the run before anything is written".config(enabled = available) {
            val dir = case()
            val keep = File(dir, "box.stl").apply { writeText("keep") }
            val r = runner.run("export", File(dir, "s.kstep.kts").path, "--all-targets", "--output", "json")
            r.exitCode shouldBe 1
            json(r.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "target_exists"
            keep.readText() shouldBe "keep"
            File(dir, "box.step").exists() shouldBe false
            File(dir, "box.glb").exists() shouldBe false
            File(dir, "box.3mf").exists() shouldBe false
            runner.run("export", File(dir, "s.kstep.kts").path, "--all-targets", "--force").also {
                it.exitCode shouldBe 0
                it.stdout shouldContain "Wrote "
            }
        }
    })
