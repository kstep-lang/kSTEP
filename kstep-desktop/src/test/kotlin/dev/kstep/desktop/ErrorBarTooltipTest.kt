package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.ErrorBarText
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ErrorBarTooltipTest :
    StringSpec({
        fun err(
            n: Int,
            line: Int? = n,
            col: Int? = 1,
            msg: String = "m$n",
        ) = DesktopError("E$n", line, col, msg)

        "one error shows code, position and message" {
            ErrorBarText.tooltip(listOf(err(1, 12, 5))) shouldBe "E1 · 12:5 · m1"
            ErrorBarText.tooltip(listOf(err(1, 12, null))) shouldBe "E1 · 12 · m1"
            ErrorBarText.tooltip(listOf(err(1, null, null))) shouldBe "E1 · — · m1"
        }

        "at most three errors, then a counter" {
            ErrorBarText.tooltip((1..3).map { err(it) }).lines().size shouldBe 3
            ErrorBarText.tooltip((1..4).map { err(it) }).lines().last() shouldBe "+1 more"
            ErrorBarText.tooltip((1..10).map { err(it) }).lines().last() shouldBe "+7 more"
        }

        "lines are cut at 120 characters including the ellipsis" {
            for (n in listOf(119, 120, 121, 500)) {
                val line = ErrorBarText.tooltip(listOf(err(1, 1, 1, "x".repeat(n))))
                (line.length <= 120) shouldBe true
            }
            val exact = ErrorBarText.tooltip(listOf(err(1, 1, 1, "x".repeat(500))))
            exact.length shouldBe 120
            exact.endsWith("…") shouldBe true
        }

        "a cut never splits a surrogate pair" {
            for (pad in 95..112) {
                val t = ErrorBarText.tooltip(listOf(err(1, 1, 1, "x".repeat(pad) + "😀".repeat(40))))
                for (i in t.indices) {
                    if (t[i].isHighSurrogate()) t[i + 1].isLowSurrogate() shouldBe true
                    if (t[i].isLowSurrogate()) t[i - 1].isHighSurrogate() shouldBe true
                }
            }
        }

        "hostile characters never reach the tooltip" {
            val t = ErrorBarText.tooltip(listOf(err(1, 1, 1, "a‮b⁦c‏d؜e\u0001f\u0085g\u001B[31mh\ti\nj")))
            t.lines().size shouldBe 1
            for (c in listOf('‮', '⁦', '‏', '؜', '\u0001', '\u0085', '\u001B', '\t')) {
                t.contains(c) shouldBe false
            }
        }

        "an empty message" {
            ErrorBarText.tooltip(listOf(err(1, 1, 1, ""))) shouldBe "E1 · 1:1 · "
        }

        "tooltips are grouped by line, others are ignored" {
            val map =
                ErrorBarText.tooltipsByLine(
                    listOf(err(1, 2), err(2, 2), err(3, 9), err(4, null), err(5, 0)),
                    lineCount = 5,
                )
            map.keys shouldBe setOf(2)
            map[2]!!.lines() shouldBe listOf("E1 · 2:1 · m1", "E2 · 2:1 · m2")
        }

        "the bar display shows a dash for a missing position, headline and copy text stay as they were" {
            ErrorBarText.displayLine(err(1, null, null)) shouldBe "E1  —  m1"
            ErrorBarText.displayHeadline(listOf(err(1, 3, 4))) shouldBe "E1  3:4  m1"
            ErrorBarText.headline(listOf(err(1, null, null))) shouldBe "E1  m1"
            ErrorBarText.displayLine(err(1, 1, 1, "a‮b")) shouldBe "E1  1:1  a�b"
        }
    })
