package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** Byte-identity guard of the non-recursive convert output (ADR-0071): fixed strings recorded before `--recursive`. */
class ConvertGoldenTest :
    StringSpec({
        fun run(
            input: Path,
            target: ConvertTarget,
            json: Boolean,
        ): Pair<String, String> {
            val o = ByteArrayOutputStream()
            val e = ByteArrayOutputStream()
            runConvert(
                CliCommand.Convert(input.toString(), target, null, false, json),
                PrintStream(o, true, "UTF-8"),
                PrintStream(e, true, "UTF-8"),
            )
            return o.toString("UTF-8") to e.toString("UTF-8")
        }

        fun tree(): Path {
            val d = Files.createTempDirectory("kstep-golden").toRealPath()
            Files.writeString(d.resolve("a.md"), "```kstep\nx\n```\n\n```kstep\ny\n```\n")
            Files.writeString(d.resolve("b.md"), "```kstep\nz\n```\n")
            Files.writeString(d.resolve("c.md"), "no block\n")
            Files.writeString(d.resolve(".h.md"), "```kstep\nz\n```\n")
            Files.createDirectory(d.resolve("sub"))
            Files.writeString(d.resolve("sub").resolve("deep.md"), "```kstep\nq\n```\n")
            return d
        }

        "folder mode text output is unchanged" {
            val d = tree()
            val (out, err) = run(d, ConvertTarget.KTS, false)
            out shouldBe "Wrote a-1.kstep.kts\nWrote a-2.kstep.kts\nWrote b.kstep.kts\n"
            err shouldBe "kstep convert: skipped .h.md (hidden file)\nkstep convert: c.md: no kstep block, skipped\n"
        }

        "folder mode json output is unchanged" {
            val d = tree()
            val (out, _) = run(d, ConvertTarget.KTS, true)
            out shouldBe
                "{\"status\":\"success\",\"command\":\"convert\",\"target\":\"kts\",\"written\":[" +
                "{\"name\":\"a-1.kstep.kts\",\"source\":\"a.md\",\"block\":1}," +
                "{\"name\":\"a-2.kstep.kts\",\"source\":\"a.md\",\"block\":2}," +
                "{\"name\":\"b.kstep.kts\",\"source\":\"b.md\"}]," +
                "\"skipped\":[{\"source\":\".h.md\",\"reason\":\"hidden\"},{\"source\":\"c.md\",\"reason\":\"no_kstep_block\"}]}\n"
        }

        "single file json output is unchanged" {
            val d = tree()
            val (out, _) = run(d.resolve("a.md"), ConvertTarget.KTS, true)
            out shouldBe
                "{\"status\":\"success\",\"command\":\"convert\",\"target\":\"kts\",\"written\":[" +
                "{\"name\":\"a-1.kstep.kts\",\"source\":\"a.md\",\"block\":1}," +
                "{\"name\":\"a-2.kstep.kts\",\"source\":\"a.md\",\"block\":2}],\"skipped\":[]}\n"
        }
    })
