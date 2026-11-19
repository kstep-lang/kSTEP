package dev.kstep.tests

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

private const val NO_OCCT = "-Dkstep.occt.bridge.library=/nonexistent/libkstep_occt_bridge.so"
private const val BLOCK_LINE = "export { step(\"export-block-smoke.step\") }"

/**
 * Subprocess-level coverage of `kstep export --target/--all-targets` (ADR-0065) with STEP-only fixtures, so it needs
 * neither OCCT nor PlaneGCS and runs everywhere. Mesh targets are exercised here only for their deterministic failure
 * without a native bridge; the real mesh files are in [CliExportTargetsOcctIntegrationTest].
 */
class CliExportTargetsIntegrationTest :
    StringSpec({
        val root = File("build/cli-export-targets-test").absoluteFile.apply { deleteRecursively() }.apply { mkdirs() }
        val runner = CliProcessRunner(root)
        var counter = 0

        fun resourceText(name: String): String =
            requireNotNull(CliExportTargetsIntegrationTest::class.java.getResourceAsStream("/$name")) { name }
                .use { String(it.readBytes(), Charsets.UTF_8) }

        /** A fresh folder with the script (the export block line replaced by [block], or removed if null). */
        fun case(block: String? = BLOCK_LINE): File {
            val dir = File(root, "case-${counter++}").apply { mkdirs() }
            val text = resourceText("export-block.kstep.kts")
            File(dir, "s.kstep.kts").writeText(
                if (block ==
                    null
                ) {
                    text.replace(BLOCK_LINE, "")
                } else {
                    text.replace(BLOCK_LINE, block)
                },
            )
            return dir
        }

        fun json(stdout: String): JsonObject = Json.parseToJsonElement(stdout.trim()).jsonObject

        fun mask(step: String): String = step.replace(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[^']*"), "TS")

        fun tmpFiles(dir: File): List<String> = dir.list().orEmpty().filter { it.endsWith(".tmp") }

        "--target step writes the declared name next to the script, text and JSON" {
            val dir = case()
            val script = File(dir, "s.kstep.kts").path
            val r = runner.run("export", script, "--target", "step")
            r.exitCode shouldBe 0
            File(dir, "export-block-smoke.step").readText().lines().first() shouldBe "ISO-10303-21;"
            r.stdout shouldContain "Exported 2 root(s) to "
            tmpFiles(dir) shouldBe emptyList()

            File(dir, "export-block-smoke.step").delete()
            val j = runner.run("export", script, "--target", "export-block-smoke.step", "--output", "json")
            j.exitCode shouldBe 0
            j.stdout
                .trim()
                .lines()
                .size shouldBe 1
            val doc = json(j.stdout)
            doc["status"]!!.jsonPrimitive.content shouldBe "success"
            doc["command"]!!.jsonPrimitive.content shouldBe "export"
            doc["rootCount"]!!.jsonPrimitive.int shouldBe 2
            val t = doc["targets"]!!.jsonArray.single().jsonObject
            t["format"]!!.jsonPrimitive.content shouldBe "step"
            t["fileName"]!!.jsonPrimitive.content shouldBe "export-block-smoke.step"
            t["replaced"]!!.jsonPrimitive.boolean shouldBe false
            t["byteLength"]!!.jsonPrimitive.int shouldBe File(dir, "export-block-smoke.step").length().toInt()
            File(t["outPath"]!!.jsonPrimitive.content).isFile shouldBe true
        }

        "--out-dir writes there, also through a symlinked folder, and the folder is never created" {
            val dir = case()
            val script = File(dir, "s.kstep.kts").path
            val out = File(dir, "out").apply { mkdirs() }
            runner.run("export", script, "--all-targets", "--out-dir", out.path).exitCode shouldBe 0
            File(out, "export-block-smoke.step").isFile shouldBe true
            File(dir, "export-block-smoke.step").exists() shouldBe false

            val real = File(dir, "real").apply { mkdirs() }
            val link = File(dir, "link").toPath()
            val linked =
                try {
                    Files.createSymbolicLink(link, real.toPath())
                    true
                } catch (_: Exception) {
                    false
                }
            if (linked) {
                val r = runner.run("export", script, "--all-targets", "--out-dir", link.toString(), "--output", "json")
                r.exitCode shouldBe 0
                File(real, "export-block-smoke.step").isFile shouldBe true
                json(r.stdout)["outDir"]!!.jsonPrimitive.content shouldBe real.toPath().toRealPath().toString()
            }

            val missing = File(dir, "missing")
            val r = runner.run("export", script, "--all-targets", "--out-dir", missing.path, "--output", "json")
            r.exitCode shouldBe 1
            json(r.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "out_dir_invalid"
            missing.exists() shouldBe false
            val asFile = File(dir, "afile").apply { writeText("x") }
            runner.run("export", script, "--all-targets", "--out-dir", asFile.path, "--output", "json").also {
                it.exitCode shouldBe 1
                json(it.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "out_dir_invalid"
            }
        }

        "the old forms behave as before, also for a script with an export block" {
            val withBlock = case()
            val without = case(block = null)
            val a = runner.run("export", File(withBlock, "s.kstep.kts").path, "--out", File(withBlock, "o.step").path)
            val b = runner.run("export", File(without, "s.kstep.kts").path, "--out", File(without, "o.step").path)
            a.exitCode shouldBe 0
            b.exitCode shouldBe 0
            a.stderr shouldBe ""
            a.stderr shouldBe b.stderr
            a.stdout.replace(withBlock.path, "<d>") shouldBe b.stdout.replace(without.path, "<d>")
            mask(File(withBlock, "o.step").readText()) shouldBe mask(File(without, "o.step").readText())
            File(withBlock, "export-block-smoke.step").exists() shouldBe false

            // the derived name, no hint about the declared target
            val d = runner.run("export", File(withBlock, "s.kstep.kts").path, "--output", "json")
            d.exitCode shouldBe 0
            d.stderr shouldBe ""
            File(withBlock, "s.step").isFile shouldBe true
            d.stdout shouldNotContain "exportTargets"
            val v =
                runner.run(
                    "export",
                    File(withBlock, "s.kstep.kts").path,
                    "--validate",
                    "--require-geometry",
                    "--output",
                    "json",
                )
            v.exitCode shouldBe 1
            json(v.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "geometry_required"

            // the content of --target step equals the content of --out (time stamp masked)
            runner.run("export", File(withBlock, "s.kstep.kts").path, "--target", "step").exitCode shouldBe 0
            mask(File(withBlock, "export-block-smoke.step").readText()) shouldBe
                mask(File(withBlock, "o.step").readText())

            // render's public JSON never lists the declared names
            val render = runner.run("render", File(withBlock, "s.kstep.kts").path, "--output", "json", "-f", "text")
            render.exitCode shouldBe 0
            render.stdout shouldNotContain "exportTargets"
        }

        "an existing target stops the run without --force and replaces with it" {
            val dir = case()
            val script = File(dir, "s.kstep.kts").path
            val target = File(dir, "export-block-smoke.step").apply { writeText("keep me") }
            val stamp = target.lastModified()
            val r = runner.run("export", script, "--all-targets", "--output", "json")
            r.exitCode shouldBe 1
            json(r.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "target_exists"
            json(r.stdout)["message"]!!.jsonPrimitive.content shouldContain "Nothing was written"
            target.readText() shouldBe "keep me"
            target.lastModified() shouldBe stamp
            val text = runner.run("export", script, "--all-targets")
            text.exitCode shouldBe 1
            text.stderr shouldContain "use --force to replace it"

            val forced = runner.run("export", script, "--all-targets", "--force", "--output", "json")
            forced.exitCode shouldBe 0
            json(forced.stdout)["targets"]!!
                .jsonArray
                .single()
                .jsonObject["replaced"]!!
                .jsonPrimitive.boolean shouldBe
                true
            target.readText().lines().first() shouldBe "ISO-10303-21;"
            tmpFiles(dir) shouldBe emptyList()
        }

        "a symlink or a directory in the target's place is refused, also with --force" {
            val dir = case()
            val script = File(dir, "s.kstep.kts").path
            val victim = File(dir, "victim.txt").apply { writeText("keep") }
            val target = File(dir, "export-block-smoke.step")
            val linked =
                try {
                    Files.createSymbolicLink(target.toPath(), victim.toPath())
                    true
                } catch (_: Exception) {
                    false
                }
            if (linked) {
                val r = runner.run("export", script, "--all-targets", "--force", "--output", "json")
                r.exitCode shouldBe 1
                json(r.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "export_target_invalid"
                victim.readText() shouldBe "keep"
                Files.delete(target.toPath())
            }
            target.mkdir()
            val d = runner.run("export", script, "--all-targets", "--force", "--output", "json")
            d.exitCode shouldBe 1
            json(d.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "export_target_invalid"
            target.isDirectory shouldBe true
        }

        "errors: no block, an undeclared target, a bad --tolerance, a compile error" {
            val dir = case(block = null)
            val script = File(dir, "s.kstep.kts").path
            runner.run("export", script, "--all-targets", "--output", "json").also {
                it.exitCode shouldBe 1
                json(it.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "no_export_targets"
            }
            val declared = File(case(), "s.kstep.kts").path
            runner.run("export", declared, "--target", "stl", "--output", "json").also {
                it.exitCode shouldBe 1
                json(it.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "export_target_not_declared"
            }
            for (tol in listOf("0.1", "-1", "NaN", "0")) {
                runner.run("export", declared, "--target", "step", "--tolerance", tol, "--output", "json").also {
                    it.exitCode shouldBe 1
                    json(it.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "invalid_request"
                }
            }
            File(File(declared).parentFile, "export-block-smoke.step").exists() shouldBe false

            val broken = case(block = "export { step(\"a.step\") ")
            runner.run("export", File(broken, "s.kstep.kts").path, "--target", "step", "--output", "json").also {
                it.exitCode shouldBe 1
                val doc = json(it.stdout)
                doc["status"]!!.jsonPrimitive.content shouldBe "error"
                doc["command"]!!.jsonPrimitive.content shouldBe "export"
            }
        }

        "a hostile declared name in the script is refused by the script host before the CLI sees it" {
            val bad = case(block = "export { step(\"../evil.step\") }")
            val r = runner.run("export", File(bad, "s.kstep.kts").path, "--all-targets", "--output", "json")
            r.exitCode shouldBe 1
            File(bad.parentFile, "evil.step").exists() shouldBe false
            File(bad, "evil.step").exists() shouldBe false
        }

        "mesh targets without a native bridge: geometry_unavailable, nothing written, not even the STEP file" {
            val dir = case(block = "export { step(\"a.step\"); stl(\"a.stl\") }")
            val script = File(dir, "s.kstep.kts").path
            val r = runner.run("export", script, "--all-targets", "--output", "json", jvmArgs = listOf(NO_OCCT))
            r.exitCode shouldBe 1
            json(r.stdout)["errorKind"]!!.jsonPrimitive.content shouldBe "geometry_unavailable"
            File(dir, "a.step").exists() shouldBe false
            File(dir, "a.stl").exists() shouldBe false
            tmpFiles(dir) shouldBe emptyList()

            val text = runner.run("export", script, "--target", "step", "--target", "stl", jvmArgs = listOf(NO_OCCT))
            text.exitCode shouldBe 1
            text.stderr shouldContain "kstep export:"
            File(dir, "a.step").exists() shouldBe false

            // the STEP target alone still works without a bridge
            runner.run("export", script, "--target", "step", jvmArgs = listOf(NO_OCCT)).exitCode shouldBe 0
            File(dir, "a.step").isFile shouldBe true
        }
    })
