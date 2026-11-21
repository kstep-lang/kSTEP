package dev.kstep.desktop.pick

import dev.kstep.desktop.edit.SourceLocation
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class ShowInScriptMessagesTest :
    StringSpec({
        "a found literal needs no message while the buffer matches the preview" {
            ShowInScriptMessages.of(SourceLocation.Found(3), "A", bufferDiffers = false) shouldBe null
        }

        "a found literal in a changed buffer says so" {
            ShowInScriptMessages.of(SourceLocation.Found(3), "A", bufferDiffers = true)!!.text shouldContain
                "script changed since last preview"
        }

        "an ambiguous name offers Find with the quoted query" {
            val m = ShowInScriptMessages.of(SourceLocation.Ambiguous("\"A\""), "A", false)!!
            m.findQuery shouldBe "\"A\""
        }

        "the other outcomes have a message without a find query" {
            for (loc in listOf(
                SourceLocation.NotFound,
                SourceLocation.TooLong,
                SourceLocation.NotLiteral,
                SourceLocation.TimedOut,
            )) {
                val m = ShowInScriptMessages.of(loc, "A", false)!!
                m.findQuery shouldBe null
            }
        }

        "a long name is clipped to 64 characters" {
            val m = ShowInScriptMessages.of(SourceLocation.NotFound, "n".repeat(250), false)!!
            m.text shouldContain "n".repeat(64) + "…"
            m.text shouldNotContain "n".repeat(65)
        }

        "control and bidirectional characters are escaped, never shown raw" {
            val m = ShowInScriptMessages.of(SourceLocation.NotLiteral, "a‮b\u001B[31m", false)!!
            m.text shouldNotContain "‮"
            m.text shouldNotContain "\u001B"
            m.text shouldContain "‹U+202E›"
        }

        "the too-long message does not repeat the name" {
            ShowInScriptMessages.of(SourceLocation.TooLong, "secretname", false)!!.text shouldNotContain "secretname"
        }
    })
