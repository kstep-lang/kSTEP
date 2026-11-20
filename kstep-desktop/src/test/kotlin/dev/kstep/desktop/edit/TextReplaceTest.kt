package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

class TextReplaceTest :
    StringSpec({
        fun all(
            text: String,
            q: String,
            r: String,
            matchCase: Boolean = true,
        ) = TextReplace.replaceAllChecked(text, q, matchCase, r)

        "replace all" {
            val ok = all("a b a", "a", "xy").shouldBeInstanceOf<TextReplace.Outcome.Ok>()
            ok.text shouldBe "xy b xy"
            ok.count shouldBe 2
            ok.caret shouldBe 2
        }

        "an empty replacement deletes, shorter and longer work, edges work" {
            (all("aXbX", "X", "") as TextReplace.Outcome.Ok).text shouldBe "ab"
            (all("Xa", "X", "long text") as TextReplace.Outcome.Ok).text shouldBe "long texta"
            (all("aX", "X", "!") as TextReplace.Outcome.Ok).text shouldBe "a!"
        }

        "case-insensitive replace all" {
            (all("Ab ab AB", "ab", "-", matchCase = false) as TextReplace.Outcome.Ok).text shouldBe "- - -"
        }

        "no matches and too many matches change nothing" {
            all("abc", "x", "y") shouldBe TextReplace.Outcome.NoMatches
            all("a".repeat(10_001), "a", "b") shouldBe TextReplace.Outcome.TooManyMatches
        }

        "10,000 matches are fine" {
            (all("a".repeat(10_000), "a", "bb") as TextReplace.Outcome.Ok).count shouldBe 10_000
        }

        "a result over 1 MiB is refused before it is built".config(timeout = 30.seconds) {
            all("a".repeat(10_000), "a", "r".repeat(4096)) shouldBe TextReplace.Outcome.TooLarge
        }

        "the limit is in bytes, not characters" {
            val text = ("x".repeat(100) + "\n").repeat(5000) // 5000 matches
            val q = "x".repeat(100)
            // 555,000 characters but 1,105,000 UTF-8 bytes
            all(text, q, "ä".repeat(110)) shouldBe TextReplace.Outcome.TooLarge
            all(text, q, "ä".repeat(90)).shouldBeInstanceOf<TextReplace.Outcome.Ok>()
        }

        "a result with a line over 20,000 characters is refused" {
            val text = "x".repeat(19_990) + "a"
            all(text, "a", "b".repeat(20)) shouldBe TextReplace.Outcome.TooLarge
            all("x".repeat(19_990) + "a", "a", "b".repeat(9)).shouldBeInstanceOf<TextReplace.Outcome.Ok>()
        }

        "withinLimits counts UTF-8 bytes" {
            fun lines(
                unit: String,
                n: Int,
            ) = (unit.repeat(5000) + "\n").repeat(n).dropLast(1)
            TextReplace.withinLimits(lines("ä", 104)) shouldBe true
            TextReplace.withinLimits(lines("ä", 106)) shouldBe false
            TextReplace.withinLimits(lines("😀", 52)) shouldBe true
            TextReplace.withinLimits(lines("😀", 53)) shouldBe false
        }

        "replace at one position" {
            TextReplace.replaceAt("hello world", 6, 5, "kSTEP") shouldBe ("hello kSTEP" to 11)
        }

        "the replacement is sanitised" {
            TextReplace.sanitizeReplacement("a\r\nb") shouldBe "ab"
            TextReplace.sanitizeReplacement("x".repeat(5000)).length shouldBe 4096
            TextReplace.sanitizeReplacement("x".repeat(4095) + "😀") shouldBe "x".repeat(4095)
        }
    })
