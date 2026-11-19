package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val RLO = 0x202E.toChar()
private val BEL = 0x07.toChar()

class ConvertCommandTest :
    StringSpec({
        class Run(
            val code: Int,
            val out: String,
            val err: String,
        )

        fun run(
            input: Path,
            target: ConvertTarget,
            outDir: Path? = null,
            force: Boolean = false,
            json: Boolean = false,
            title: String? = null,
            tags: List<String> = emptyList(),
            limits: ConvertDirLimits = ConvertDirLimits(),
            beforeMove: () -> Unit = {},
        ): Run {
            val o = ByteArrayOutputStream()
            val e = ByteArrayOutputStream()
            val code =
                runConvert(
                    CliCommand.Convert(input.toString(), target, outDir?.toString(), force, json, title, tags),
                    PrintStream(o, true, "UTF-8"),
                    PrintStream(e, true, "UTF-8"),
                    beforeMove,
                    limits,
                )
            return Run(code, o.toString("UTF-8"), e.toString("UTF-8"))
        }

        fun tmp(): Path = Files.createTempDirectory("kstep-convert-cmd").toRealPath()

        "kts to okf to kts round trip end to end" {
            val d = tmp()
            val src = "val answer = 42\nprintln(answer)\n"
            val f = Files.writeString(d.resolve("demo.kstep.kts"), src)
            val a = run(f, ConvertTarget.OKF)
            a.code shouldBe 0
            a.out shouldContain "Wrote demo.md"
            val back = Files.createDirectory(d.resolve("back"))
            val b = run(d.resolve("demo.md"), ConvertTarget.KTS, back)
            b.code shouldBe 0
            Files.readString(back.resolve("demo.kstep.kts")) shouldBe src
        }

        "collision without force writes nothing, with force replaces" {
            val d = tmp()
            val note = "```kstep\na\n```\n\n```kstep\nb\n```\n"
            val f = Files.writeString(d.resolve("n.md"), note)
            Files.writeString(d.resolve("n-2.kstep.kts"), "old")
            val r = run(f, ConvertTarget.KTS)
            r.code shouldBe 1
            r.err shouldContain "--force"
            Files.exists(d.resolve("n-1.kstep.kts")) shouldBe false
            Files.readString(d.resolve("n-2.kstep.kts")) shouldBe "old"
            run(f, ConvertTarget.KTS, force = true).code shouldBe 0
            Files.readString(d.resolve("n-2.kstep.kts")) shouldBe "b\n"
        }

        "wrong extension and missing out dir fail" {
            val d = tmp()
            val f = Files.writeString(d.resolve("x.txt"), "a")
            run(f, ConvertTarget.OKF).code shouldBe 1
            val k = Files.writeString(d.resolve("x.kstep.kts"), "a\n")
            run(k, ConvertTarget.OKF, d.resolve("missing")).code shouldBe 1
            Files.exists(d.resolve("missing")) shouldBe false
        }

        "three blocks give three files and three lines" {
            val d = tmp()
            val f = Files.writeString(d.resolve("t.md"), "```kstep\na\n```\n```kstep\nb\n```\n```kstep\nc\n```\n")
            val r = run(f, ConvertTarget.KTS)
            r.code shouldBe 0
            r.out
                .lines()
                .filter { it.isNotEmpty() }
                .size shouldBe 3
        }

        "hostile note names never reach the output raw" {
            val d = tmp()
            val f = Files.writeString(d.resolve("a${RLO}b$BEL.md"), "no block here")
            val r = run(f, ConvertTarget.KTS)
            r.code shouldBe 1
            r.err shouldNotContain RLO.toString()
            r.err shouldNotContain "\u0007"
            r.err shouldContain "U+202E"
            val g = Files.writeString(d.resolve("c${RLO}d.md"), "```kstep\nx\n```\n")
            val ok = run(g, ConvertTarget.KTS)
            ok.code shouldBe 0
            ok.out shouldNotContain RLO.toString()
            Files.exists(d.resolve("c_d.kstep.kts")) shouldBe true
        }

        "empty script and hostile sizes fail cleanly".config(timeout = 30.seconds) {
            val d = tmp()
            run(Files.writeString(d.resolve("e.kstep.kts"), "\n"), ConvertTarget.OKF).code shouldBe 1
            val big = Files.writeString(d.resolve("big.md"), "x".repeat(1 shl 20))
            run(big, ConvertTarget.KTS).code shouldBe 1
            val over = Files.write(d.resolve("over.md"), ByteArray((1 shl 20) + 1) { 'x'.code.toByte() })
            run(over, ConvertTarget.KTS).code shouldBe 1
        }

        fun obj(r: Run): JsonObject {
            r.out
                .trimEnd('\n')
                .lines()
                .size shouldBe 1
            r.err shouldBe ""
            return Json.parseToJsonElement(r.out).jsonObject
        }

        fun str(
            o: JsonObject,
            k: String,
        ) = o.getValue(k).jsonPrimitive.content

        fun dirNames(d: Path): List<String> =
            Files.list(d).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

        fun link(
            link: Path,
            to: Path,
        ): Boolean =
            try {
                Files.createSymbolicLink(link, to)
                true
            } catch (_: Exception) {
                false
            }

        "json success for okf and kts is one parseable line with the documented fields" {
            val d = tmp()
            val f = Files.writeString(d.resolve("demo.kstep.kts"), "val a = 1\n")
            val a = obj(run(f, ConvertTarget.OKF, json = true))
            str(a, "status") shouldBe "success"
            str(a, "command") shouldBe "convert"
            str(a, "target") shouldBe "okf"
            a.keys.toList().take(3) shouldBe listOf("status", "command", "target")
            val w =
                a
                    .getValue("written")
                    .jsonArray
                    .single()
                    .jsonObject
            str(w, "name") shouldBe "demo.md"
            str(w, "source") shouldBe "demo.kstep.kts"
            a.getValue("skipped") shouldBe JsonArray(emptyList())
            val back = Files.createDirectory(d.resolve("back"))
            Files.writeString(d.resolve("n.md"), "```kstep\n```\n```kstep\nb\n```\n")
            val k = obj(run(d.resolve("n.md"), ConvertTarget.KTS, back, json = true))
            str(k, "target") shouldBe "kts"
            val kw =
                k
                    .getValue("written")
                    .jsonArray
                    .single()
                    .jsonObject
            str(kw, "name") shouldBe "n-2.kstep.kts"
            kw.getValue("block").jsonPrimitive.content shouldBe "2"
            val sk =
                k
                    .getValue("skipped")
                    .jsonArray
                    .single()
                    .jsonObject
            str(sk, "reason") shouldBe "empty_block"
            sk.getValue("line").jsonPrimitive.content shouldBe "1"
        }

        "json errors carry kind and code for every error family" {
            val d = tmp()

            fun code(r: Run): String = str(obj(r), "code")
            code(run(Files.writeString(d.resolve("x.txt"), "a"), ConvertTarget.OKF, json = true)) shouldBe "KSTEP-C-002"
            code(run(d.resolve("missing.kstep.kts"), ConvertTarget.OKF, json = true)) shouldBe "KSTEP-C-001"
            val big = Files.write(d.resolve("big.kstep.kts"), ByteArray((256 * 1024) + 1) { 'x'.code.toByte() })
            code(run(big, ConvertTarget.OKF, json = true)) shouldBe "KSTEP-C-004"
            val bin = Files.write(d.resolve("bin.kstep.kts"), byteArrayOf(0xC3.toByte(), 0x28))
            code(run(bin, ConvertTarget.OKF, json = true)) shouldBe "KSTEP-C-003"
            val open = Files.writeString(d.resolve("open.md"), "```kstep\nx\n")
            code(run(open, ConvertTarget.KTS, json = true)) shouldBe "KSTEP-C-005"
            code(run(Files.writeString(d.resolve("e.kstep.kts"), "\n"), ConvertTarget.OKF, json = true)) shouldBe
                "KSTEP-C-005"
            val ok = Files.writeString(d.resolve("ok.kstep.kts"), "a\n")
            code(run(ok, ConvertTarget.OKF, title = "\u202E", json = true)) shouldBe "KSTEP-C-006"
            code(run(ok, ConvertTarget.OKF, d.resolve("nope"), json = true)) shouldBe "KSTEP-C-007"
            Files.writeString(d.resolve("ok.md"), "old")
            code(run(ok, ConvertTarget.OKF, json = true)) shouldBe "KSTEP-C-008"
            Files.delete(d.resolve("ok.md"))
            if (link(d.resolve("ok.md"), d.resolve("x.txt"))) {
                code(run(ok, ConvertTarget.OKF, force = true, json = true)) shouldBe "KSTEP-C-009"
            }
        }

        "json: a file appearing meanwhile reports the files written before" {
            val d = tmp()
            val f = Files.writeString(d.resolve("n.md"), "```kstep\na\n```\n```kstep\nb\n```\n")
            var calls = 0
            val r =
                run(f, ConvertTarget.KTS, json = true) {
                    if (++calls == 2) Files.writeString(d.resolve("n-2.kstep.kts"), "foreign")
                }
            val o = obj(r)
            str(o, "code") shouldBe "KSTEP-C-008"
            o.getValue("written").jsonArray.size shouldBe 1
            Files.readString(d.resolve("n-2.kstep.kts")) shouldBe "foreign"
        }

        "json never contains an absolute path or a raw hostile name" {
            val d = tmp()
            val f = Files.writeString(d.resolve("a${RLO}b.md"), "no block")
            val r = run(f, ConvertTarget.KTS, json = true)
            r.out shouldNotContain d.toString()
            r.out shouldNotContain RLO.toString()
            r.out shouldContain "U+202E"
            val g = Files.writeString(d.resolve("c${RLO}d.kstep.kts"), "x\n")
            val ok = run(g, ConvertTarget.OKF, json = true)
            ok.out shouldNotContain d.toString()
            ok.out shouldNotContain RLO.toString()
        }

        "title and tags end to end; the default note is unchanged" {
            val d = tmp()
            val f = Files.writeString(d.resolve("demo.kstep.kts"), "val a = 1\n")
            val plain = run(f, ConvertTarget.OKF)
            plain.code shouldBe 0
            Files.readString(d.resolve("demo.md")) shouldBe
                "---\ntitle: \"demo\"\ntags: [kstep]\n---\n\n# demo\n\n```kstep\nval a = 1\n```\n"
            run(
                f,
                ConvertTarget.OKF,
                force = true,
                title = "My Part",
                tags = listOf("cad", "x y".replace(" ", "_")),
            ).code shouldBe
                0
            Files.readString(d.resolve("demo.md")) shouldBe
                "---\ntitle: \"My Part\"\ntags: [kstep, cad, x_y]\n---\n\n# My Part\n\n```kstep\nval a = 1\n```\n"
            run(f, ConvertTarget.OKF, force = true, tags = listOf("bad tag")).code shouldBe 1
            // nothing was changed by the failed run
            Files.readString(d.resolve("demo.md")).contains("My Part") shouldBe true
        }

        "folder to okf: three scripts, empty one skipped, round trip back is byte-identical" {
            val d = tmp()
            val src = Files.createDirectory(d.resolve("src"))
            val bodies = mapOf("a" to "val a = 1\r\n", "b" to "\uFEFFval b = 2\n", "c" to "val c = 3")
            for ((n, b) in bodies) Files.writeString(src.resolve("$n.kstep.kts"), b)
            Files.writeString(src.resolve("empty.kstep.kts"), "  \n")
            val notes = Files.createDirectory(d.resolve("notes"))
            val r = run(src, ConvertTarget.OKF, notes, json = true)
            val o = obj(r)
            o.getValue("written").jsonArray.size shouldBe 3
            str(
                o
                    .getValue("skipped")
                    .jsonArray
                    .single()
                    .jsonObject,
                "reason",
            ) shouldBe "empty_script"
            dirNames(notes) shouldBe listOf("a.md", "b.md", "c.md")
            val back = Files.createDirectory(d.resolve("back"))
            run(notes, ConvertTarget.KTS, back).code shouldBe 0
            for ((n, b) in bodies) {
                val got = Files.readString(back.resolve("$n.kstep.kts"))
                got shouldBe if (n == "c") "val c = 3\n" else b
            }
        }

        "folder to kts: notes without a block are skipped, only skipped notes fail, a broken note blocks everything" {
            val d = tmp()
            val src = Files.createDirectory(d.resolve("src"))
            Files.writeString(src.resolve("a.md"), "```kstep\nx\n```\n")
            Files.writeString(src.resolve("b.md"), "just prose\n")
            val out = Files.createDirectory(d.resolve("out"))
            val r = run(src, ConvertTarget.KTS, out)
            r.code shouldBe 0
            r.err shouldContain "b.md: no kstep block, skipped"
            dirNames(out) shouldBe listOf("a.kstep.kts")

            val only = Files.createDirectory(d.resolve("only"))
            Files.writeString(only.resolve("p.md"), "prose")
            val out2 = Files.createDirectory(d.resolve("out2"))
            val none = run(only, ConvertTarget.KTS, out2, json = true)
            none.code shouldBe 1
            str(obj(none), "code") shouldBe "KSTEP-C-005"

            val text = run(only, ConvertTarget.KTS, out2)
            text.code shouldBe 1
            text.err shouldContain "p.md: no kstep block, skipped"
            text.err shouldContain "nothing to convert: every file was skipped"

            val mixed = Files.createDirectory(d.resolve("mixed"))
            Files.writeString(mixed.resolve("q.md"), "prose")
            Files.writeString(mixed.resolve("broken.md"), "```kstep\nnever closed\n")
            val out4 = Files.createDirectory(d.resolve("out4"))
            val mr = run(mixed, ConvertTarget.KTS, out4)
            mr.code shouldBe 1
            mr.err shouldContain "q.md: no kstep block, skipped"

            val bad = Files.createDirectory(d.resolve("bad"))
            Files.writeString(bad.resolve("ok.md"), "```kstep\nx\n```\n")
            Files.writeString(bad.resolve("broken.md"), "```kstep\nnever closed\n")
            val out3 = Files.createDirectory(d.resolve("out3"))
            val before = dirNames(bad)
            val br = run(bad, ConvertTarget.KTS, out3, json = true)
            br.code shouldBe 1
            dirNames(out3) shouldBe emptyList()
            dirNames(bad) shouldBe before
            obj(br).getValue("errors").jsonArray.size shouldBe 1
        }

        "folder: name collisions are rejected and nothing is written" {
            val d = tmp()
            val src = Files.createDirectory(d.resolve("src"))
            Files.writeString(src.resolve("a.md"), "```kstep\n1\n```\n```kstep\n2\n```\n")
            Files.writeString(src.resolve("a-1.md"), "```kstep\n3\n```\n")
            val out = Files.createDirectory(d.resolve("out"))
            val r = run(src, ConvertTarget.KTS, out, json = true)
            r.code shouldBe 1
            str(obj(r), "code") shouldBe "KSTEP-C-009"
            dirNames(out) shouldBe emptyList()
            val s2 = Files.createDirectory(d.resolve("s2"))
            Files.writeString(s2.resolve("\u00E4.kstep.kts"), "x\n")
            Files.writeString(s2.resolve("_.kstep.kts"), "y\n")
            val out2 = Files.createDirectory(d.resolve("out2"))
            val r2 = run(s2, ConvertTarget.OKF, out2, json = true)
            str(obj(r2), "code") shouldBe "KSTEP-C-009"
            dirNames(out2) shouldBe emptyList()
        }

        "folder: one conflict among ten writes nothing without force" {
            val d = tmp()
            val src = Files.createDirectory(d.resolve("src"))
            for (i in 0 until 10) Files.writeString(src.resolve("f$i.kstep.kts"), "x$i\n")
            val out = Files.createDirectory(d.resolve("out"))
            Files.writeString(out.resolve("f7.md"), "old")
            val r = run(src, ConvertTarget.OKF, out, json = true)
            r.code shouldBe 1
            str(obj(r), "code") shouldBe "KSTEP-C-008"
            dirNames(out) shouldBe listOf("f7.md")
            run(src, ConvertTarget.OKF, out, force = true).code shouldBe 0
            dirNames(out).size shouldBe 10
        }

        "folder: --title is refused, --tag applies to every note, limits are enforced" {
            val d = tmp()
            val src = Files.createDirectory(d.resolve("src"))
            for (i in 0 until 3) Files.writeString(src.resolve("f$i.kstep.kts"), "x$i\n")
            val out = Files.createDirectory(d.resolve("out"))
            val t = run(src, ConvertTarget.OKF, out, title = "T", json = true)
            str(obj(t), "code") shouldBe "KSTEP-C-006"
            run(src, ConvertTarget.OKF, out, tags = listOf("batch")).code shouldBe 0
            for (i in 0 until 3) Files.readString(out.resolve("f$i.md")) shouldContain "tags: [kstep, batch]"
            val out2 = Files.createDirectory(d.resolve("out2"))
            val l = run(src, ConvertTarget.OKF, out2, json = true, limits = ConvertDirLimits(maxTargets = 2))
            str(obj(l), "code") shouldBe "KSTEP-C-011"
            dirNames(out2) shouldBe emptyList()
            val l2 = run(src, ConvertTarget.OKF, out2, json = true, limits = ConvertDirLimits(maxFiles = 2))
            str(obj(l2), "code") shouldBe "KSTEP-C-011"
        }

        "folder: --out equal to the input folder never overwrites an input, links and hidden files are skipped" {
            val d = tmp()
            val a = Files.writeString(d.resolve("a.kstep.kts"), "orig\n")
            Files.writeString(d.resolve(".h.kstep.kts"), "h\n")
            val hard = d.resolve("a.md")
            val linked =
                try {
                    Files.createLink(hard, a)
                    true
                } catch (_: Exception) {
                    false
                }
            val r = run(d, ConvertTarget.OKF, d, force = true, json = true)
            if (linked) {
                r.code shouldBe 1
                str(obj(r), "code") shouldBe "KSTEP-C-009"
                Files.readString(a) shouldBe "orig\n"
            } else {
                r.code shouldBe 0
                Files.readString(a) shouldBe "orig\n"
            }
        }

        "folder without matching files fails; a link to a folder is accepted as input" {
            val d = tmp()
            val e = Files.createDirectory(d.resolve("e"))
            Files.writeString(e.resolve("x.txt"), "a")
            str(obj(run(e, ConvertTarget.KTS, json = true)), "code") shouldBe "KSTEP-C-002"
            val src = Files.createDirectory(d.resolve("src"))
            Files.writeString(src.resolve("a.kstep.kts"), "x\n")
            val l = d.resolve("lnk")
            if (link(l, src)) {
                val out = Files.createDirectory(d.resolve("out"))
                run(l, ConvertTarget.OKF, out).code shouldBe 0
                dirNames(out) shouldBe listOf("a.md")
            }
        }

        "text mode of a single file is unchanged: Wrote lines on stdout only" {
            val d = tmp()
            val f = Files.writeString(d.resolve("t.md"), "```kstep\n```\n```kstep\nb\n```\n")
            val r = run(f, ConvertTarget.KTS)
            r.code shouldBe 0
            r.out shouldBe "Wrote t-2.kstep.kts\n"
            r.err shouldBe "kstep convert: skipped the empty kstep block in line 1\n"
        }
    })
