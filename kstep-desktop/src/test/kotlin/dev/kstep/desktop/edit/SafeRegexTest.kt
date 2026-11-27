package dev.kstep.desktop.edit

import dev.kstep.desktop.edit.SafeRegex.RejectReason
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class SafeRegexTest :
    StringSpec({
        fun regex(
            p: String,
            matchCase: Boolean = true,
            wholeWord: Boolean = false,
        ): SearchPattern.Regex =
            (SearchPattern.compile(p, true, matchCase, wholeWord) as SearchPattern.Compile.Ok)
                .pattern as SearchPattern.Regex

        fun spans(
            text: String,
            p: String,
            matchCase: Boolean = true,
            wholeWord: Boolean = false,
        ): List<Pair<Int, Int>> {
            val r = TextSearch.find(text, regex(p, matchCase, wholeWord))
            return (0 until r.count).map { r.starts[it] to r.ends[it] }
        }

        fun rejected(p: String): SafeRegex.Compiled.Rejected =
            SafeRegex.compile(p, true, false).shouldBeInstanceOf<SafeRegex.Compiled.Rejected>()

        "literals, escapes, any char, classes" {
            spans("a.b axb", "a.b") shouldBe listOf(0 to 3, 4 to 7)
            spans("a.b axb", "a\\.b") shouldBe listOf(0 to 3)
            spans("a1 b22 c", "\\d+") shouldBe listOf(1 to 2, 4 to 6)
            spans("ab_1 -x", "\\w+") shouldBe listOf(0 to 4, 6 to 7)
            spans("a b\tc", "\\s") shouldBe listOf(1 to 2, 3 to 4)
            spans("a1 b", "\\D+") shouldBe listOf(0 to 1, 2 to 4)
            spans("a1 b", "\\W") shouldBe listOf(2 to 3)
            spans("a1 b", "\\S+") shouldBe listOf(0 to 2, 3 to 4)
            spans("abcdef", "[a-c]+") shouldBe listOf(0 to 3)
            spans("abcdef", "[^a-c]+") shouldBe listOf(3 to 6)
            spans("a-b", "[a\\-]+") shouldBe listOf(0 to 2)
            spans("a1_ ", "[\\d_]+") shouldBe listOf(1 to 3)
            spans("a1_ ", "[\\D]+") shouldBe listOf(0 to 1, 2 to 4)
            spans("x\ny", "x\\ny") shouldBe listOf(0 to 3)
            spans("a]b", "[]]") shouldBe listOf(1 to 2)
            spans("a-b", "[-a]+") shouldBe listOf(0 to 2)
        }

        "anchors and word boundaries" {
            spans("foo\nbar foo", "^foo") shouldBe listOf(0 to 3)
            spans("foo\nbar foo", "foo$") shouldBe listOf(0 to 3, 8 to 11)
            spans("a\nfoo\nb", "^foo$") shouldBe listOf(2 to 5)
            spans("a\r\nfoo\r\nb", "^foo$") shouldBe listOf(3 to 6)
            spans("cat concat cat", "\\bcat\\b") shouldBe listOf(0 to 3, 11 to 14)
            spans("a cat", "\\Bat") shouldBe listOf(3 to 5)
        }

        "quantifiers, groups and alternation" {
            spans("aaa b", "a*") shouldBe listOf(0 to 3, 4 to 4, 5 to 5)
            spans("ab abab", "(?:ab)+") shouldBe listOf(0 to 2, 3 to 7)
            spans("ab abab", "(ab){2}") shouldBe listOf(3 to 7)
            spans("aaaa", "a{2,3}") shouldBe listOf(0 to 3)
            spans("aaaa", "a{2,}") shouldBe listOf(0 to 4)
            spans("color colour", "colou?r") shouldBe listOf(0 to 5, 6 to 12)
            spans("cat dog bird", "cat|dog") shouldBe listOf(0 to 3, 4 to 7)
            spans("ab", "a|ab") shouldBe listOf(0 to 1)
            spans("ab", "(a|ab)") shouldBe listOf(0 to 1)
            spans("xaaay", "xa*y") shouldBe listOf(0 to 5)
        }

        "every rejection has its reason" {
            rejected("(a)\\1").reason shouldBe RejectReason.BACKREFERENCE
            rejected("\\k<n>").reason shouldBe RejectReason.BACKREFERENCE
            rejected("(?=a)").reason shouldBe RejectReason.LOOKAROUND
            rejected("(?!a)").reason shouldBe RejectReason.LOOKAROUND
            rejected("(?<=a)b").reason shouldBe RejectReason.LOOKAROUND
            rejected("(?<!a)b").reason shouldBe RejectReason.LOOKAROUND
            for (p in listOf("a*?", "a+?", "a??", "a{2}?", "a*+", "a++", "a?+", "a{2}+")) {
                withClue(p) { rejected(p).reason shouldBe RejectReason.LAZY_OR_POSSESSIVE }
            }
            rejected("(?i)a").reason shouldBe RejectReason.INLINE_FLAGS_OR_NAMED
            rejected("(?<n>a)").reason shouldBe RejectReason.INLINE_FLAGS_OR_NAMED
            rejected("(?P<n>a)").reason shouldBe RejectReason.INLINE_FLAGS_OR_NAMED
            rejected("(?i:a)").reason shouldBe RejectReason.INLINE_FLAGS_OR_NAMED
            for (p in listOf("(a", "a)", "[a", "(a))", "[]")) {
                withClue(p) { rejected(p).reason shouldBe RejectReason.UNBALANCED }
            }
            for (p in listOf("*a", "a|*", "(+a)", "a{3,2}", "a{x", "{", "a**", "^*", "a{2}{3}", "a{,3}")) {
                withClue(p) { rejected(p).reason shouldBe RejectReason.BAD_QUANTIFIER }
            }
            rejected("\\q").also {
                it.reason shouldBe RejectReason.BAD_ESCAPE
                it.escapeChar shouldBe 'q'.code
            }
            rejected("a\\").reason shouldBe RejectReason.BAD_ESCAPE
            rejected("\\0").reason shouldBe RejectReason.BAD_ESCAPE
            rejected("[z-a]").reason shouldBe RejectReason.BAD_CLASS_RANGE
            rejected("[a-\\d]").reason shouldBe RejectReason.BAD_CLASS_RANGE
            rejected("()()()()()()()()()()").reason shouldBe RejectReason.TOO_COMPLEX
            rejected("").reason shouldBe RejectReason.EMPTY
        }

        "the limits are counted: repeat, instructions, nesting, pattern length" {
            SafeRegex.compile("a{100}", true, false).shouldBeInstanceOf<SafeRegex.Compiled.Ok>()
            rejected("a{101}").reason shouldBe RejectReason.TOO_COMPLEX
            rejected("a{1,101}").reason shouldBe RejectReason.TOO_COMPLEX
            rejected("(?:a{100}){100}").reason shouldBe RejectReason.TOO_COMPLEX
            rejected("(?:(?:(?:a{100}){100}){100}){100}").reason shouldBe RejectReason.TOO_COMPLEX
            rejected("(?:".repeat(33) + "a" + ")".repeat(33)).reason shouldBe RejectReason.TOO_COMPLEX
            SafeRegex
                .compile("(?:".repeat(32) + "a" + ")".repeat(32), true, false)
                .shouldBeInstanceOf<SafeRegex.Compiled.Ok>()
            rejected("a".repeat(257)).reason shouldBe RejectReason.TOO_COMPLEX
            SafeRegex.compile("a".repeat(256), true, false).shouldBeInstanceOf<SafeRegex.Compiled.Ok>()
            TextSearch.sanitizeQuery("a".repeat(300)).length shouldBe 256
        }

        "empty matches terminate and follow the editor rule" {
            spans("", "a*") shouldBe listOf(0 to 0)
            spans("a", "a*") shouldBe listOf(0 to 1)
            spans("bab", "a*") shouldBe listOf(0 to 0, 1 to 2, 3 to 3)
            spans("ab", "x?") shouldBe listOf(0 to 0, 1 to 1, 2 to 2)
            spans("ab", "^") shouldBe listOf(0 to 0)
            spans("a\nb", "^") shouldBe listOf(0 to 0, 2 to 2)
            spans("ab", "$") shouldBe listOf(2 to 2)
            spans("a b", "\\b") shouldBe listOf(0 to 0, 1 to 1, 2 to 2, 3 to 3)
            spans("a😀", "x*") shouldBe listOf(0 to 0, 1 to 1, 3 to 3)
        }

        "ReDoS patterns finish and stay within the counted bound".config(timeout = 30.seconds) {
            val text = "a".repeat(100_000) + "!"
            for (p in listOf("(a+)+$", "(a|a)*", "(a*)*b", "^(a+)+$", "(a|aa)+$")) {
                val re = regex(p)
                val started = System.nanoTime()
                val r = TextSearch.find(text, re)
                withClue("$p took ${(System.nanoTime() - started) / 1_000_000} ms") {
                    r.limit shouldBe TextSearch.Limit.NONE
                    (r.steps <= (text.length + 1L) * re.compiled.program.size * 2) shouldBe true
                }
            }
            spans(text, "(a+)+$") shouldBe emptyList()
            spans(text, "(a|a)*") shouldBe listOf(0 to 100_000, 100_001 to 100_001)
            spans(text, "^(a+)+$") shouldBe emptyList()
            spans(text, "(a*)*b") shouldBe emptyList()
        }

        "a pattern that exceeds the step budget stops with the matches found so far".config(timeout = 30.seconds) {
            val re = regex("(.*a){20}")
            val r = TextSearch.find("a".repeat(100_000) + "!", re)
            // either it finishes within the budget or it reports the budget; never a hang, never a wrong NONE
            (r.limit == TextSearch.Limit.NONE || r.limit == TextSearch.Limit.STEP_BUDGET) shouldBe true
            val tiny =
                SafeRegex.findAll("a".repeat(1000), re.compiled.program, 10_000, stepBudget = 500)
            tiny!!.budgetExhausted shouldBe true
        }

        "text over 1 MiB is refused for a regex, not for a literal" {
            val big = "a".repeat(SafeRegex.MAX_TEXT_CHARS + 1)
            TextSearch.find(big, regex("a")).limit shouldBe TextSearch.Limit.TEXT_TOO_LARGE
            TextSearch.find(big, regex("a")).count shouldBe 0
            TextSearch.find(big, "a", true).truncated shouldBe true
        }

        "10,001 matches truncate" {
            val r = TextSearch.find("a".repeat(10_001), regex("a"))
            r.count shouldBe 10_000
            r.truncated shouldBe true
            r.limit shouldBe TextSearch.Limit.MATCHES
        }

        "unicode: a pair is one character, never split" {
            spans("😀", ".") shouldBe listOf(0 to 2)
            spans("a😀b", "[😀-😂]") shouldBe listOf(1 to 3)
            spans("a😀b", "a.b") shouldBe listOf(0 to 4)
            val random = Random(7)
            val alphabet = listOf("a", "b", "😀", "é", "\n", " ")
            repeat(200) {
                val text = (0 until random.nextInt(0, 30)).joinToString("") { alphabet[random.nextInt(alphabet.size)] }
                for (p in listOf(".", "a*", "[^a]", "\\W+", "😀?")) {
                    val r = TextSearch.find(text, regex(p))
                    for (i in 0 until r.count) {
                        for (off in listOf(r.starts[i], r.ends[i])) {
                            val split =
                                off in 1 until text.length &&
                                    text[off - 1].isHighSurrogate() &&
                                    text[off].isLowSurrogate()
                            withClue("$p on ${text.map { it.code }}") { split shouldBe false }
                        }
                    }
                }
            }
        }

        "case folding is per code point" {
            spans("Straße STRASSE", "straße", matchCase = false) shouldBe listOf(0 to 6)
            spans("STRASSE", "straße", matchCase = false) shouldBe emptyList()
            spans("É é", "é", matchCase = false) shouldBe listOf(0 to 1, 2 to 3)
            spans("É é", "[é]", matchCase = false) shouldBe listOf(0 to 1, 2 to 3)
            spans("A a", "[^a]", matchCase = false) shouldBe listOf(1 to 2)
            spans("A a", "[^a]", matchCase = true) shouldBe listOf(0 to 1, 1 to 2)
        }

        "whole word wraps the pattern in boundaries" {
            spans("cat concat cat", "cat", wholeWord = true) shouldBe listOf(0 to 3, 11 to 14)
            spans("a1 a", "a|a1", wholeWord = true) shouldBe listOf(0 to 2, 3 to 4)
        }

        "a cancelled search returns nothing" {
            TextSearch.find("a".repeat(100_000), regex("a"), shouldContinue = { false }).count shouldBe 0
        }

        "quote makes a literal match itself" {
            for (lit in listOf("a.b", "x(y)", "[z]", "1+1=2", "a|b", "\\n", "{1}", "^$", "a*")) {
                val r = TextSearch.find("zz${lit}zz", regex(SafeRegex.quote(lit)))
                withClue(lit) { r.starts.toList() shouldBe listOf(2) }
            }
        }

        "the matches agree with java.util.regex for harmless patterns" {
            val patterns =
                listOf(
                    "ab",
                    "a.c",
                    "[a-c]+",
                    "(?:ab)+c?",
                    "a{2,3}b",
                    "x|ab|abc",
                    "\\w+@\\w+",
                    "\\d{2}-\\d{2}",
                    "(a|b)*c",
                    "^\\w+",
                    "\\w+$",
                    "\\bis\\b",
                    "[^ ]+",
                    "a+b*",
                    "(ab|a)(bc|c)?",
                    "[xyz]{1,2}",
                )
            val texts =
                listOf(
                    "abc abcabc",
                    "aab aaab ab",
                    "xabcx abc\nabc",
                    "foo@bar 12-34 5-6",
                    "this is a test; is it",
                    "zzzxyzxy yz",
                    "abbbc aabbc ac",
                    "ab\nab\nab",
                    "",
                )
            for (p in patterns) {
                val re = regex(p)
                val jre =
                    java.util.regex.Pattern
                        .compile(p, java.util.regex.Pattern.MULTILINE)
                for (t in texts) {
                    val ours = TextSearch.find(t, re).let { r -> (0 until r.count).map { r.starts[it] to r.ends[it] } }
                    val m = jre.matcher(t)
                    val theirs = mutableListOf<Pair<Int, Int>>()
                    while (m.find()) theirs += m.start() to m.end()
                    withClue("pattern=$p text=${t.replace("\n", "\\n")}") { ours shouldBe theirs }
                }
            }
        }
    })
