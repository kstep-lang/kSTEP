package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The multi-selection of ADR-0092: toggle, range, context, limits and the single-selection invariants. */
class SelectionMultiTest :
    StringSpec({
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(20.0, 0.0, 0.0),
                triangle(40.0, 0.0, 0.0),
                triangle(60.0, 0.0, 0.0),
                names = listOf("A", "B", "A", "C"),
            )
        val start = SelectionState()

        fun reduce(
            s: SelectionState,
            vararg events: SelectionEvent,
        ) = events.fold(s) { acc, e -> SelectionMachine.reduce(acc, e) }

        fun click(
            i: Int,
            name: String,
            occ: Int = 0,
        ) = SelectionEvent.ClickAt(i, name, occ)

        fun toggle(
            i: Int,
            name: String,
            occ: Int = 0,
        ) = SelectionEvent.TogglePart(i, name, occ)

        "toggling adds parts in order, the primary stays the first, selected and parts agree" {
            val s = reduce(start, click(0, "A"), toggle(1, "B"), toggle(3, "C"))
            s.parts shouldBe listOf(PartRef("A", 0), PartRef("B", 0), PartRef("C", 0))
            s.selected shouldBe "A"
            s.occurrence shouldBe 0
            s.anchorRef shouldBe PartRef("C", 0)
        }

        "toggling a selected part removes it, the next one becomes the primary" {
            val s = reduce(start, click(0, "A"), toggle(1, "B"), toggle(0, "A"))
            s.parts shouldBe listOf(PartRef("B", 0))
            s.selected shouldBe "B"
            reduce(s, toggle(1, "B")) shouldBe start
        }

        "toggling in an empty selection selects the part, toggling empty space changes nothing" {
            reduce(start, toggle(1, "B")).parts shouldBe listOf(PartRef("B", 0))
            reduce(start, SelectionEvent.TogglePart(null, null)) shouldBe start
        }

        "the same name with another occurrence is another part" {
            val s = reduce(start, click(0, "A"), toggle(2, "A", 1))
            s.parts shouldBe listOf(PartRef("A", 0), PartRef("A", 1))
        }

        "the 1001st part is refused: the very same state and a notice" {
            val refs = (0 until MAX_SELECTION).map { PartRef("P$it", 0) }
            val full = reduce(start, SelectionEvent.SelectParts(refs))
            full.parts.size shouldBe MAX_SELECTION
            val (next, notice) = SelectionMachine.reduceWithNotice(full, SelectionEvent.TogglePart(5, "X", 0))
            (next === full) shouldBe true
            notice shouldBe SelectionNotice.LimitReached
            // removing still works at the limit
            SelectionMachine.reduce(full, SelectionEvent.TogglePart(0, "P0", 0)).parts.size shouldBe MAX_SELECTION - 1
        }

        "SelectParts cuts to the limit and reports the real total" {
            val refs = (0 until MAX_SELECTION + 5).map { PartRef("P$it", 0) }
            val (s, notice) = SelectionMachine.reduceWithNotice(start, SelectionEvent.SelectParts(refs))
            s.parts.size shouldBe MAX_SELECTION
            notice shouldBe SelectionNotice.Truncated(MAX_SELECTION + 5)
            SelectionMachine.reduceWithNotice(start, SelectionEvent.SelectParts(refs.take(3))).second shouldBe null
        }

        "SelectParts drops duplicates and an empty list clears" {
            val s = reduce(start, SelectionEvent.SelectParts(listOf(PartRef("A", 0), PartRef("A", 0), PartRef("B", 0))))
            s.parts shouldBe listOf(PartRef("A", 0), PartRef("B", 0))
            reduce(s, SelectionEvent.SelectParts(emptyList())) shouldBe start
        }

        "a range keeps the anchor, the next range starts from it" {
            val s1 = reduce(start, click(1, "B"))
            val s2 = reduce(s1, SelectionEvent.SelectRange(listOf(PartRef("B", 0), PartRef("A", 1), PartRef("C", 0))))
            s2.parts shouldBe listOf(PartRef("B", 0), PartRef("A", 1), PartRef("C", 0))
            s2.anchorRef shouldBe PartRef("B", 0)
            // a backwards range: primary is the first of the list, the anchor stays where the range started
            val s3 = reduce(s1, SelectionEvent.SelectRange(listOf(PartRef("A", 0), PartRef("B", 0))))
            s3.selected shouldBe "A"
            s3.anchorRef shouldBe PartRef("B", 0)
        }

        "a plain click, Escape and empty space end the whole selection" {
            val multi = reduce(start, click(0, "A"), toggle(1, "B"))
            reduce(multi, click(3, "C")).parts shouldBe listOf(PartRef("C", 0))
            reduce(multi, SelectionEvent.Escape) shouldBe start
            reduce(multi, SelectionEvent.ClickAt(null, null)) shouldBe start
        }

        "a context request inside the selection keeps it, outside it replaces it, on empty space changes nothing" {
            val multi = reduce(start, click(0, "A"), toggle(1, "B"))
            (SelectionMachine.reduce(multi, SelectionEvent.ContextAt(1, "B", 0)) === multi) shouldBe true
            reduce(multi, SelectionEvent.ContextAt(3, "C", 0)).parts shouldBe listOf(PartRef("C", 0))
            (SelectionMachine.reduce(multi, SelectionEvent.ContextAt(null, null)) === multi) shouldBe true
        }

        "a replaced scene filters vanished parts, keeps the order and drops a lost anchor" {
            val multi = reduce(start, click(0, "A"), toggle(1, "B"), toggle(3, "C"))
            val (_, smaller) =
                TestScenes.scene(triangle(0.0, 0.0, 0.0), triangle(20.0, 0.0, 0.0), names = listOf("A", "B"))
            val s = reduce(multi, SelectionEvent.SceneReplaced(smaller))
            s.parts shouldBe listOf(PartRef("A", 0), PartRef("B", 0))
            s.anchor shouldBe null
            val (_, onlyB) = TestScenes.scene(triangle(0.0, 0.0, 0.0), names = listOf("B"))
            val t = reduce(multi, SelectionEvent.SceneReplaced(onlyB))
            t.selected shouldBe "B"
            t.parts shouldBe listOf(PartRef("B", 0))
        }

        "toggling while a group is selected resolves the group to its parts first" {
            val group = SelectionState(selectedGroup = "g")
            val refs = listOf(PartRef("A", 0), PartRef("B", 0), PartRef("A", 1))
            val s = reduce(group, SelectionEvent.TogglePart(3, "C", 0, refs))
            s.selectedGroup shouldBe null
            s.parts shouldBe refs + PartRef("C", 0)
            // toggling a part of the group removes it from the resolved set
            reduce(group, SelectionEvent.TogglePart(1, "B", 0, refs)).parts shouldBe
                listOf(PartRef("A", 0), PartRef("A", 1))
        }

        "a group over the limit restarts with the clicked part and says so" {
            val group = SelectionState(selectedGroup = "g")
            val (s, notice) =
                SelectionMachine.reduceWithNotice(
                    group,
                    SelectionEvent.TogglePart(3, "C", 0, groupRefs = emptyList(), groupTotal = 5000),
                )
            s.parts shouldBe listOf(PartRef("C", 0))
            notice shouldBe SelectionNotice.Truncated(5000)
        }

        "partIndices follows the selection order and tells repeated names apart" {
            val s = reduce(start, click(3, "C"), toggle(2, "A", 1), toggle(0, "A", 0))
            SelectionMachine.partIndices(s, null, table).toList() shouldBe listOf(3, 2, 0)
            SelectionMachine.partIndices(start, null, table).toList() shouldBe emptyList()
        }

        "the mask is null for one part and flags exactly the selected parts for several" {
            SelectionMachine.highlightMask(reduce(start, click(0, "A")), table) shouldBe null
            val mask = SelectionMachine.highlightMask(reduce(start, click(3, "C"), toggle(2, "A", 1)), table)!!
            mask.toList() shouldBe listOf(false, false, true, true)
        }

        "the mask memo rebuilds only when the selection changes, not on a hover" {
            val memo = MaskMemo()
            val s = reduce(start, click(0, "A"), toggle(1, "B"))
            val first = memo.of(s, table)!!
            (memo.of(s.copy(hovered = 2), table) === first) shouldBe true
            (memo.of(reduce(s, toggle(3, "C")), table) === first) shouldBe false
            memo.of(reduce(start, click(0, "A")), table) shouldBe null
        }

        "single selections behave exactly as before" {
            val one = reduce(start, click(0, "A"))
            one shouldBe SelectionState(selected = "A")
            one.parts shouldBe listOf(PartRef("A", 0))
            SelectionMachine.highlightRange(one, null, table) shouldBe 0..0
        }

        "notices have texts that name the limit" {
            SelectionNotices.text(SelectionNotice.LimitReached) shouldBe "Selection limit of 1000 parts reached"
            SelectionNotices.text(SelectionNotice.Truncated(1500)) shouldBe "Selection limited to 1000 parts (of 1500)"
        }
    })
