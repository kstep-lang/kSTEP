package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PartContextMenuTest :
    StringSpec({
        // Top / Sub / (A, B) / C
        val tree =
            AssemblyTree(
                arrayOf("Top", "Sub", "A", "B", "C"),
                intArrayOf(-1, 0, 1, 1, 0),
                intArrayOf(-1, -1, 0, 1, 2),
            )
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(20.0, 0.0, 0.0),
                triangle(40.0, 0.0, 0.0),
                names = listOf("A", "B", "C"),
            )
        val scene = VisibilityScene.of(table, tree)
        val none = VisibilityState()
        val start = SelectionState()

        fun reduce(vararg e: SelectionEvent) = e.fold(start) { s, ev -> SelectionMachine.reduce(s, ev) }

        fun build(
            sel: SelectionState,
            vis: VisibilityState = none,
            reason: String? = null,
            over: Boolean = true,
        ) = PartContextMenu.build(sel, table, tree, vis, scene, reason, over)

        fun MenuModel.entry(a: MenuAction) = entries.single { it.action == a }

        val one = reduce(SelectionEvent.ClickAt(0, "A"))
        val two = reduce(SelectionEvent.ClickAt(0, "A"), SelectionEvent.TogglePart(1, "B"))
        val group = reduce(SelectionEvent.SelectGroup(tree.key(1)))

        "a single part: header is its name, everything possible is enabled, Show All and Select Children are not" {
            val m = build(one)!!
            m.header shouldBe "A"
            m.entry(MenuAction.ShowInScript).enabled shouldBe true
            m.entry(MenuAction.Hide).enabled shouldBe true
            m.entry(MenuAction.Isolate).enabled shouldBe true
            m.entry(MenuAction.Isolate).label shouldBe "Isolate (others transparent)"
            m.entry(MenuAction.CopyName).label shouldBe "Copy Name"
            m.entry(MenuAction.ShowAll).enabled shouldBe false
            m.entry(MenuAction.ShowAll).reason shouldBe PartContextMenu.REASON_NOTHING_TO_SHOW
            m.entry(MenuAction.SelectChildren).reason shouldBe PartContextMenu.REASON_NOT_A_GROUP
        }

        "Show in Script: several parts and the host's reason disable it, each with its reason" {
            build(two)!!.entry(MenuAction.ShowInScript).reason shouldBe
                "Show in Script needs a single part (2 selected)"
            val e = build(one, reason = "No script for this model")!!.entry(MenuAction.ShowInScript)
            e.enabled shouldBe false
            e.reason shouldBe "No script for this model"
        }

        "Hide is disabled with a reason once everything selected is hidden" {
            val hidden = VisibilityMachine.hideParts(none, scene, intArrayOf(0))
            val m = build(one, hidden)!!
            m.entry(MenuAction.Hide).enabled shouldBe false
            m.entry(MenuAction.Hide).reason shouldBe PartContextMenu.REASON_ALREADY_HIDDEN
            m.entry(MenuAction.ShowAll).enabled shouldBe true
            // one of two still visible: Hide works
            build(two, hidden)!!.entry(MenuAction.Hide).enabled shouldBe true
        }

        "Isolate is disabled when the selection already is the only thing shown" {
            val isolated = VisibilityMachine.isolateParts(none, scene, intArrayOf(0))
            val e = build(one, isolated)!!.entry(MenuAction.Isolate)
            e.enabled shouldBe false
            e.reason shouldBe PartContextMenu.REASON_ALREADY_ISOLATED
        }

        "several parts: header counts, Copy Names in the plural" {
            val m = build(two)!!
            m.header shouldBe "2 parts"
            m.entry(MenuAction.CopyName).label shouldBe "Copy Names"
        }

        "a group: header is its name, Select Children works, Show in Script follows the host" {
            val m = build(group)!!
            m.header shouldBe "Sub"
            m.entry(MenuAction.SelectChildren).enabled shouldBe true
            m.entry(MenuAction.ShowInScript).enabled shouldBe true
            m.entry(MenuAction.Hide).enabled shouldBe true
        }

        "a group over the limit explains why Select Children is off" {
            val bigTable = RawTables.table(MAX_SELECTION + 1)
            val big = RawTables.flatTree(bigTable)
            val bigScene = VisibilityScene.of(bigTable, big)
            val sel = SelectionState(selectedGroup = big.key(0))
            val m = PartContextMenu.build(sel, bigTable, big, none, bigScene, null, true)!!
            m.entry(MenuAction.SelectChildren).reason shouldBe "Group has 1001 parts, limit is 1000"
        }

        "empty space: only Show All and only when it would change something, else no menu" {
            build(one, over = false) shouldBe null
            build(start) shouldBe null
            val hidden = VisibilityMachine.hideParts(none, scene, intArrayOf(2))
            val m = build(one, hidden, over = false)!!
            m.header shouldBe null
            m.entries.map { it.action } shouldBe listOf(MenuAction.ShowAll)
        }

        "the header of a hostile name is made visible and clipped" {
            val t = RawTables.table(listOf("evil\u202Eexe" + "x".repeat(100)))
            val tr = RawTables.flatTree(t)
            val sc = VisibilityScene.of(t, tr)
            val sel = SelectionState(selected = t.parts[0].name)
            val header = PartContextMenu.build(sel, t, tr, none, sc, null, true)!!.header!!
            header.contains('\u202E') shouldBe false
            header.startsWith("evil‹U+202E›exe") shouldBe true
            header.endsWith("…") shouldBe true
        }

        "keys: Down and Up wrap and also land on disabled entries" {
            val m = build(one)!!
            m.focused shouldBe 0
            var cur = m
            val seen = ArrayList<MenuAction>()
            repeat(m.entries.size) {
                cur = (PartContextMenu.navigate(cur, MenuKey.Down) as MenuStep.Moved).model
                seen += cur.focusedEntry!!.action
            }
            seen.last() shouldBe m.entries[0].action
            seen.contains(MenuAction.ShowAll) shouldBe true
            val up = (PartContextMenu.navigate(m, MenuKey.Up) as MenuStep.Moved).model
            up.focused shouldBe m.entries.size - 1
            (PartContextMenu.navigate(m, MenuKey.End) as MenuStep.Moved).model.focused shouldBe m.entries.size - 1
            (PartContextMenu.navigate(up, MenuKey.Home) as MenuStep.Moved).model.focused shouldBe 0
        }

        "keys: Enter activates an enabled entry, does nothing on a disabled one, Escape closes" {
            val m = build(one)!!
            PartContextMenu.navigate(m, MenuKey.Enter) shouldBe MenuStep.Activate(MenuAction.ShowInScript)
            val onShowAll = m.copy(focused = m.entries.indexOfFirst { it.action == MenuAction.ShowAll })
            PartContextMenu.navigate(onShowAll, MenuKey.Enter) shouldBe MenuStep.NoOp
            PartContextMenu.navigate(m, MenuKey.Escape) shouldBe MenuStep.Close
        }

        "the first enabled entry has the focus when Show in Script is not possible" {
            build(two)!!.focused shouldBe 1
        }
    })
