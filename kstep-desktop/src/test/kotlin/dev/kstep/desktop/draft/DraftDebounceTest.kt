package dev.kstep.desktop.draft

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DraftDebounceTest :
    StringSpec({
        "nothing is pending at first" {
            DraftDebounce().dueAt() shouldBe null
        }

        "the write is due 2 s after the last change" {
            val d = DraftDebounce()
            d.onChange(1_000)
            d.dueAt() shouldBe 3_000
            d.isDue(2_999) shouldBe false
            d.isDue(3_000) shouldBe true
            d.onChange(2_500)
            d.dueAt() shouldBe 4_500
        }

        "continuous typing is written at the latest 20 s after the first change" {
            val d = DraftDebounce()
            var t = 0L
            while (t <= 30_000) {
                d.onChange(t)
                t += 500
            }
            d.dueAt() shouldBe 20_000
            d.isDue(20_000) shouldBe true
        }

        "writing resets the window" {
            val d = DraftDebounce()
            d.onChange(0)
            d.onWritten()
            d.dueAt() shouldBe null
            d.onChange(50_000)
            d.dueAt() shouldBe 52_000
        }
    })
