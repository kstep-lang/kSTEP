package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class EditHistoryTest :
    StringSpec({
        var now = 0L

        fun history(
            maxSteps: Int = 500,
            maxChars: Int = 16 * 1024 * 1024,
        ): EditHistory {
            now = 0
            return EditHistory(maxSteps, maxChars, 1_000) { now }
        }

        "consecutive typing is one step" {
            val h = history()
            h.record("", 0, EditKind.TYPING, 1)
            now = 100
            h.record("a", 1, EditKind.TYPING, 2)
            now = 200
            h.record("ab", 2, EditKind.TYPING, 3)
            h.undo("abc", 3) shouldBe EditHistory.Entry("", 0)
            h.canUndo shouldBe false
        }

        "a pause longer than a second starts a new step" {
            val h = history()
            h.record("", 0, EditKind.TYPING, 1)
            now = 1_500
            h.record("a", 1, EditKind.TYPING, 2)
            h.undo("ab", 2) shouldBe EditHistory.Entry("a", 1)
            h.undo("a", 1) shouldBe EditHistory.Entry("", 0)
        }

        "a line break and a caret jump start a new step" {
            val h = history()
            h.record("a", 1, EditKind.TYPING, 2)
            h.record("ab", 2, EditKind.TYPING, 3, insertedNewline = true)
            h.record("ab\n", 3, EditKind.TYPING, 4)
            h.record("ab\nc", 0, EditKind.TYPING, 1)
            var steps = 0
            while (h.undo("x", 0) != null) steps++
            steps shouldBe 4
        }

        "a new edit clears redo" {
            val h = history()
            h.record("a", 1, EditKind.OTHER, 2)
            h.undo("ab", 2)
            h.canRedo shouldBe true
            h.record("a", 1, EditKind.OTHER, 2)
            h.canRedo shouldBe false
        }

        "redo restores the undone state" {
            val h = history()
            h.record("a", 1, EditKind.OTHER, 2)
            h.undo("ab", 2)
            h.redo("a", 1) shouldBe EditHistory.Entry("ab", 2)
        }

        "the step limit drops the oldest" {
            val h = history(maxSteps = 3)
            repeat(10) { h.record("t$it", 0, EditKind.OTHER, 0) }
            var steps = 0
            while (h.undo("x", 0) != null) steps++
            steps shouldBe 3
        }

        "the character budget drops the oldest" {
            val h = history(maxChars = 25)
            repeat(10) { h.record("0123456789", 0, EditKind.OTHER, 0) }
            var steps = 0
            while (h.undo("x", 0) != null) steps++
            (steps <= 3) shouldBe true
        }

        "a reload is a separate undoable step" {
            val h = history()
            h.record("a", 1, EditKind.TYPING, 2)
            h.record("old", 0, EditKind.RELOAD, 0)
            h.undo("new", 0) shouldBe EditHistory.Entry("old", 0)
        }
    })
