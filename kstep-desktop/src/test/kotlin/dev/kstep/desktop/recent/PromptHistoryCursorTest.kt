package dev.kstep.desktop.recent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PromptHistoryCursorTest :
    StringSpec({
        val entries = listOf("newest", "middle", "oldest")

        "up walks to older entries and stops at the oldest" {
            val (n1, t1) = PromptHistoryCursor.up(HistoryNav(), entries, "my draft")!!
            t1 shouldBe "newest"
            n1.index shouldBe 0
            val (n2, t2) = PromptHistoryCursor.up(n1, entries, t1)!!
            t2 shouldBe "middle"
            val (n3, t3) = PromptHistoryCursor.up(n2, entries, t2)!!
            t3 shouldBe "oldest"
            PromptHistoryCursor.up(n3, entries, t3) shouldBe null
        }

        "down walks back and returns the draft" {
            var nav = HistoryNav()
            var text = "my draft"
            repeat(2) {
                val (n, t) = PromptHistoryCursor.up(nav, entries, text)!!
                nav = n
                text = t
            }
            val (n1, t1) = PromptHistoryCursor.down(nav, entries)!!
            t1 shouldBe "newest"
            val (n2, t2) = PromptHistoryCursor.down(n1, entries)!!
            t2 shouldBe "my draft"
            n2 shouldBe HistoryNav()
            PromptHistoryCursor.down(n2, entries) shouldBe null
        }

        "an empty history does nothing" {
            PromptHistoryCursor.up(HistoryNav(), emptyList(), "x") shouldBe null
            PromptHistoryCursor.down(HistoryNav(), emptyList()) shouldBe null
        }

        "only the first line reacts to up and only the last line to down" {
            val text = "one\ntwo\nthree"
            PromptHistoryCursor.isFirstLine(text, 0) shouldBe true
            PromptHistoryCursor.isFirstLine(text, 3) shouldBe true
            PromptHistoryCursor.isFirstLine(text, 4) shouldBe false
            PromptHistoryCursor.isFirstLine(text, 9) shouldBe false
            PromptHistoryCursor.isLastLine(text, 13) shouldBe true
            PromptHistoryCursor.isLastLine(text, 8) shouldBe true
            PromptHistoryCursor.isLastLine(text, 7) shouldBe false
            PromptHistoryCursor.isFirstLine("", 0) shouldBe true
            PromptHistoryCursor.isLastLine("", 0) shouldBe true
        }
    })
