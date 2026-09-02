package dev.kstep.render.svg

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SvgEscapingTest :
    StringSpec({
        "the five predefined XML entities are escaped" {
            SvgEscaping.escape("& < > \" '") shouldBe "&amp; &lt; &gt; &quot; &apos;"
        }

        "an SVG/script injection attempt is neutralized" {
            val input = "</text><script>alert(1)</script>"
            val escaped = SvgEscaping.escape(input)
            escaped.contains("<script") shouldBe false
            escaped.contains("</text>") shouldBe false
            escaped shouldBe "&lt;/text&gt;&lt;script&gt;alert(1)&lt;/script&gt;"
        }

        "ASCII control characters other than tab/newline/CR are dropped" {
            SvgEscaping.escape("a\u0000b\u0007c") shouldBe "abc"
        }

        "tab, newline and carriage return collapse to a single space" {
            SvgEscaping.escape("a\tb\nc\rd") shouldBe "a b c d"
        }

        "non-ASCII characters are replaced with a question mark" {
            SvgEscaping.escape("Bräcket ü ￿") shouldBe "Br?cket ? ?"
        }

        "plain ASCII text is passed through unchanged" {
            SvgEscaping.escape("Bracket-001, rev A") shouldBe "Bracket-001, rev A"
        }

        "an empty string escapes to an empty string" {
            SvgEscaping.escape("") shouldBe ""
        }
    })
