package dev.kstep.cli.okf

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import kotlin.time.Duration.Companion.seconds

private val RLO = 0x202E.toChar()
private val BEL = 0x07.toChar()
private val BOM = 0xFEFF.toChar()
private const val EURO = "\u20AC"

class OkfConverterTest :
    StringSpec({
        fun wrap(
            s: String,
            title: String = "Demo",
        ): String = (OkfConverter.wrapAsOkf(s, title).shouldBeInstanceOf<OkfConverter.WrapResult.Ok>()).note

        fun unwrap(note: String): List<OkfConverter.ExtractedScript> =
            (OkfConverter.extractScripts(note, "n").shouldBeInstanceOf<OkfConverter.ExtractResult.Ok>()).scripts

        "wrapped note reads back through the workspace parsers" {
            val note = wrap("val x = 1\n", "Hello world")
            val fm = FrontmatterParser.parse(note)
            fm.title shouldBe "Hello world"
            fm.tags shouldBe listOf("kstep")
            fm.ignored shouldBe false
            val ex = MarkdownBlockExtractor.extract(note, fm.bodyStartLine)
            ex.blocks.size shouldBe 1
            ex.blocks[0].state shouldBe BlockState.OK
        }

        "titles with quotes, backslashes, hash, colon and bidi controls survive" {
            val raw = "a \"q\" \\ b # c: d $RLO e"
            val title = OkfConverter.titleFromStem(raw)
            val fm = FrontmatterParser.parse(wrap("x\n", title))
            fm.ignored shouldBe false
            fm.title shouldBe title
            title.contains(RLO) shouldBe false
        }

        "fence selection around the 64 run cap" {
            OkfConverter.fenceFor("a\n```\nb") shouldBe "````"
            OkfConverter.fenceFor("`".repeat(63) + "\n") shouldBe "`".repeat(64)
            OkfConverter.fenceFor("`".repeat(64) + "\n") shouldBe "~~~"
            OkfConverter.fenceFor("`".repeat(64) + "\n" + "~".repeat(64)) shouldBe null
            OkfConverter.fenceFor("    ```\n") shouldBe "```"
            OkfConverter
                .wrapAsOkf(
                    "`".repeat(64) + "\n" + "~".repeat(64) + "\n",
                    "t",
                ).shouldBeInstanceOf<OkfConverter.WrapResult.Rejected>()
        }

        "empty and oversized scripts are rejected" {
            OkfConverter.wrapAsOkf("  \n\t", "t").shouldBeInstanceOf<OkfConverter.WrapResult.Rejected>()
            OkfConverter
                .wrapAsOkf(
                    "x".repeat(OkfLimits.MAX_BLOCK_BYTES + 1),
                    "t",
                ).shouldBeInstanceOf<OkfConverter.WrapResult.Rejected>()
            // multi-byte characters: 3 bytes each, over the byte limit while under it in chars
            OkfConverter
                .wrapAsOkf(
                    EURO.repeat(OkfLimits.MAX_BLOCK_BYTES / 3 + 1),
                    "t",
                ).shouldBeInstanceOf<OkfConverter.WrapResult.Rejected>()
        }

        "extraction names: single, several, empty middle block" {
            val one = "```kstep\na\n```\n"
            unwrap(one).map { it.fileName } shouldBe listOf("n.kstep.kts")
            val three = "```kstep\na\n```\n\n```kstep\nb\n```\n\n```kstep\nc\n```\n"
            unwrap(three).map { it.fileName } shouldBe listOf("n-1.kstep.kts", "n-2.kstep.kts", "n-3.kstep.kts")
            val mid = "```kstep\na\n```\n```kstep\n\n```\n```kstep\nc\n```\n"
            val r = OkfConverter.extractScripts(mid, "n").shouldBeInstanceOf<OkfConverter.ExtractResult.Ok>()
            r.scripts.map { it.fileName } shouldBe listOf("n-1.kstep.kts", "n-3.kstep.kts")
            r.skippedEmptyLines shouldBe listOf(4)
        }

        "extraction rejections" {
            OkfConverter
                .extractScripts(
                    "```kstep\nabc\n",
                    "n",
                ).shouldBeInstanceOf<OkfConverter.ExtractResult.Rejected>()
                .line shouldBe
                1
            OkfConverter
                .extractScripts("```kstep\n" + "x".repeat(OkfLimits.MAX_BLOCK_BYTES + 5) + "\n```\n", "n")
                .shouldBeInstanceOf<OkfConverter.ExtractResult.Rejected>()
            val many = "```kstep\na\n```\n".repeat(OkfLimits.MAX_BLOCKS + 1)
            OkfConverter.extractScripts(many, "n").shouldBeInstanceOf<OkfConverter.ExtractResult.Rejected>()
            OkfConverter
                .extractScripts(
                    "just prose\n```kotlin\nx\n```\n",
                    "n",
                ).shouldBeInstanceOf<OkfConverter.ExtractResult.Rejected>()
            OkfConverter
                .extractScripts(
                    "```kstep\n\n```\n",
                    "n",
                ).shouldBeInstanceOf<OkfConverter.ExtractResult.Rejected>()
        }

        "fences in front matter do not count; a kstepx fence is no block; an outer tilde fence swallows" {
            val fm = "---\ntitle: x\nnote: \"```kstep\"\n---\n```kstep\nA\n```\n"
            unwrap(fm).size shouldBe 1
            OkfConverter
                .extractScripts(
                    "```kstepx\nA\n```\n",
                    "n",
                ).shouldBeInstanceOf<OkfConverter.ExtractResult.Rejected>()
            OkfConverter
                .extractScripts(
                    "~~~~\n```kstep\nA\n```\n~~~~\n",
                    "n",
                ).shouldBeInstanceOf<OkfConverter.ExtractResult.Rejected>()
        }

        "sanitizeStem" {
            OkfConverter.sanitizeStem("../../etc/passwd") shouldBe "passwd"
            OkfConverter.sanitizeStem("a/b\\c") shouldBe "c"
            OkfConverter.sanitizeStem("a b$RLO$BEL\uD83D\uDE00z") shouldBe "a_b___z"
            OkfConverter.sanitizeStem("...") shouldBe "block"
            OkfConverter.sanitizeStem("") shouldBe "block"
            OkfConverter.sanitizeStem("---") shouldBe "block"
            OkfConverter.sanitizeStem("CON") shouldBe "_CON"
            OkfConverter.sanitizeStem("nul") shouldBe "_nul"
            OkfConverter.sanitizeStem("x".repeat(300)).length shouldBe 120
            OkfConverter.sanitizeStem(".hidden") shouldBe "hidden"
        }

        "round trip over the repository scripts and synthetic cases" {
            val dirs = listOf("../kstep-tests/src/test/resources", "../kstep-desktop/src/test/resources")
            val real =
                dirs
                    .flatMap { d -> File(d).listFiles { f -> f.name.endsWith(".kstep.kts") }?.toList() ?: emptyList() }
                    .map { it.readText() }
            (real.size >= 5) shouldBe true
            val synthetic =
                listOf(
                    "a\nb\n",
                    "a\r\nb\r\n",
                    "x\r\n",
                    "x\r",
                    "a\nb\r\n",
                    "no trailing break",
                    "a\n\n\n",
                    "x\n```\ny\n~~~\n---\n",
                    "${BOM}val a = 1\n",
                    "\u00e4$EURO\uD83D\uDE00\tTab\n",
                    "`".repeat(63) + "\n",
                    "`".repeat(64) + "\n",
                    "    ```\n  ~~~\n",
                )
            for (s in real + synthetic) {
                val back = unwrap(wrap(s)).single().source
                val expected =
                    if (s.endsWith("\n") || s.endsWith("\r")) s else s + OkfConverter.lineEnding(s)
                back shouldBe expected
            }
        }

        "hostile inputs finish and are rejected or handled".config(timeout = 30.seconds) {
            shouldNotThrowAny {
                OkfConverter.extractScripts("`".repeat(1 shl 20), "n")
                OkfConverter.extractScripts("```kstep\n".repeat(100_000), "n")
                OkfConverter.extractScripts("x".repeat(1 shl 20), "n")
                OkfConverter.extractScripts("---\n" + "k: v\n".repeat(20_000) + "---\n```kstep\na\n```\n", "n")
            }
        }
    })
