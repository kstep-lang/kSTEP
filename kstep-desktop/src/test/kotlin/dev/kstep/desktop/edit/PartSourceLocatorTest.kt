package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** The name-to-literal heuristic of "Show in Script" (ADR-0068). */
class PartSourceLocatorTest :
    StringSpec({
        fun locate(
            text: String,
            name: String,
        ) = PartSourceLocator.locate(text, name)

        "one literal is found right after the opening quote" {
            val text = "val a = part(\"Bracket\")\n"
            locate(text, "Bracket") shouldBe SourceLocation.Found(text.indexOf("\"Bracket\"") + 1)
        }

        "a declaration and a comment make the name ambiguous" {
            val text = "// the \"Bracket\"\nval a = part(\"Bracket\")\n"
            locate(text, "Bracket") shouldBe SourceLocation.Ambiguous("\"Bracket\"")
        }

        "no literal means not found" {
            locate("val a = 1", "Bracket") shouldBe SourceLocation.NotFound
        }

        "a longer name does not match a prefix" {
            locate("part(\"BracketHolder\")", "Bracket") shouldBe SourceLocation.NotFound
        }

        "the match is case sensitive" {
            locate("part(\"bracket\")", "Bracket") shouldBe SourceLocation.NotFound
        }

        "the limit is 254 characters" {
            val ok = "a".repeat(PartSourceLocator.MAX_NAME_CHARS)
            locate("x(\"$ok\")", ok).shouldBeFound()
            locate("x(\"${ok}a\")", ok + "a") shouldBe SourceLocation.TooLong
        }

        "names that cannot be a plain literal are not searched" {
            for (name in listOf("a\"b", "a\\b", "a\$b", "a\tb", "a\u007Fb", "a\nb")) {
                locate("x(\"$name\")", name) shouldBe SourceLocation.NotLiteral
            }
        }

        "an empty name is not found" {
            locate("x(\"\")", "") shouldBe SourceLocation.NotFound
        }

        "unicode and surrogate pairs are matched as literal text" {
            val name = "Bügel 😀"
            val text = "part(\"$name\")"
            locate(text, name) shouldBe SourceLocation.Found(text.indexOf('"') + 1)
        }

        "a bidirectional control in a name still matches exactly, nothing is interpreted" {
            val name = "ab‮cd"
            val text = "part(\"$name\")"
            locate(text, name) shouldBe SourceLocation.Found(text.indexOf('"') + 1)
        }

        "leading and trailing spaces are part of the name" {
            locate("part(\" A \")", " A ").shouldBeFound()
            locate("part(\"A\")", " A ") shouldBe SourceLocation.NotFound
        }

        "a CRLF file gives the right offset and line" {
            val text = "a\r\nb\r\npart(\"X\")\r\n"
            val found = locate(text, "X") as SourceLocation.Found
            found.offset shouldBe text.indexOf("\"X\"") + 1
            CaretMapper.lineColumn(text, found.offset).first shouldBe 3
        }

        "a one MiB buffer with the literal at the end is searched in time".config(timeout = 30.seconds) {
            val text = "x".repeat(1 shl 20) + "part(\"Z\")"
            locate(text, "Z").shouldBeFound()
        }

        "an expired deadline reports a timeout instead of not found" {
            val text = "x".repeat(100_000) + "part(\"Z\")"
            var now = 0L
            val clock = { now.also { now += 1_000L } }
            PartSourceLocator.locate(text, "Z", budgetNanos = 0, nanoTime = clock) shouldBe SourceLocation.TimedOut
        }
    })

private fun SourceLocation.shouldBeFound() {
    (this is SourceLocation.Found) shouldBe true
}
