package dev.kstep.cli

import dev.kstep.cli.okf.ConvertTreeScanner
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

private const val NOTE = "```kstep\nval a = 1\n```\n"

class ConvertRecursiveTest :
    StringSpec({
        class Run(
            val code: Int,
            val out: String,
            val err: String,
        )

        fun run(
            input: Path,
            outDir: Path? = null,
            target: ConvertTarget = ConvertTarget.KTS,
            force: Boolean = false,
            json: Boolean = false,
            recursive: Boolean = true,
            limits: ConvertDirLimits = ConvertDirLimits(),
            beforeDescend: (Path) -> Unit = {},
        ): Run {
            val o = ByteArrayOutputStream()
            val e = ByteArrayOutputStream()
            val code =
                runConvert(
                    CliCommand.Convert(
                        input.toString(),
                        target,
                        outDir?.toString(),
                        force,
                        json,
                        null,
                        emptyList(),
                        recursive,
                    ),
                    PrintStream(o, true, "UTF-8"),
                    PrintStream(e, true, "UTF-8"),
                    {},
                    limits,
                    beforeDescend,
                )
            return Run(code, o.toString("UTF-8"), e.toString("UTF-8"))
        }

        fun tmp(): Path = Files.createTempDirectory("kstep-rec").toRealPath()

        fun put(
            root: Path,
            rel: String,
            text: String = NOTE,
        ): Path {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            return Files.writeString(p, text)
        }

        fun files(root: Path): List<String> =
            Files.walk(root).use { s ->
                s
                    .filter { !Files.isDirectory(it) }
                    .map { root.relativize(it).toString() }
                    .sorted()
                    .collect(Collectors.toList())
            }

        fun symlinkOrSkip(
            link: Path,
            target: Path,
        ): Boolean =
            try {
                Files.createSymbolicLink(link, target)
                true
            } catch (_: Exception) {
                false
            }

        "happy path mirrors the tree, sorted, relative paths" {
            val d = tmp()
            put(d, "B.md")
            put(d, "a.md")
            put(d, "z.md")
            put(d, "x/y/z/deep.md")
            put(d, "x/mid.md")
            val out = Files.createDirectory(tmp().resolve("o"))
            val r = run(d, out, json = true)
            r.code shouldBe 0
            files(out) shouldBe
                listOf("B.kstep.kts", "a.kstep.kts", "x/mid.kstep.kts", "x/y/z/deep.kstep.kts", "z.kstep.kts")
            val j = Json.parseToJsonElement(r.out).jsonObject
            j["recursive"]!!.jsonPrimitive.content shouldBe "true"
            val written = j["written"]!!.jsonArray.map { it.jsonObject }
            written.map { it["source"]!!.jsonPrimitive.content } shouldBe
                listOf("B.md", "a.md", "x/mid.md", "x/y/z/deep.md", "z.md")
            written[3]["target"]!!.jsonPrimitive.content shouldBe "x/y/z/deep.kstep.kts"
            written[3]["name"]!!.jsonPrimitive.content shouldBe "deep.kstep.kts"
            r.out shouldNotContain d.toString()
        }

        "a target file named like a target folder fails in planning, folder sorted second (kts)" {
            val d = tmp()
            put(d, "x.md")
            put(d, "x.kstep.kts/y.md")
            val out = Files.createDirectory(tmp().resolve("o"))
            val r = run(d, out, json = true)
            r.code shouldBe 1
            r.out shouldContain "KSTEP-C-009"
            files(out) shouldBe emptyList()
        }

        "a target file named like a target folder fails in planning, folder sorted first (okf)" {
            val d = tmp()
            put(d, "a.kstep.kts", "val a = 1\n")
            put(d, "a.md/b.kstep.kts", "val a = 1\n")
            val out = Files.createDirectory(tmp().resolve("o"))
            val r = run(d, out, target = ConvertTarget.OKF, json = true)
            r.code shouldBe 1
            r.out shouldContain "KSTEP-C-009"
            files(out) shouldBe emptyList()
        }

        "text output names the relative target" {
            val d = tmp()
            put(d, "s/n.md")
            val r = run(d, Files.createDirectory(tmp().resolve("o")))
            r.out shouldBe "Wrote s/n.kstep.kts\n"
        }

        "the default output is the input folder, mirrored next to the sources" {
            val d = tmp()
            put(d, "s/n.md")
            run(d).code shouldBe 0
            Files.exists(d.resolve("s/n.kstep.kts")) shouldBe true
        }

        "depth 8 passes, depth 9 fails with C-012 and writes nothing" {
            val d = tmp()
            put(d, "1/2/3/4/5/6/7/8/ok.md")
            val o1 = Files.createDirectory(tmp().resolve("o"))
            run(d, o1).code shouldBe 0
            files(o1) shouldBe listOf("1/2/3/4/5/6/7/8/ok.kstep.kts")
            put(d, "1/2/3/4/5/6/7/8/9/deep.md")
            val o2 = Files.createDirectory(tmp().resolve("o"))
            val r = run(d, o2, json = true)
            r.code shouldBe 1
            r.out shouldContain "KSTEP-C-012"
            files(o2) shouldBe emptyList()
            val d2 = tmp()
            Files.createDirectories(d2.resolve("1/2/3/4/5/6/7/8/.hidden/x"))
            run(d2, Files.createDirectory(tmp().resolve("o"))).err shouldNotContain "deeper"
        }

        "links are skipped, files and folders" {
            val d = tmp()
            val outside = tmp()
            put(outside, "ext.md")
            put(d, "real.md")
            if (symlinkOrSkip(d.resolve("l.md"), d.resolve("real.md")) &&
                symlinkOrSkip(d.resolve("ld"), outside) &&
                symlinkOrSkip(d.resolve("lo.md"), outside.resolve("ext.md"))
            ) {
                val o = Files.createDirectory(tmp().resolve("o"))
                val r = run(d, o, json = true)
                r.code shouldBe 0
                files(o) shouldBe listOf("real.kstep.kts")
                val skipped =
                    Json
                        .parseToJsonElement(r.out)
                        .jsonObject["skipped"]!!
                        .jsonArray
                        .map { it.jsonObject }
                skipped.map { it["reason"]!!.jsonPrimitive.content }.toSet() shouldBe setOf("symbolic_link")
                skipped.map { it["source"]!!.jsonPrimitive.content } shouldBe listOf("l.md", "ld", "lo.md")
            }
        }

        "isContained uses path components, not string prefixes" {
            val root = tmp().resolve("root")
            ConvertTreeScanner.isContained(root, root.resolve("a")) shouldBe true
            ConvertTreeScanner.isContained(root, root) shouldBe true
            ConvertTreeScanner.isContained(root, root.parent.resolve("rootX")) shouldBe false
            ConvertTreeScanner.isContained(root, root.resolve("..").resolve("x").normalize()) shouldBe false
        }

        "a folder swapped for a link after the check is C-013 and nothing is written" {
            val d = tmp()
            val outside = tmp()
            put(outside, "ext.md")
            put(d, "sub/n.md")
            val o = Files.createDirectory(tmp().resolve("o"))
            var swapped = false
            val r =
                run(d, o, json = true, beforeDescend = { dir ->
                    if (dir.fileName.toString() == "sub") {
                        try {
                            Files.walk(dir).use { s ->
                                s.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                            }
                            Files.createSymbolicLink(dir, outside)
                            swapped = true
                        } catch (_: Exception) {
                        }
                    }
                })
            if (swapped) {
                r.code shouldBe 1
                r.out shouldContain "KSTEP-C-013"
                files(o) shouldBe emptyList()
            }
        }

        "hidden folders and the output folder are skipped" {
            val d = tmp()
            put(d, ".obsidian/x.md")
            put(d, "n.md")
            val out = Files.createDirectory(d.resolve("out"))
            val r = run(d, out, json = true)
            r.code shouldBe 0
            r.out shouldContain "\"reason\":\"hidden_dir\""
            r.out shouldContain "\"source\":\"out/\""
            r.out shouldContain "\"reason\":\"output_dir\""
            files(out) shouldBe listOf("n.kstep.kts")
            // second run: the results of the first are not read as sources
            val again = run(d, out, force = true, json = true)
            again.code shouldBe 0
            files(out) shouldBe listOf("n.kstep.kts")
        }

        "output equal to the root skips nothing" {
            val d = tmp()
            put(d, "sub/n.md")
            val r = run(d, d, json = true)
            r.out shouldNotContain "output_dir"
            Files.exists(d.resolve("sub/n.kstep.kts")) shouldBe true
        }

        "bounds count over the whole tree" {
            val d = tmp()
            put(d, "a/1.md")
            put(d, "b/2.md")
            put(d, "c/3.md")

            fun lim(l: ConvertDirLimits) = run(d, Files.createDirectory(tmp().resolve("o")), limits = l, json = true)
            lim(ConvertDirLimits(maxFiles = 2)).out shouldContain "KSTEP-C-011"
            lim(ConvertDirLimits(maxEntries = 4)).out shouldContain "KSTEP-C-011"
            lim(ConvertDirLimits(maxBytes = 10)).out shouldContain "KSTEP-C-011"
            lim(ConvertDirLimits(maxTargets = 2)).out shouldContain "KSTEP-C-011"
        }

        "recursive needs a folder, and only once" {
            val d = tmp()
            val f = put(d, "n.md")
            val r = run(f)
            r.code shouldBe 1
            r.err shouldContain "--recursive expects a folder"
            resolveConvertForTest(listOf("x", "--to", "kts", "--recursive", "--recursive")) shouldBe false
            resolveConvertForTest(listOf("x", "--to", "kts", "--recursive")) shouldBe true
        }

        "names that differ only in case collide in a sub-folder (C-009)" {
            val d = tmp()
            put(d, "sub/a b.md")
            put(d, "sub/A_b.md")
            val o = Files.createDirectory(tmp().resolve("o"))
            val r = run(d, o, json = true)
            r.code shouldBe 1
            r.out shouldContain "KSTEP-C-009"
            files(o) shouldBe emptyList()
        }

        "a file or link in the way of a target folder is C-009" {
            val d = tmp()
            put(d, "sub/n.md")
            val o = Files.createDirectory(tmp().resolve("o"))
            Files.writeString(o.resolve("sub"), "in the way")
            val r = run(d, o, json = true)
            r.code shouldBe 1
            r.out shouldContain "KSTEP-C-009"
            Files.readString(o.resolve("sub")) shouldBe "in the way"
            val o2 = Files.createDirectory(tmp().resolve("o"))
            if (symlinkOrSkip(o2.resolve("sub"), tmp())) {
                run(d, o2, json = true).out shouldContain "KSTEP-C-009"
            }
        }

        "a name error anywhere aborts the whole run" {
            val d = tmp()
            put(d, "ok.md")
            put(d, "s/bad.md", "```kstep {name=../x}\na\n```\n")
            put(d, "s/dup.md", "```kstep {name=a}\na\n```\n```kstep {name=A}\nb\n```\n")
            val o = Files.createDirectory(tmp().resolve("o"))
            val r = run(d, o, json = true)
            r.code shouldBe 1
            r.out shouldContain "KSTEP-C-014"
            r.out shouldContain "KSTEP-C-015"
            files(o) shouldBe emptyList()
        }

        "warnings for unknown keys carry a code and do not fail" {
            val d = tmp()
            put(d, "n.md", "```kstep {name=x, type=y}\na\n```\n")
            val o = Files.createDirectory(tmp().resolve("o"))
            val r = run(d, o, json = true)
            r.code shouldBe 0
            val w =
                Json
                    .parseToJsonElement(r.out)
                    .jsonObject["warnings"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            w["code"]!!.jsonPrimitive.content shouldBe "KSTEP-C-016"
            w["line"]!!.jsonPrimitive.content shouldBe "1"
            Json
                .parseToJsonElement(r.out)
                .jsonObject["written"]!!
                .jsonArray
                .single()
                .jsonObject["blockName"]!!
                .jsonPrimitive.content shouldBe "x"
            val text = run(d, Files.createDirectory(tmp().resolve("o")), json = false)
            text.err shouldContain "unknown block attribute \"type\" ignored (line 1)"
        }

        "no warnings field without a warning" {
            val d = tmp()
            put(d, "n.md")
            run(d, Files.createDirectory(tmp().resolve("o")), json = true).out shouldNotContain "warnings"
        }

        "force replaces mirrored targets, without it nothing is written" {
            val d = tmp()
            put(d, "s/n.md")
            val o = Files.createDirectory(tmp().resolve("o"))
            run(d, o).code shouldBe 0
            val again = run(d, o, json = true)
            again.code shouldBe 1
            again.out shouldContain "KSTEP-C-008"
            run(d, o, force = true).code shouldBe 0
        }

        "kts to okf works recursively too" {
            val d = tmp()
            put(d, "s/t/n.kstep.kts", "val a = 1\n")
            val o = Files.createDirectory(tmp().resolve("o"))
            val r = run(d, o, target = ConvertTarget.OKF)
            r.code shouldBe 0
            files(o) shouldBe listOf("s/t/n.md")
        }

        "a named block keeps its bytes and writes name-based files" {
            val d = tmp()
            put(d, "n.md", "```kstep {name=bracket}\nA\n```\n\n```kstep\nB\n```\n")
            val o = Files.createDirectory(tmp().resolve("o"))
            run(d, o).code shouldBe 0
            files(o) shouldBe listOf("n-2.kstep.kts", "n-bracket.kstep.kts")
            Files.readString(o.resolve("n-bracket.kstep.kts")) shouldBe "A\n"
        }
    })

private fun resolveConvertForTest(rest: List<String>): Boolean =
    (resolveCommand((listOf("convert") + rest).toTypedArray()) as? CliCommand.Convert)?.recursive == true
