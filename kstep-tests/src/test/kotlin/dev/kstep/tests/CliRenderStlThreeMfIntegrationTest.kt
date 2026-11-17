package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

private const val OCCT_OVERRIDE = "kstep.occt.bridge.library"

/** Subprocess coverage of `kstep render -f stl|3mf` (ADR-0049). The class name carries `Occt` for the CI skip gate. */
class CliRenderStlThreeMfIntegrationTest :
    StringSpec({
        val workDir = File("build/cli-render-stl-3mf-test").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun fixture(name: String): File {
            val target = File(workDir, name)
            requireNotNull(CliRenderStlThreeMfIntegrationTest::class.java.getResourceAsStream("/$name")).use { i ->
                target.outputStream().use { o -> i.copyTo(o) }
            }
            return target
        }

        fun json(text: String) = Json.parseToJsonElement(text.trim()).jsonObject

        "-f stl writes a binary STL with the derived name and a meshExport report".config(enabled = available) {
            val script = fixture("hello-box.kstep.kts")
            File(workDir, "hello-box.stl").delete()
            val r = runner.run("render", "--output", "json", script.name, "-f", "stl")
            r.exitCode shouldBe 0
            val j = json(r.stdout)
            j["format"]?.jsonPrimitive?.content shouldBe "stl"
            val m = j["meshExport"]!!.jsonObject
            m["triangleCount"]?.jsonPrimitive?.int shouldBe 12
            m["unit"]?.jsonPrimitive?.content shouldBe "mm"
            m["watertight"]?.jsonPrimitive?.boolean shouldBe true
            File(workDir, "hello-box.stl").length() shouldBe (84L + 12 * 50)
        }

        "-f 3mf writes a package and auto resolves from the --out extension".config(enabled = available) {
            val script = fixture("hello-box.kstep.kts")
            File(workDir, "hello-box.3mf").delete()
            val r = runner.run("render", "--output", "json", script.name, "-f", "3mf")
            r.exitCode shouldBe 0
            json(r.stdout)["format"]?.jsonPrimitive?.content shouldBe "3mf"
            ZipFile(File(workDir, "hello-box.3mf")).use { z -> (z.getEntry("3D/3dmodel.model") != null) shouldBe true }
            val out = File(workDir, "viaext.stl").apply { delete() }
            val r2 = runner.run("render", script.name, "--out", out.name)
            r2.exitCode shouldBe 0
            out.length() shouldBe (84L + 12 * 50)
        }

        "colours are dropped with a stderr warning for stl and kept for 3mf".config(enabled = available) {
            val script = fixture("export-colour-assembly-two-parts.kstep.kts")
            val stl = runner.run("render", "--output", "json", script.name, "-f", "stl", "--out", "col.stl")
            stl.exitCode shouldBe 0
            stl.stderr shouldContain "stl carries no colours"
            json(stl.stdout)["meshExport"]!!.jsonObject["colorsDropped"]?.jsonPrimitive?.boolean shouldBe true
            val mf = runner.run("render", "--output", "json", script.name, "-f", "3mf", "--out", "col.3mf")
            mf.exitCode shouldBe 0
            json(mf.stdout)["meshExport"]!!.jsonObject["colorsDropped"]?.jsonPrimitive?.boolean shouldBe false
        }

        "--tolerance is accepted for stl and rejected elsewhere or when invalid".config(enabled = available) {
            val script = fixture("hello-box.kstep.kts")
            runner.run("render", script.name, "-f", "stl", "--tolerance", "0.05", "--out", "tol.stl").exitCode shouldBe
                0
            runner.run("render", script.name, "-f", "svg", "--tolerance", "0.05").exitCode shouldBe 1
            runner.run("render", script.name, "-f", "stl", "--tolerance", "abc").exitCode shouldBe 1
            val bad = runner.run("render", script.name, "-f", "stl", "--tolerance", "NaN", "--out", "nan.stl")
            bad.exitCode shouldBe 1
            File(workDir, "nan.stl").exists() shouldBe false
            runner.run("render", script.name, "-f", "stl", "--tolerance", "-1", "--out", "neg.stl").exitCode shouldBe 1
            runner.run("render", script.name, "-f", "svg", "--require-watertight").exitCode shouldBe 1
        }

        "--require-watertight passes on a closed mesh".config(enabled = available) {
            val script = fixture("hello-box.kstep.kts")
            runner.run("render", script.name, "-f", "stl", "--require-watertight", "--out", "wt.stl").exitCode shouldBe
                0
        }

        "a script without geometry exits 1 and writes no stl/3mf file (no text card inside a printer file)" {
            val script = File(workDir, "nogeo.kstep.kts")
            script.writeText(
                """
                val appCtx = applicationContext { application = "x" }.getOrThrow()
                val prodCtx = productContext { name = "e"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
                val defCtx = productDefinitionContext { name = "e"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
                val part = product("N-1") { name = "N"; frameOfReference = setOf(prodCtx) }.getOrThrow()
                val f = productDefinitionFormation("N-1-F") { ofProduct = part }.getOrThrow()
                val d = productDefinition("N-1-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
                stepFile(fileName = "n.step") { root(d) }
                """.trimIndent(),
            )
            for (ext in listOf("stl", "3mf")) {
                File(workDir, "nogeo.$ext").delete()
                val r = runner.run("render", "--output", "json", script.name, "-f", ext)
                r.exitCode shouldBe 1
                json(r.stdout)["errorKind"]?.jsonPrimitive?.content shouldBe "geometry_unavailable"
                File(workDir, "nogeo.$ext").exists() shouldBe false
            }
        }

        "-f stl fails predictably when the native bridge is unavailable and writes no file" {
            val script = fixture("hello-box.kstep.kts")
            File(workDir, "nobridge.stl").delete()
            val r =
                runner.run(
                    "render",
                    script.name,
                    "-f",
                    "stl",
                    "--out",
                    "nobridge.stl",
                    jvmArgs = listOf("-D$OCCT_OVERRIDE=/nonexistent/libkstep_occt_bridge.so"),
                )
            r.exitCode shouldBe 1
            File(workDir, "nobridge.stl").exists() shouldBe false
        }

        "a directory as --out reports an io error for stl".config(enabled = available) {
            val script = fixture("hello-box.kstep.kts")
            File(workDir, "adir.stl").mkdirs()
            runner.run("render", script.name, "-f", "stl", "--out", "adir.stl").exitCode shouldBe 1
        }

        "hostile --out names (unicode, spaces, very long) write correctly".config(enabled = available) {
            val script = fixture("hello-box.kstep.kts")
            val name = "bauteil \u00e4\u00f6\u00fc ${"x".repeat(60)}.3mf"
            runner.run("render", script.name, "-f", "3mf", "--out", name).exitCode shouldBe 0
            File(workDir, name).exists() shouldBe true
        }

        "import --render keeps rejecting stl and 3mf, and asciidoc format stays svg/png" {
            val stepFile = File(workDir, "x.step").apply { writeText("ISO-10303-21;\nEND-ISO-10303-21;\n") }
            runner.run("import", stepFile.name, "--render", "x.stl").exitCode shouldBe 1
            runner.run("import", stepFile.name, "--render", "x.3mf").exitCode shouldBe 1
            runner.run("asciidoc", "--input", "a.adoc", "--output", "b.adoc", "-f", "stl").exitCode shouldBe 1
        }
    })
