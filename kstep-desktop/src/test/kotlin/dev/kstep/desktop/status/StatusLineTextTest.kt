package dev.kstep.desktop.status

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

class StatusLineTextTest :
    StringSpec({
        fun noBrokenSurrogate(text: String) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (Character.isHighSurrogate(c)) {
                    (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) shouldBe true
                    i++
                } else {
                    Character.isLowSurrogate(c) shouldBe false
                }
                i++
            }
        }

        "a short name stays as it is and no file says so" {
            StatusLineText.fileLabel("bracket.kstep.kts") shouldBe "bracket.kstep.kts"
            StatusLineText.fileLabel(null) shouldBe "No file"
        }

        "a long script name keeps its extension and the limit" {
            val label = StatusLineText.fileLabel("a".repeat(200) + ".kstep.kts")
            label.length shouldBeLessThanOrEqualTo 32
            label shouldEndWith ".kstep.kts"
            label shouldContain "…"
        }

        "another extension is kept too" {
            val label = StatusLineText.shorten("b".repeat(80) + ".step", 20)
            label shouldEndWith ".step"
            label.length shouldBeLessThanOrEqualTo 20
        }

        "CJK and emoji are never cut in the middle of a surrogate pair" {
            val emoji = "😀".repeat(40) + ".kstep.kts"
            for (max in 8..40) noBrokenSurrogate(StatusLineText.shorten(emoji, max))
            val cjk = "部品".repeat(60)
            for (max in 8..40) {
                StatusLineText.shorten(cjk, max).length shouldBeLessThanOrEqualTo max
            }
        }

        "direction and control characters are shown as markers, never raw" {
            val label = StatusLineText.fileLabel("a‮b\u0000c‎d\ne.kstep.kts", 64)
            label shouldContain "‹U+202E›"
            label shouldContain "‹U+0000›"
            label shouldContain "‹U+000A›"
            for (raw in listOf("‮", "\u0000", "‎", "\n")) label shouldNotContain raw
        }

        "a marker is never torn apart" {
            val name = "‹U+202E›".repeat(1) + "x".repeat(60) + "‮".repeat(10) + ".kstep.kts"
            for (max in 8..50) {
                val label = StatusLineText.fileLabel(name, max)
                label.length shouldBeLessThanOrEqualTo max
                val open = label.count { it == '‹' }
                val close = label.count { it == '›' }
                open shouldBe close
            }
        }

        "below eight characters nothing is shortened" {
            StatusLineText.shorten("a".repeat(30), 7) shouldBe "a".repeat(30)
        }

        "an extension longer than the budget falls back to the middle of the whole name" {
            val name = "x" + "." + "y".repeat(60)
            val label = StatusLineText.shorten(name, 20)
            label.length shouldBeLessThanOrEqualTo 20
            label shouldContain "…"
        }

        "a name of only dots or blanks does not crash" {
            StatusLineText.shorten(".".repeat(100), 16).length shouldBeLessThanOrEqualTo 16
            StatusLineText.shorten(" ".repeat(100), 16).length shouldBeLessThanOrEqualTo 16
            StatusLineText.fileLabel("") shouldBe ""
        }
    })
