package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

class IncrementalTokenizerTest :
    StringSpec({
        fun check(
            inc: IncrementalTokenizer,
            text: String,
        ) {
            inc.update(text) shouldBe KotlinTokenizer.tokenize(text)
            inc.verifyAgainstFullScan() shouldBe true
        }

        "deep templates over the limit, multi-line, with edits at the closing braces" {
            val inc = IncrementalTokenizer()
            var text = (1..40).joinToString("\n") { "\"\${" } + "\nx\n" + (1..40).joinToString("\n") { "}\"" }
            check(inc, text)
            var at = text.indexOf('}')
            while (at >= 0) {
                text = text.removeRange(at, at + 1)
                check(inc, text)
                text = text.substring(0, at) + "}" + text.substring(at)
                check(inc, text)
                at = text.indexOf('}', at + 7)
            }
        }

        "nested block comments of depth 1000" {
            val inc = IncrementalTokenizer()
            var text = (1..1000).joinToString("\n") { "/* c$it" } + "\n" + (1..1000).joinToString("\n") { "*/" }
            check(inc, text)
            text = text.replaceFirst("*/", "")
            check(inc, text)
            text += "\n*/"
            check(inc, text)
            val mid = text.length / 2
            text = text.substring(0, mid) + "*/" + text.substring(mid)
            check(inc, text)
        }

        "a raw string over 1000 lines opened and closed again" {
            val inc = IncrementalTokenizer()
            val body = (1..1000).joinToString("\n") { "val a$it = 1 // line" }
            var text = body
            check(inc, text)
            text = "\"\"\"" + text
            check(inc, text)
            text = text.removePrefix("\"\"\"")
            check(inc, text)
            text = "\"\"\"\n" + text + "\n\"\"\""
            check(inc, text)
            text = text.replaceFirst("\"\"\"\n", "\n")
            check(inc, text)
        }

        "a line of 20000 characters" {
            val inc = IncrementalTokenizer()
            var text = "val x = 1; ".repeat(1_800) + "\nval y = 2\n"
            check(inc, text)
            text = text.substring(0, 10_000) + "\"" + text.substring(10_000)
            check(inc, text)
        }

        "a text over the limit falls back to a full scan and rebuilds afterwards" {
            val inc = IncrementalTokenizer(maxChars = 100)
            val small = "val a = 1\nval b = 2\n"
            check(inc, small)
            val big = "val c = 3\n".repeat(30)
            inc.update(big) shouldBe KotlinTokenizer.tokenize(big)
            inc.lastStats.full shouldBe true
            inc.update(big + "x") shouldBe KotlinTokenizer.tokenize(big + "x")
            inc.lastStats.full shouldBe true
            check(inc, small)
            inc.lastStats.full shouldBe true
            check(inc, small + "val d = 4\n")
            inc.lastStats.full shouldBe false
        }

        "a list returned earlier is not changed by a later update" {
            val inc = IncrementalTokenizer()
            val t1 = "val a = 1\nval b = \"x\"\nval c = 3\n"
            val first = inc.update(t1)
            val snapshot = first.toList()
            inc.update("val a = 1\nval b = \"x\nval c = 3\n")
            inc.update("zzz $t1")
            first shouldBe snapshot
        }

        "degenerate texts never throw" {
            val inc = IncrementalTokenizer()
            for (t in listOf("", "\"", "/*", "\"\"\"", "\${", "}", "\n", "\n\n/*\n", "x", "")) check(inc, t)
        }

        "an unterminated raw string at the end is extended and closed" {
            val inc = IncrementalTokenizer()
            check(inc, "val a = \"\"\"x\n")
            check(inc, "val a = \"\"\"x\ny\n")
            check(inc, "val a = \"\"\"x\ny\n\"\"\"\n")
        }

        "one typed character in a 1 MiB script scans only a line or two" {
            val line = "val a = shape(\"x \$y\") + 12 // comment\n"
            val text = line.repeat(1_040_000 / line.length)
            val inc = IncrementalTokenizer()
            inc.update(text)
            val mid = text.length / 2
            val edited = text.substring(0, mid) + "z" + text.substring(mid)
            inc.update(edited) shouldBe KotlinTokenizer.tokenize(edited)
            val s = inc.lastStats
            println("incremental: 1 MiB, one char typed, scanned ${s.scannedChars} chars, full=${s.full}")
            s.full shouldBe false
            s.scannedChars shouldBeLessThanOrEqual 3 * line.length
            inc.verifyAgainstFullScan() shouldBe true

            // an unterminated comment makes everything after it a comment: the rest is scanned (the known worst case)
            val open = edited.substring(0, mid) + "/*" + edited.substring(mid)
            inc.update(open) shouldBe KotlinTokenizer.tokenize(open)
            println("incremental: opened /*, scanned ${inc.lastStats.scannedChars} of ${open.length} chars")
            // closing it changes every later state (comment to code), so that edit scans the rest too ...
            val closed = open.substring(0, mid + 2) + "*/" + open.substring(mid + 2)
            inc.update(closed) shouldBe KotlinTokenizer.tokenize(closed)
            println("incremental: closed */, scanned ${inc.lastStats.scannedChars} chars, full=${inc.lastStats.full}")
            inc.lastStats.full shouldBe false
            // ... and the next edit is local again
            val typed = closed.substring(0, mid / 2) + "z" + closed.substring(mid / 2)
            inc.update(typed) shouldBe KotlinTokenizer.tokenize(typed)
            println("incremental: typed after closing, scanned ${inc.lastStats.scannedChars} chars")
            inc.lastStats.full shouldBe false
            inc.lastStats.scannedChars shouldBeLessThanOrEqual 3 * line.length
            inc.verifyAgainstFullScan() shouldBe true
        }
    })
