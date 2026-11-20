package dev.kstep.desktop.edit

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.Severity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CaretMapperTest :
    StringSpec({
        val text = "alpha\n  beta\n\ngamma"

        "a null line means no jump" { CaretMapper.toOffset(text, null, 3) shouldBe null }

        "line and column map to the offset" {
            CaretMapper.toOffset(text, 1, 1) shouldBe 0
            CaretMapper.toOffset(text, 1, 3) shouldBe 2
            CaretMapper.toOffset(text, 2, 4) shouldBe 9
        }

        "a line past the end clamps to the last line" { CaretMapper.toOffset(text, 99, 2) shouldBe text.length - 4 }

        "a null column is the first non-blank character" {
            CaretMapper.toOffset(text, 2, null) shouldBe 8
            CaretMapper.toOffset(text, 3, null) shouldBe 13
        }

        "a column past the line end clamps to the line end" { CaretMapper.toOffset(text, 1, 99) shouldBe 5 }

        "values below one count as one" {
            CaretMapper.toOffset(text, 0, 0) shouldBe 0
            CaretMapper.toOffset(text, -5, -5) shouldBe 0
        }

        "empty text" {
            CaretMapper.toOffset("", 3, 3) shouldBe 0
            CaretMapper.lineColumn("", 0) shouldBe (1 to 1)
        }

        "columns are UTF-16 units" {
            val s = "a😀b"
            CaretMapper.toOffset(s, 1, 4) shouldBe 3
            CaretMapper.lineColumn(s, 3) shouldBe (1 to 4)
        }

        "lineColumn is the inverse of toOffset" {
            CaretMapper.lineColumn(text, 9) shouldBe (2 to 4)
            CaretMapper.lineColumn(text, 14) shouldBe (4 to 1)
        }

        "marked lines keep the worst severity and ignore lines outside the buffer" {
            val marks =
                CaretMapper.markedLines(
                    listOf(
                        DesktopError("W", 2, 1, "w", Severity.WARNING),
                        DesktopError("E", 2, 1, "e", Severity.ERROR),
                        DesktopError("O", 50, 1, "out"),
                        DesktopError("N", null, null, "none"),
                    ),
                    lineCount = 4,
                )
            marks shouldBe mapOf(2 to Severity.ERROR)
        }

        "the clamp rule in one place: past the end, past the line, below one, null line" {
            val t = "ab\ncd"
            CaretMapper.toOffset(t, 99, 1) shouldBe 3
            CaretMapper.toOffset(t, 1, 99) shouldBe 2
            CaretMapper.toOffset(t, 0, 1) shouldBe 0
            CaretMapper.toOffset(t, -3, -3) shouldBe 0
            CaretMapper.toOffset(t, null, null) shouldBe null
            CaretMapper.toOffset("😀x\nz", 1, 99) shouldBe 3
        }
    })
