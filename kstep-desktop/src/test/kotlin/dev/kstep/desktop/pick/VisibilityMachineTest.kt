package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class VisibilityMachineTest :
    StringSpec({
        // Top / Sub / (P0, P1) / P2: parts 0,1 under Sub, part 2 directly under Top
        fun tree() =
            AssemblyTree(
                arrayOf("Top", "Sub", "P0", "P1", "P2"),
                intArrayOf(-1, 0, 1, 1, 0),
                intArrayOf(-1, -1, 0, 1, 2),
            )

        fun table(names: List<String> = listOf("P0", "P1", "P2")): PartTable =
            TestScenes
                .scene(
                    triangle(0.0, 0.0, 0.0),
                    triangle(20.0, 0.0, 0.0),
                    triangle(40.0, 0.0, 0.0),
                    names = names,
                ).second

        val scene = VisibilityScene.of(table(), tree())
        val none = VisibilityState()

        "the default state shows everything at 25 percent" {
            for (i in 0..2) VisibilityMachine.effective(none, scene, i) shouldBe Visibility.Visible
            none.alphaStep shouldBe 5
            none.alpha shouldBe 0.25f
            VisibilityMachine.rangeState(none, scene, 0..2) shouldBe TreeVisibility.Visible
        }

        "a group range is mixed when its parts differ, otherwise uniform" {
            val s = VisibilityMachine.hide(none, scene, 0..0)
            VisibilityMachine.rangeState(s, scene, 0..1) shouldBe TreeVisibility.Mixed
            VisibilityMachine.rangeState(s, scene, 0..0) shouldBe TreeVisibility.Hidden
            VisibilityMachine.rangeState(s, scene, 1..2) shouldBe TreeVisibility.Visible
            VisibilityMachine.rangeState(VisibilityMachine.hide(s, scene, 1..1), scene, 0..1) shouldBe
                TreeVisibility.Hidden
        }

        "cycle goes visible, transparent, hidden, visible; mixed becomes visible" {
            val t = VisibilityMachine.cycle(none, scene, 0..1)
            VisibilityMachine.rangeState(t, scene, 0..1) shouldBe TreeVisibility.Transparent
            val h = VisibilityMachine.cycle(t, scene, 0..1)
            VisibilityMachine.rangeState(h, scene, 0..1) shouldBe TreeVisibility.Hidden
            val v = VisibilityMachine.cycle(h, scene, 0..1)
            VisibilityMachine.rangeState(v, scene, 0..1) shouldBe TreeVisibility.Visible
            v.leafStates.isEmpty() shouldBe true
            val mixed = VisibilityMachine.hide(none, scene, 0..0)
            VisibilityMachine.rangeState(VisibilityMachine.cycle(mixed, scene, 0..1), scene, 0..1) shouldBe
                TreeVisibility.Visible
        }

        "T toggles transparent from hidden, mixed and transparent" {
            val hidden = VisibilityMachine.hide(none, scene, 0..1)
            VisibilityMachine.rangeState(VisibilityMachine.toggleTransparent(hidden, scene, 0..1), scene, 0..1) shouldBe
                TreeVisibility.Transparent
            val mixed = VisibilityMachine.hide(none, scene, 0..0)
            VisibilityMachine.rangeState(VisibilityMachine.toggleTransparent(mixed, scene, 0..1), scene, 0..1) shouldBe
                TreeVisibility.Transparent
            val transparent = VisibilityMachine.toggleTransparent(none, scene, 0..1)
            VisibilityMachine.toggleTransparent(transparent, scene, 0..1).leafStates.isEmpty() shouldBe true
        }

        "V toggles hidden from transparent, mixed and hidden" {
            val transparent = VisibilityMachine.toggleTransparent(none, scene, 0..1)
            VisibilityMachine.rangeState(VisibilityMachine.toggleHidden(transparent, scene, 0..1), scene, 0..1) shouldBe
                TreeVisibility.Hidden
            val mixed = VisibilityMachine.toggleTransparent(none, scene, 0..0)
            VisibilityMachine.rangeState(VisibilityMachine.toggleHidden(mixed, scene, 0..1), scene, 0..1) shouldBe
                TreeVisibility.Hidden
            val hidden = VisibilityMachine.toggleHidden(none, scene, 0..1)
            VisibilityMachine.toggleHidden(hidden, scene, 0..1).leafStates.isEmpty() shouldBe true
        }

        "isolate shows the range, makes the rest transparent and keeps hidden parts hidden" {
            val start = VisibilityMachine.hide(none, scene, 2..2)
            val s = VisibilityMachine.isolate(start, scene, 0..0)
            VisibilityMachine.effective(s, scene, 0) shouldBe Visibility.Visible
            VisibilityMachine.effective(s, scene, 1) shouldBe Visibility.Transparent
            VisibilityMachine.effective(s, scene, 2) shouldBe Visibility.Hidden
        }

        "isolate shows a range that was hidden before" {
            val start = VisibilityMachine.hide(none, scene, 0..0)
            val s = VisibilityMachine.isolate(start, scene, 0..0)
            VisibilityMachine.effective(s, scene, 0) shouldBe Visibility.Visible
            VisibilityMachine.effective(s, scene, 1) shouldBe Visibility.Transparent
        }

        "hide leaves the selection alone: it is a separate state" {
            val selection = SelectionState(selected = "P0")
            VisibilityMachine.hide(none, scene, 0..0)
            selection shouldBe SelectionState(selected = "P0")
        }

        "show all clears the hiding and keeps the opacity" {
            val s = VisibilityMachine.withAlpha(VisibilityMachine.hide(none, scene, 0..2), 0.4f)
            val all = VisibilityMachine.showAll(s)
            all.leafStates.isEmpty() shouldBe true
            all.alphaStep shouldBe 8
        }

        "opacity is clamped, rounded to 5 percent steps, and survives odd input" {
            VisibilityMachine.withAlpha(none, 0.0f).alphaStep shouldBe 1
            VisibilityMachine.withAlpha(none, 1.0f).alphaStep shouldBe 12
            VisibilityMachine.withAlpha(none, Float.NaN) shouldBe none
            VisibilityMachine.withAlpha(none, Float.POSITIVE_INFINITY) shouldBe none
            VisibilityMachine.withAlpha(none, Float.NEGATIVE_INFINITY).alphaStep shouldBe 5
            VisibilityMachine.withAlpha(none, 0.27f).alphaStep shouldBe 5
            VisibilityMachine.withAlpha(none, 0.6f).alphaStep shouldBe 12
            VisibilityMachine.withAlpha(none, 0.3f).alpha shouldBe 0.3f
        }

        "a re-run keeps known keys, drops vanished ones and keeps the opacity" {
            val s = VisibilityMachine.withAlpha(VisibilityMachine.hide(none, scene, 0..2), 0.4f)
            val smaller =
                VisibilityScene.of(
                    TestScenes
                        .scene(
                            triangle(0.0, 0.0, 0.0),
                            triangle(20.0, 0.0, 0.0),
                            names = listOf("P0", "P1"),
                        ).second,
                    AssemblyTree(arrayOf("Top", "P0", "P1"), intArrayOf(-1, 0, 0), intArrayOf(-1, 0, 1)),
                )
            // a different tree gives different keys: nothing of the old tree survives, the opacity does
            val dropped = VisibilityMachine.onRerun(s, smaller)
            dropped.leafStates.isEmpty() shouldBe true
            dropped.alphaStep shouldBe 8
            val same = VisibilityMachine.onRerun(s, scene)
            (same === s) shouldBe true
        }

        "without a valid scene a re-run discards nothing" {
            val s = VisibilityMachine.hide(none, scene, 0..2)
            (VisibilityMachine.onRerun(s, null) === s) shouldBe true
        }

        "same-named parts are told apart by their occurrence" {
            val t = table(listOf("Bolt", "Bolt", "Nut"))
            val noTree = VisibilityScene.of(t, null)
            noTree.partKey[0] shouldNotBe noTree.partKey[1]
            val s = VisibilityMachine.hide(none, noTree, 1..1)
            VisibilityMachine.effective(s, noTree, 0) shouldBe Visibility.Visible
            VisibilityMachine.effective(s, noTree, 1) shouldBe Visibility.Hidden
            // a re-run that keeps the scene keeps the second bolt hidden
            val again = VisibilityMachine.onRerun(s, VisibilityScene.of(t, null))
            VisibilityMachine.effective(again, VisibilityScene.of(t, null), 1) shouldBe Visibility.Hidden
        }

        "counts only count parts of the scene" {
            val s = VisibilityMachine.toggleTransparent(VisibilityMachine.hide(none, scene, 0..0), scene, 1..2)
            VisibilityMachine.counts(s, scene) shouldBe VisibilityCounts(hidden = 1, transparent = 2)
            val foreign = VisibilityState(mapOf("zz" to Visibility.Hidden))
            VisibilityMachine.counts(foreign, scene) shouldBe VisibilityCounts(0, 0)
        }

        "operations that change nothing return the same instance" {
            val hidden = VisibilityMachine.hide(none, scene, 0..0)
            (VisibilityMachine.hide(hidden, scene, 0..0) === hidden) shouldBe true
            (VisibilityMachine.showAll(none) === none) shouldBe true
            (VisibilityMachine.withAlpha(none, 0.25f) === none) shouldBe true
            (VisibilityMachine.hide(none, scene, SelectionMachine.NONE) === none) shouldBe true
            (VisibilityMachine.cycle(none, scene, SelectionMachine.NONE) === none) shouldBe true
            (VisibilityMachine.toggleTransparent(none, scene, SelectionMachine.NONE) === none) shouldBe true
            (VisibilityMachine.isolate(none, scene, SelectionMachine.NONE) === none) shouldBe true
        }

        "ranges outside the scene are clipped or ignored" {
            (VisibilityMachine.hide(none, scene, 10..20) === none) shouldBe true
            val s = VisibilityMachine.hide(none, scene, 1..50)
            VisibilityMachine.effective(s, scene, 1) shouldBe Visibility.Hidden
            VisibilityMachine.effective(s, scene, 2) shouldBe Visibility.Hidden
            VisibilityMachine.effective(none, scene, 99) shouldBe Visibility.Visible
            VisibilityMachine.effective(none, scene, -1) shouldBe Visibility.Visible
            VisibilityMachine.rangeState(none, scene, 10..20) shouldBe TreeVisibility.Visible
        }

        "a part the tree does not cover falls back to the name key" {
            val short = AssemblyTree(arrayOf("Top", "P0"), intArrayOf(-1, 0), intArrayOf(-1, 0))
            val sc = VisibilityScene.of(table(), short)
            sc.partKey[1].startsWith("n:") shouldBe true
            sc.partKey[2].startsWith("n:") shouldBe true
            sc.partKey[0].startsWith("n:") shouldBe false
        }
    })
