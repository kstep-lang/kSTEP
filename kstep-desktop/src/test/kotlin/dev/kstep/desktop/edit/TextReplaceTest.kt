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
            TextReplace.replaceAt("hello world", 6, 11, "kSTEP") shouldBe ("hello kSTEP" to 11)
        }

        "the replacement is sanitised" {
            TextReplace.sanitizeReplacement("a\r\nb") shouldBe "ab"
            TextReplace.sanitizeReplacement("x".repeat(5000)).length shouldBe 4096
            TextReplace.sanitizeReplacement("x".repeat(4095) + "😀") shouldBe "x".repeat(4095)
        }

        fun rx(
            p: String,
            matchCase: Boolean = true,
        ) = (SearchPattern.compile(p, true, matchCase, false) as SearchPattern.Compile.Ok).pattern

        fun rall(
            text: String,
            p: String,
            r: String,
        ) = TextReplace.replaceAllChecked(text, rx(p), r)

        "group references: $0 $1 $$ and the dollar signs that stay" {
            (rall("ab cd", "(\\w)(\\w)", "$2$1") as TextReplace.Outcome.Ok).text shouldBe "ba dc"
            (rall("ab", "a(b)", "[$0|$1]") as TextReplace.Outcome.Ok).text shouldBe "[ab|b]"
            (rall("ab", "ab", "$$1") as TextReplace.Outcome.Ok).text shouldBe "$1"
            (rall("ab", "ab", "\$x") as TextReplace.Outcome.Ok).text shouldBe "\$x"
            (rall("ab", "ab", "cost$") as TextReplace.Outcome.Ok).text shouldBe "cost$"
            (rall("ab", "(a)b", "$12") as TextReplace.Outcome.Ok).text shouldBe "a2"
        }

        "a group that does not exist is refused, one that did not take part is empty" {
            rall("ab", "ab", "$1") shouldBe TextReplace.Outcome.BadTemplate(1)
            rall("ab", "(a)(b)", "$9") shouldBe TextReplace.Outcome.BadTemplate(9)
            (rall("b", "(a)?b", "<$1>") as TextReplace.Outcome.Ok).text shouldBe "<>"
        }

        "a literal search never interprets the dollar sign" {
            all("a", "a", "$1$$").shouldBeInstanceOf<TextReplace.Outcome.Ok>().text shouldBe "$1$$"
        }

        "empty matches are replaced where they are" {
            (rall("ab", "x*", "-") as TextReplace.Outcome.Ok).text shouldBe "-a-b-"
            (rall("bab", "a*", "-") as TextReplace.Outcome.Ok).text shouldBe "-b-b-"
        }

        "a variable-length replacement is checked against the limits" {
            val text = "ab".repeat(5000)
            rall(text, "(ab)", "$1".repeat(1000)) shouldBe TextReplace.Outcome.TooLarge
            rall(text, "(ab)", "$1$1").shouldBeInstanceOf<TextReplace.Outcome.Ok>()
        }

        "an incomplete search refuses Replace all, a huge text too" {
            val text = "a".repeat(SafeRegex.MAX_TEXT_CHARS + 1)
            rall(text, "a", "b") shouldBe TextReplace.Outcome.TextTooLarge
            rall("a".repeat(10_001), "a", "b") shouldBe TextReplace.Outcome.TooManyMatches
        }

        "replace one: the match at or after the caret, with its groups" {
            val ok =
                TextReplace
                    .replaceOne(
                        "x1 y2 z3",
                        rx("(\\w)(\\d)"),
                        "$2$1",
                        3,
                    ).shouldBeInstanceOf<TextReplace.Outcome.Ok>()
            ok.text shouldBe "x1 2y z3"
            ok.caret shouldBe 5
            TextReplace
                .replaceOne("x1 y2", rx("(\\w)(\\d)"), "$2$1", 4)
                .shouldBeInstanceOf<TextReplace.Outcome.Ok>()
                .text shouldBe "1x y2"
            TextReplace.replaceOne("x", rx("y"), "z", 0) shouldBe TextReplace.Outcome.NoMatches
        }

        "a huge expansion of one whole-text match is refused before it is built" {
            val text = ("x".repeat(9_999) + "\n").repeat(100)
            val template = "$0".repeat(2048)
            rall(text, "[\\s\\S]+", template) shouldBe TextReplace.Outcome.TooLarge
            TextReplace.replaceOne(text, rx("[\\s\\S]+"), template, 0) shouldBe TextReplace.Outcome.TooLarge
        }
    })
