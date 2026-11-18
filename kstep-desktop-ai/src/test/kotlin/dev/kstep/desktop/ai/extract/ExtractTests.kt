package dev.kstep.desktop.ai.extract

import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.repair.AiLimits
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class ResponseExtractorTest :
    FunSpec({
        val limits = AiLimits()

        fun x(
            s: String,
            secrets: List<Secret> = emptyList(),
        ) = ResponseExtractor.extract(s, limits, secrets)

        test("kotlin, kts and untagged single blocks") {
            (x("a\n```kotlin\nval a = 1\n```\nb") as Extraction.Script).text shouldBe "val a = 1"
            (x("```kts\nval a = 2\n```") as Extraction.Script).text shouldBe "val a = 2"
            (x("```\nval a = 3\n```") as Extraction.Script).text shouldBe "val a = 3"
        }
        test("the first kotlin block wins; several untagged blocks are refused") {
            (
                x(
                    "```text\nno\n```\n```kotlin\nval a = 1\n```\n```kotlin\nval b = 2\n```",
                ) as Extraction.Script
            ).text shouldBe
                "val a = 1"
            x("```\na\n```\n```\nb\n```").shouldBeInstanceOf<Extraction.Rejected>()
        }
        test("no block and an empty block are refused") {
            x("just text").shouldBeInstanceOf<Extraction.Rejected>()
            x("```kotlin\n   \n```").shouldBeInstanceOf<Extraction.Rejected>()
        }
        test("CRLF and BOM are normalised") {
            (x("\uFEFF```kotlin\r\nval a = 1\r\nval b = 2\r\n```") as Extraction.Script).text shouldBe
                "val a = 1\nval b = 2"
        }
        test("size limits") {
            val ok = "a".repeat(limits.maxScriptBytes)
            x("```kotlin\n$ok\n```").shouldBeInstanceOf<Extraction.Script>()
            x("```kotlin\n${ok}a\n```").shouldBeInstanceOf<Extraction.Rejected>()
        }
        test("control, C1 and bidi characters are refused") {
            for (c in listOf("\u0000", "\u001B", "\u0085", "\u202E", "\u2066", "\u007F")) {
                x("```kotlin\nval a = \"$c\"\n```").shouldBeInstanceOf<Extraction.Rejected>()
            }
        }
        test("the configured key is refused and the reason carries no content") {
            val k = Secret.fromEnv("sk-ant-TEST-1234567890abcdef")
            val r = x("```kotlin\nval k = \"sk-ant-TEST-1234567890abcdef\"\n```", listOf(k))
            (r as Extraction.Rejected).reason shouldNotContain "sk-ant"
        }
        test("a key-like text that is not the configured key is accepted") {
            x(
                "```kotlin\nval k = \"sk-ant-OTHER-1234567890\"\n```",
                listOf(Secret.fromEnv("zzz")),
            ).shouldBeInstanceOf<Extraction.Script>()
        }
    })

class ErrorSummaryTest :
    FunSpec({
        val limits = AiLimits()

        test("paths of every OS, file urls and the candidate name are removed") {
            val d =
                Diagnostic(
                    "KSTEP-S-001",
                    3,
                    4,
                    "at /Users/irakli/x/candidate-2.kstep.kts and C:\\Users\\irakli\\a.kts and ~/foo/bar and file:/tmp/z",
                )
            val s = ErrorSummary.forModel(listOf(d), limits)
            s shouldNotContain "irakli"
            s shouldNotContain "/Users"
            s shouldNotContain "C:\\"
            s shouldNotContain "file:"
            s shouldNotContain "candidate-2"
        }
        test("key patterns and the known secret are redacted") {
            val k = Secret.fromEnv("my-private-value")
            val d =
                Diagnostic("C", null, null, "sk-ant-abcdefghijk api_key=hunter2 my-private-value ${"ab".repeat(20)}")
            val s = ErrorSummary.forModel(listOf(d), limits, listOf(k))
            s shouldNotContain "sk-ant-abc"
            s shouldNotContain "hunter2"
            s shouldNotContain "my-private-value"
            s shouldNotContain "abababab"
        }
        test("control characters out, at most 20 entries, 4 KiB cap without half characters") {
            val many = (1..50).map { Diagnostic("C$it", it, 1, "m\u0007x") }
            val s = ErrorSummary.forModel(many, limits)
            s.lines().size shouldBe 20
            s shouldNotContain "\u0007"
            val big = ErrorSummary.forModel(listOf(Diagnostic("C", 1, 1, "\u00e4".repeat(10_000))), limits)
            (big.toByteArray(Charsets.UTF_8).size <= limits.maxErrorSummaryBytes) shouldBe true
            String(big.toByteArray(Charsets.UTF_8), Charsets.UTF_8) shouldBe big
        }
    })
