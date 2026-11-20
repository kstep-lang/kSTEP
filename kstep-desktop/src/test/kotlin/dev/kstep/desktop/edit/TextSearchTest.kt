package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class TextSearchTest :
    StringSpec({
        fun find(
            text: String,
            q: String,
            matchCase: Boolean = true,
        ) = TextSearch.find(text, q, matchCase)

        "empty text, empty query and no match" {
            find("", "a").count shouldBe 0
            find("abc", "").count shouldBe 0
            find("abc", "x").count shouldBe 0
            find("ab", "abc").count shouldBe 0
        }

        "matches at the start and the end, one line" {
            find("ab cd ab", "ab").starts.toList() shouldBe listOf(0, 6)
            find("x", "x").starts.toList() shouldBe listOf(0)
        }

        "matches do not overlap" {
            find("aaa", "aa").starts.toList() shouldBe listOf(0)
            find("aaaa", "aa").starts.toList() shouldBe listOf(0, 2)
        }

        "case sensitivity" {
            find("Ab aB ab", "ab", matchCase = true).starts.toList() shouldBe listOf(6)
            find("Ab aB ab", "ab", matchCase = false).starts.toList() shouldBe listOf(0, 3, 6)
        }

        "special case folding keeps the match length and valid offsets" {
            for (q in listOf("ß", "İ", "ǅ", "ǆ", "ς", "σ")) {
                val r = find("x${q}y$q", q, matchCase = false)
                r.length shouldBe q.length
                for (s in r.starts) (s + r.length <= 4 + q.length) shouldBe true
            }
        }

        "a surrogate pair is found whole, half a pair never matches" {
            val text = "a😀b😀"
            find(text, "😀").starts.toList() shouldBe listOf(1, 4)
            find(text, "\uD83D").count shouldBe 0
            find(text, "\uDE00").count shouldBe 0
            find(text, "\uDE00b").count shouldBe 0
        }

        "control and bidi characters are plain text" {
            find("a‮b\u0001c", "‮").starts.toList() shouldBe listOf(1)
            find("a‮b\u0001c", "\u0001").starts.toList() shouldBe listOf(3)
        }

        "a query cannot cross a line break because the sanitiser removes it" {
            TextSearch.sanitizeQuery("a\nb\r") shouldBe "ab"
        }

        "a very long line" {
            val line = "x".repeat(20_000) + "needle"
            find(line, "needle").starts.toList() shouldBe listOf(20_000)
        }

        "the match limit: 10,000 fit exactly, 10,001 truncate" {
            val ok = TextSearch.find("a".repeat(10_000), "a", true)
            ok.count shouldBe 10_000
            ok.truncated shouldBe false
            val cut = TextSearch.find("a".repeat(10_001), "a", true)
            cut.count shouldBe 10_000
            cut.truncated shouldBe true
        }

        "the query limit cuts at a code point" {
            TextSearch.sanitizeQuery("a".repeat(256)).length shouldBe 256
            TextSearch.sanitizeQuery("a".repeat(257)).length shouldBe 256
            val s = TextSearch.sanitizeQuery("a".repeat(255) + "😀")
            s shouldBe "a".repeat(255)
        }

        "navigation wraps around" {
            val r = find("a.a.a", "a")
            r.firstAtOrAfter(0) shouldBe 0
            r.firstAtOrAfter(1) shouldBe 1
            r.firstAtOrAfter(3) shouldBe 2
            r.firstAtOrAfter(5) shouldBe 0
            r.next(2) shouldBe 0
            r.previous(0) shouldBe 2
            find("x", "a").firstAtOrAfter(0) shouldBe -1
            find("x", "a").next(0) shouldBe -1
        }

        "inRange gives the visible matches, capped" {
            val r = find("a.a.a.a.a", "a")
            r.inRange(2, 7).toList() shouldBe listOf(1, 2, 3)
            r.inRange(0, 100, cap = 2).toList() shouldBe listOf(0, 1)
            r.inRange(50, 60).isEmpty() shouldBe true
            find("abcd abcd", "abcd").inRange(2, 3).toList() shouldBe listOf(0)
        }

        "a cancelled search returns nothing" {
            TextSearch.find("a".repeat(100_000), "a", true, shouldContinue = { false }).count shouldBe 0
        }

        "worst cases stay linear".config(timeout = 30.seconds) {
            val mib = "a".repeat(1_048_576)
            for (mc in listOf(true, false)) {
                TextSearch.find(mib, "a".repeat(255) + "b", mc).count shouldBe 0
                TextSearch.find(mib, "a".repeat(100), mc).truncated shouldBe true
            }
            TextSearch.find("ab".repeat(524_288), "ab", false).truncated shouldBe true
        }
    })
