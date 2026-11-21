package dev.kstep.desktop.recent

import dev.kstep.desktop.ai.repair.AiLimits
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The prompt history is pure logic: order, dedupe, sanitizing, clamping (ADR-0069). */
class PromptHistoryTest :
    StringSpec({
        "the character limit equals the prompt limit of the AI panel" {
            PromptHistory.MAX_CHARS shouldBe AiLimits().maxPromptChars
        }

        "record puts the newest first and moves an equal older entry up" {
            val h =
                PromptHistory
                    .empty()
                    .record("a")
                    .record("b")
                    .record("c")
                    .record("a")
            h.entries shouldBe listOf("a", "c", "b")
        }

        "empty and whitespace-only prompts change nothing" {
            val h = PromptHistory.empty().record("a")
            h.record("").entries shouldBe listOf("a")
            h.record("  \n\t ").entries shouldBe listOf("a")
        }

        "at most 50 entries, the oldest fall off" {
            var h = PromptHistory.empty()
            repeat(60) { h = h.record("p$it") }
            h.entries.size shouldBe PromptHistory.MAX_ENTRIES
            h.entries.first() shouldBe "p59"
            h.entries.last() shouldBe "p10"
        }

        "control and bidi characters go, newline and tab stay" {
            PromptHistory.sanitize("a\u0000b‮c\nd\te\u0085") shouldBe "abc\nd\te"
            PromptHistory.sanitize("‮") shouldBe null
        }

        "a long prompt is clamped without splitting a surrogate pair" {
            val long = "x".repeat(PromptHistory.MAX_CHARS - 1) + "😀" + "tail"
            val s = PromptHistory.sanitize(long)!!
            s.length shouldBe PromptHistory.MAX_CHARS - 1
            s.last() shouldBe 'x'
            PromptHistory.sanitize("y".repeat(10_000))!!.length shouldBe PromptHistory.MAX_CHARS
        }

        "of() sanitizes, drops empties, de-duplicates (the first wins) and clamps the count" {
            val raw = listOf("a", "", "b", "a", "  ", "c") + (1..80).map { "z$it" }
            val h = PromptHistory.of(raw)
            h.entries.take(3) shouldBe listOf("a", "b", "c")
            h.entries.size shouldBe PromptHistory.MAX_ENTRIES
        }
    })
