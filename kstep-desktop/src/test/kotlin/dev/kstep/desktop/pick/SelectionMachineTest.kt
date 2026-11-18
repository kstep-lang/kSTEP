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

        // Top > [Bolt, Sub > [Nut]]
        val tree =
            AssemblyTree(
                arrayOf("Top", "Bolt", "Sub", "Nut"),
                intArrayOf(-1, 0, 0, 2),
                intArrayOf(-1, 0, -1, 1),
            )
        val subKey = tree.key(2)

        "selecting a group excludes a part selection and vice versa" {
            val part = reduce(start, SelectionEvent.ClickAt(0, "Bolt"))
            val group = reduce(part, SelectionEvent.SelectGroup(subKey))
            group.selectedGroup shouldBe subKey
            group.selected shouldBe null
            val back = reduce(group, SelectionEvent.ClickAt(1, "Nut"))
            back.selectedGroup shouldBe null
            back.selected shouldBe "Nut"
        }

        "clicking empty space or Escape also ends a group selection" {
            val group = reduce(start, SelectionEvent.SelectGroup(subKey))
            reduce(group, SelectionEvent.ClickAt(null, null)).selectedGroup shouldBe null
            reduce(group, SelectionEvent.Escape).selectedGroup shouldBe null
        }

        "tree hover is set, cleared, and kept by drag start" {
            val over = reduce(start, SelectionEvent.TreeHover(1..2))
            over.treeHover shouldBe 1..2
            reduce(over, SelectionEvent.DragStart).treeHover shouldBe 1..2
            reduce(over, SelectionEvent.TreeHover(null)).treeHover shouldBe null
        }

        "a replaced scene keeps a group whose path still exists and clears the tree hover" {
            val s = reduce(start, SelectionEvent.SelectGroup(subKey), SelectionEvent.TreeHover(0..1))
            val kept = reduce(s, SelectionEvent.SceneReplaced(table, tree))
            kept.selectedGroup shouldBe subKey
            kept.treeHover shouldBe null
        }

        "a replaced scene drops a group that is gone, or a group of a leaf key, or without a tree" {
            val other =
                AssemblyTree(arrayOf("Top", "Bolt", "Other", "Nut"), intArrayOf(-1, 0, 0, 2), intArrayOf(-1, 0, -1, 1))
            val s = reduce(start, SelectionEvent.SelectGroup(subKey))
            reduce(s, SelectionEvent.SceneReplaced(table, other)).selectedGroup shouldBe null
            reduce(s, SelectionEvent.SceneReplaced(table, null)).selectedGroup shouldBe null
            val leaf = reduce(start, SelectionEvent.SelectGroup(tree.key(1)))
            reduce(leaf, SelectionEvent.SceneReplaced(table, tree)).selectedGroup shouldBe null
        }

        "the highlight range is the group's range, the single part, or none" {
            SelectionMachine.highlightRange(reduce(start, SelectionEvent.SelectGroup(subKey)), tree, table) shouldBe
                1..1
            SelectionMachine.highlightRange(
                reduce(start, SelectionEvent.SelectGroup(tree.key(0))),
                tree,
                table,
            ) shouldBe
                0..1
            SelectionMachine.highlightRange(reduce(start, SelectionEvent.ClickAt(0, "Bolt")), tree, table) shouldBe 0..0
            SelectionMachine.highlightRange(start, tree, table) shouldBe SelectionMachine.NONE
            SelectionMachine.highlightRange(reduce(start, SelectionEvent.SelectGroup("x")), tree, table) shouldBe
                SelectionMachine.NONE
        }
    })
