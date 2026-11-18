package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SelectionMachineTest :
    StringSpec({
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(30.0, 0.0, 0.0),
                names = listOf("Bolt", "Nut"),
            )
        val (_, duplicates) =
            TestScenes.scene(triangle(0.0, 0.0, 0.0), triangle(30.0, 0.0, 0.0), names = listOf("Bolt", "Bolt"))
        val start = SelectionState()

        fun reduce(
            s: SelectionState,
            vararg events: SelectionEvent,
        ) = events.fold(s) { acc, e -> SelectionMachine.reduce(acc, e) }

        "hovering sets and clears the hover only" {
            reduce(start, SelectionEvent.HoverAt(1)) shouldBe SelectionState(hovered = 1)
            reduce(start, SelectionEvent.HoverAt(1), SelectionEvent.HoverAt(null)) shouldBe start
        }

        "clicking a part selects its name, clicking the same part again keeps it" {
            val once = reduce(start, SelectionEvent.ClickAt(0, "Bolt"))
            once.selected shouldBe "Bolt"
            reduce(once, SelectionEvent.ClickAt(0, "Bolt")).selected shouldBe "Bolt"
            reduce(once, SelectionEvent.ClickAt(1, "Nut")).selected shouldBe "Nut"
        }

        "clicking empty space or pressing Escape ends the selection" {
            val sel = reduce(start, SelectionEvent.ClickAt(0, "Bolt"))
            reduce(sel, SelectionEvent.ClickAt(null, null)).selected shouldBe null
            reduce(sel, SelectionEvent.Escape).selected shouldBe null
        }

        "drag start and pointer exit clear the hover but keep the selection" {
            val s = reduce(start, SelectionEvent.ClickAt(0, "Bolt"), SelectionEvent.HoverAt(1))
            reduce(s, SelectionEvent.DragStart) shouldBe SelectionState(null, "Bolt")
            reduce(s, SelectionEvent.PointerExit) shouldBe SelectionState(null, "Bolt")
        }

        "a replaced scene keeps a selection whose name still exists and always clears the hover" {
            val s = reduce(start, SelectionEvent.ClickAt(0, "Bolt"), SelectionEvent.HoverAt(1))
            reduce(s, SelectionEvent.SceneReplaced(table)) shouldBe SelectionState(null, "Bolt")
        }

        "a replaced scene drops a selection whose name is gone, or any selection without a table" {
            val s = reduce(start, SelectionEvent.ClickAt(1, "Washer"))
            reduce(s, SelectionEvent.SceneReplaced(table)).selected shouldBe null
            val bolt = reduce(start, SelectionEvent.ClickAt(0, "Bolt"))
            reduce(bolt, SelectionEvent.SceneReplaced(null)).selected shouldBe null
        }

        "with duplicate names the clicked occurrence is selected" {
            val s = reduce(start, SelectionEvent.ClickAt(1, "Bolt", 1))
            SelectionMachine.selectedIndex(s, duplicates) shouldBe 1
            SelectionMachine.selectedIndex(s, table) shouldBe -1
            val first = reduce(start, SelectionEvent.ClickAt(0, "Bolt"))
            SelectionMachine.selectedIndex(first, duplicates) shouldBe 0
            SelectionMachine.selectedIndex(start, table) shouldBe -1
            SelectionMachine.selectedIndex(s, null) shouldBe -1
            reduce(s, SelectionEvent.SceneReplaced(duplicates)).occurrence shouldBe 1
            reduce(s, SelectionEvent.SceneReplaced(table)).selected shouldBe null
        }
    })
