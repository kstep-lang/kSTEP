package dev.kstep.desktop.pick

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class TreeViewLogicTest :
    StringSpec({
        fun tree(vararg n: Triple<String, Int, Int>) =
            AssemblyTree(
                Array(n.size) { n[it].first },
                IntArray(n.size) { n[it].second },
                IntArray(n.size) { n[it].third },
            )

        // 0 Top
        //   1 A [0]
        //   2 Sub
        //     3 Deep
        //       4 B [1]
        //     5 C [2]
        //   6 D [3]
        val t =
            tree(
                Triple("Top", -1, -1),
                Triple("A", 0, 0),
                Triple("Sub", 0, -1),
                Triple("Deep", 2, -1),
                Triple("B", 3, 1),
                Triple("C", 2, 2),
                Triple("D", 0, 3),
            )

        fun rows(
            v: TreeViewState,
            tree: AssemblyTree = t,
        ) = TreeViewLogic.visibleRows(tree, v).toList()

        "at first sight the root and level one are open, deeper groups are closed" {
            val v = TreeViewLogic.initial(t)
            TreeViewLogic.isExpanded(t, v, 0) shouldBe true
            TreeViewLogic.isExpanded(t, v, 2) shouldBe true
            TreeViewLogic.isExpanded(t, v, 3) shouldBe false
            rows(v) shouldBe listOf(0, 1, 2, 3, 5, 6)
        }

        "a closed group hides its whole subtree, an open one shows it" {
            val v = TreeViewLogic.initial(t)
            rows(TreeViewLogic.toggle(t, v, 2)) shouldBe listOf(0, 1, 2, 6)
            rows(TreeViewLogic.toggle(t, v, 0)) shouldBe listOf(0)
            rows(TreeViewLogic.toggle(t, v, 3)) shouldBe listOf(0, 1, 2, 3, 4, 5, 6)
        }

        "toggling a leaf changes nothing" {
            val v = TreeViewLogic.initial(t)
            TreeViewLogic.toggle(t, v, 1) shouldBe v
        }

        "expandAncestors opens the way to a node" {
            val v = TreeViewLogic.expandAncestors(t, TreeViewLogic.initial(t), 4)
            rows(v) shouldBe listOf(0, 1, 2, 3, 4, 5, 6)
            // already open: same state back
            TreeViewLogic.expandAncestors(t, v, 4) shouldBe v
        }

        fun key(
            v: TreeViewState,
            selected: Int,
            k: TreeKey,
        ): TreeStep = TreeViewLogic.onKey(t, v, TreeViewLogic.visibleRows(t, v), selected, k)

        val start = TreeViewLogic.initial(t)

        "Down and Up move over the visible rows without wrapping" {
            key(start, 0, TreeKey.Down).select shouldBe TreeTarget(1)
            key(start, 3, TreeKey.Down).select shouldBe TreeTarget(5) // 4 is hidden
            key(start, 6, TreeKey.Down).select shouldBe TreeTarget(6)
            key(start, 5, TreeKey.Up).select shouldBe TreeTarget(3)
            key(start, 0, TreeKey.Up).select shouldBe TreeTarget(0)
        }

        "without a selection Down picks the first and Up the last visible row" {
            key(start, -1, TreeKey.Down).select shouldBe TreeTarget(0)
            key(start, -1, TreeKey.Up).select shouldBe TreeTarget(6)
            key(start, -1, TreeKey.Right).select shouldBe null
            key(start, -1, TreeKey.Left).select shouldBe null
            key(start, -1, TreeKey.Enter).view shouldBe start
        }

        "Home and End go to the first and last visible row" {
            key(start, 3, TreeKey.Home).select shouldBe TreeTarget(0)
            key(start, 3, TreeKey.End).select shouldBe TreeTarget(6)
        }

        "Right opens a closed group, then goes to the first child, and does nothing on a leaf" {
            val opened = key(start, 3, TreeKey.Right)
            opened.select shouldBe null
            TreeViewLogic.isExpanded(t, opened.view, 3) shouldBe true
            val child = key(opened.view, 3, TreeKey.Right)
            child.select shouldBe TreeTarget(4)
            key(start, 1, TreeKey.Right).let {
                it.select shouldBe null
                it.view shouldBe start
            }
        }

        "Left closes an open group, else goes to the parent, and does nothing at the root" {
            val closed = key(start, 2, TreeKey.Left)
            TreeViewLogic.isExpanded(t, closed.view, 2) shouldBe false
            key(closed.view, 2, TreeKey.Left).select shouldBe TreeTarget(0)
            key(start, 1, TreeKey.Left).select shouldBe TreeTarget(0)
            val rootClosed = key(start, 0, TreeKey.Left)
            TreeViewLogic.isExpanded(t, rootClosed.view, 0) shouldBe false
            key(rootClosed.view, 0, TreeKey.Left).let {
                it.select shouldBe null
                it.view shouldBe rootClosed.view
            }
        }

        "Enter toggles a group and leaves a leaf alone" {
            TreeViewLogic.isExpanded(t, key(start, 2, TreeKey.Enter).view, 2) shouldBe false
            key(start, 1, TreeKey.Enter).view shouldBe start
        }

        "Escape clears a selection and is a no-op without one" {
            key(start, 3, TreeKey.Escape).clearSelection shouldBe true
            key(start, -1, TreeKey.Escape).clearSelection shouldBe false
        }

        "a selected node that is not visible behaves like no selection" {
            key(start, 4, TreeKey.Down).select shouldBe TreeTarget(0)
        }

        "a re-run keeps open and closed groups of paths that still exist" {
            val closedSub = TreeViewLogic.toggle(t, TreeViewLogic.initial(t), 2)
            val openDeep = TreeViewLogic.toggle(t, closedSub, 3)
            val next = TreeViewLogic.onTreeReplaced(t, t, openDeep)
            next.expanded shouldBe openDeep.expanded
        }

        "a vanished path is dropped and new nodes follow the first-sight rule" {
            val shrunk = tree(Triple("Top", -1, -1), Triple("A", 0, 0), Triple("Sub", 0, -1), Triple("C", 2, 1))
            val grown =
                tree(
                    Triple("Top", -1, -1),
                    Triple("A", 0, 0),
                    Triple("Sub", 0, -1),
                    Triple("Deep", 2, -1),
                    Triple("B", 3, 1),
                    Triple("New", 0, -1),
                    Triple("N1", 5, -1),
                    Triple("N2", 6, 2),
                    Triple("C", 2, 3),
                )
            val withDeep = TreeViewLogic.expandAncestors(t, TreeViewLogic.initial(t), 4)
            val onShrunk = TreeViewLogic.onTreeReplaced(t, shrunk, withDeep)
            onShrunk.known.size shouldBe shrunk.size
            onShrunk.expanded.contains(t.key(3)) shouldBe false
            val onGrown = TreeViewLogic.onTreeReplaced(shrunk, grown, onShrunk)
            // New is at depth 1: open at first sight; N1 at depth 2: closed
            TreeViewLogic.isExpanded(grown, onGrown, 5) shouldBe true
            TreeViewLogic.isExpanded(grown, onGrown, 6) shouldBe false
        }

        "with no earlier tree the first-sight state is used" {
            TreeViewLogic.onTreeReplaced(null, t, TreeViewState(emptySet(), emptySet())) shouldBe
                TreeViewLogic.initial(t)
        }

        "4096 leaves: the visible rows and a key press stay cheap (O(n))".config(timeout = 30.seconds) {
            val n = 4096
            val names = Array(n + 1) { if (it == 0) "Top" else "p$it" }
            val parent = IntArray(n + 1) { if (it == 0) -1 else 0 }
            val partOf = IntArray(n + 1) { if (it == 0) -1 else it - 1 }
            val big = AssemblyTree(names, parent, partOf)
            val v = TreeViewLogic.initial(big)
            TreeViewLogic.visibleRows(big, v).size shouldBe n + 1
            repeat(200) { TreeViewLogic.visibleRows(big, v) }
            val r = TreeViewLogic.visibleRows(big, v)
            TreeViewLogic.onKey(big, v, r, 2000, TreeKey.Down).select shouldBe TreeTarget(2001)
        }
    })
