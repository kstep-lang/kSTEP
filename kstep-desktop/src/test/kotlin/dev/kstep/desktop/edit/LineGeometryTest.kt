package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class LineGeometryTest :
    StringSpec({
        "fixed and table geometry with equal values agree" {
            val fixed = FixedLineGeometry(5, 20f)
            val table = TableLineGeometry.of(5, floatArrayOf(0f, 20f, 40f, 60f, 80f), 100f)
            for (line in 1..5) {
                table.top(line) shouldBe fixed.top(line)
                table.height(line) shouldBe fixed.height(line)
            }
            for (y in listOf(0f, 19.9f, 20f, 59f, 99.9f)) table.lineAt(y) shouldBe fixed.lineAt(y)
            table.totalHeight shouldBe fixed.totalHeight
        }

        "lineAt is clamped and exact on the top of a line" {
            val g = TableLineGeometry.of(3, floatArrayOf(0f, 20f, 70f), 90f)
            g.lineAt(-5f) shouldBe 1
            g.lineAt(0f) shouldBe 1
            g.lineAt(19.99f) shouldBe 1
            g.lineAt(20f) shouldBe 2
            g.lineAt(70f) shouldBe 3
            g.lineAt(90f) shouldBe 3
            g.lineAt(1_000_000f) shouldBe 3
            g.lineAt(Float.NaN) shouldBe 1
        }

        "a line that wraps to 50 visual lines is 50 lines high" {
            val g = TableLineGeometry.of(2, floatArrayOf(0f, 1000f), 1020f)
            g.height(1) shouldBe 1000f
            g.height(2) shouldBe 20f
            g.lineAt(999f) shouldBe 1
            g.lineAt(1000f) shouldBe 2
        }

        "an empty file has one line, a line height of 0 or less is guarded" {
            val g = FixedLineGeometry(0, 20f)
            g.lineCount shouldBe 1
            g.lineAt(100f) shouldBe 1
            val zero = FixedLineGeometry(3, 0f)
            zero.lineAt(2f) shouldBe 3
            zero.top(2) shouldBe 1f
        }

        "top and height clamp the line number" {
            val g = FixedLineGeometry(3, 10f)
            g.top(0) shouldBe 0f
            g.top(99) shouldBe 20f
        }

        "a table whose size differs from the line count is refused" {
            val result = runCatching { TableLineGeometry.of(3, floatArrayOf(0f, 10f), 20f) }
            result.isFailure shouldBe true
        }

        "scrollFor with a table: visible line does nothing, far line scrolls, margin shrinks in a small viewport" {
            val g = TableLineGeometry.of(100, FloatArray(100) { it * 20f + if (it >= 10) 80f else 0f }, 2080f)
            // line 5 at 80..100, viewport 400
            ScrollTarget.scrollFor(5, g, 0, 400, 20f) shouldBe null
            // line 50 sits at 49*20+80 = 1060
            ScrollTarget.scrollFor(50, g, 0, 400, 20f) shouldBe (1060 - 60)
            // clamped to the end
            ScrollTarget.scrollFor(100, g, 0, 400, 20f) shouldBe (2080 - 400)
            // tiny viewport: margin 0
            ScrollTarget.scrollFor(1, g, 80, 40, 20f) shouldBe 0
        }

        "the old overload gives the same values as the geometry overload" {
            val g = FixedLineGeometry(100, 20f)
            for (line in listOf(1, 2, 10, 19, 50, 100)) {
                for (scroll in listOf(0, 400, 900)) {
                    ScrollTarget.scrollFor(line, 100, scroll, 400, 20f) shouldBe
                        ScrollTarget.scrollFor(line, g, scroll, 400, 20f)
                }
            }
        }
    })
