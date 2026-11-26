package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BracketMatcherTest :
    StringSpec({
        fun match(
            text: String,
            caret: Int,
        ): BracketMatch? = BracketMatcher.match(text, KotlinTokenizer.tokenize(text), caret)

        "pairs of all three kinds, caret behind the bracket or in front of it" {
            match("(a)", 1) shouldBe BracketMatch.Pair(0, 2)
            match("(a)", 3) shouldBe BracketMatch.Pair(0, 2)
            match("[a]", 0) shouldBe BracketMatch.Pair(0, 2)
            match("{a}", 1) shouldBe BracketMatch.Pair(0, 2)
            match("{a}", 2) shouldBe BracketMatch.Pair(0, 2)
        }

        "nesting is respected" {
            match("(a(b)c)", 1) shouldBe BracketMatch.Pair(0, 6)
            match("(a(b)c)", 3) shouldBe BracketMatch.Pair(2, 4)
            match("f(g(h()))", 9) shouldBe BracketMatch.Pair(1, 8)
        }

        "between two brackets the one before the caret wins" {
            match(")(", 1) shouldBe BracketMatch.Unmatched(0)
            match("()()", 2) shouldBe BracketMatch.Pair(0, 1)
            match("(()", 2) shouldBe BracketMatch.Pair(1, 2)
        }

        "caret at the start, at the end, empty text and no bracket" {
            match("(a)", 0) shouldBe BracketMatch.Pair(0, 2)
            match("(a)", 3) shouldBe BracketMatch.Pair(0, 2)
            match("", 0) shouldBe null
            match("abc", 1) shouldBe null
            match("(a)", 9) shouldBe null
        }

        "an unbalanced bracket is unmatched" {
            match("(a", 1) shouldBe BracketMatch.Unmatched(0)
            match("a)", 2) shouldBe BracketMatch.Unmatched(1)
        }

        "a bracket of the wrong kind is unmatched" {
            match("(]", 1) shouldBe BracketMatch.Unmatched(0)
            match("[)", 2) shouldBe BracketMatch.Unmatched(1)
            match("(a{b)c}", 1) shouldBe BracketMatch.Unmatched(0)
        }

        "brackets in strings, raw strings and comments are ignored" {
            val s = "(\"a)\")"
            match(s, 1) shouldBe BracketMatch.Pair(0, 5)
            val raw = "(\"\"\"a)\n\"\"\")"
            match(raw, 1) shouldBe BracketMatch.Pair(0, raw.length - 1)
            val line = "(a // )\n)"
            match(line, 1) shouldBe BracketMatch.Pair(0, line.length - 1)
            val block = "(a /* ) */ )"
            match(block, 1) shouldBe BracketMatch.Pair(0, block.length - 1)
        }

        "a candidate inside a string or comment gives nothing" {
            match("\"(a)\"", 2) shouldBe null
            match("// (a)", 4) shouldBe null
            match("/* ( */", 4) shouldBe null
        }

        "code inside a string template counts, the template delimiters do not pair" {
            val t = "x(\"\${f(1)}\")"
            // the outer parenthesis pairs over the string, the inner ones inside the template pair with each other
            match(t, 1) shouldBe BracketMatch.Pair(1, t.length - 1)
            val inner = t.indexOf("f(") + 1
            match(t, inner + 1) shouldBe BracketMatch.Pair(inner, inner + 2)
            match(t, t.indexOf("\${") + 2) shouldBe null
        }

        "a partner farther away than the scan limit gives nothing" {
            val far = "(" + " ".repeat(BracketMatcher.MAX_SCAN_CHARS + 10) + ")"
            match(far, 1) shouldBe null
            match(far, far.length) shouldBe null
            val near = "(" + " ".repeat(BracketMatcher.MAX_SCAN_CHARS - 5) + ")"
            match(near, 1) shouldBe BracketMatch.Pair(0, near.length - 1)
        }

        "a text above the text limit gives nothing" {
            val big = "(" + " ".repeat(BracketMatcher.MAX_TEXT_CHARS) + ")"
            BracketMatcher.match(big, emptyList(), 1) shouldBe null
        }

        "a very long line of openers finishes in time" {
            val text = "(".repeat(200_000)
            val started = System.nanoTime()
            BracketMatcher.match(text, emptyList(), 1) shouldBe null
            ((System.nanoTime() - started) / 1_000_000 < 30_000) shouldBe true
        }
    })
