package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis

class FrontmatterParserTest :
    StringSpec({
        fun parse(vararg lines: String) = FrontmatterParser.parse(lines.joinToString("\n") + "\n")

        "no front matter" {
            val f = parse("# Title", "text")
            f.ignored shouldBe false
            f.bodyStartLine shouldBe 1
            f.title shouldBe null
        }

        "title in the three scalar styles" {
            parse("---", "title: Plain Title", "---").title shouldBe "Plain Title"
            parse("---", "title: \"Say \\\"hi\\\" \\\\ now\"", "---").title shouldBe "Say \"hi\" \\ now"
            parse("---", "title: 'it''s'", "---").title shouldBe "it's"
            parse("---", "title: a # comment", "---").title shouldBe "a"
        }

        "tags as a flow list and as a block list" {
            parse("---", "tags: [a, \"b, c\", d]", "---").tags shouldBe listOf("a", "b, c", "d")
            parse("---", "tags:", "  - x", "  - 'y'", "---").tags shouldBe listOf("x", "y")
        }

        "the body starts after the closing line; a fence inside does not count" {
            val f = parse("---", "title: T", "---", "body")
            f.bodyStartLine shouldBe 4
            val text = "---\nnote: |\n```kstep\nx\n```\n---\n"
            FrontmatterParser.parse(text).ignored shouldBe true
        }

        "a header without an end is ignored and the text is normal markdown" {
            val f = parse("---", "title: x", "text")
            f.ignored shouldBe true
            f.bodyStartLine shouldBe 1
        }

        "a header of more than 200 lines is ignored" {
            val lines = mutableListOf("---")
            repeat(199) { lines += "k$it: v" }
            lines += "---"
            FrontmatterParser.parse(lines.joinToString("\n") + "\n").ignored shouldBe true
            val ok = mutableListOf("---")
            repeat(50) { ok += "k$it: v" }
            ok += "---"
            FrontmatterParser.parse(ok.joinToString("\n") + "\n").ignored shouldBe false
        }

        "anchors, aliases, tags, merge keys and block scalars are never resolved" {
            parse("---", "a: &x 1", "---").ignored shouldBe true
            parse("---", "a: *x", "---").ignored shouldBe true
            parse("---", "a: !!python/object x", "---").ignored shouldBe true
            parse("---", "<<: *x", "---").ignored shouldBe true
            parse("---", "a: |", "  text", "---").ignored shouldBe true
            parse("---", "a: >", "  text", "---").ignored shouldBe true
            parse("---", "a: {b: c}", "---").ignored shouldBe true
            parse("---", "tags: [[a]]", "---").ignored shouldBe true
            parse("---", "tags:", "  - - a", "---").ignored shouldBe true
        }

        "limits: 65 keys, a 1025 character scalar, 33 tags" {
            val keys = (1..65).map { "k$it: v" }
            FrontmatterParser.parse((listOf("---") + keys + "---").joinToString("\n") + "\n").ignored shouldBe true
            parse("---", "title: ${"x".repeat(1_025)}", "---").ignored shouldBe true
            val tags = (1..33).joinToString(", ") { "t$it" }
            parse("---", "tags: [$tags]", "---").ignored shouldBe true
        }

        "a billion-laughs header is ignored fast" {
            val lines =
                mutableListOf("---", "a: &a [x, x, x, x, x, x, x, x, x]")
            ('b'..'i').forEachIndexed { i, c ->
                val prev = if (i == 0) 'a' else ('a' + i)
                lines += "$c: &$c [*$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev, *$prev]"
            }
            lines += "---"
            var result: Frontmatter? = null
            val ms = measureTimeMillis { result = FrontmatterParser.parse(lines.joinToString("\n") + "\n") }
            result!!.ignored shouldBe true
            (ms < 1_000) shouldBe true
        }
    })
