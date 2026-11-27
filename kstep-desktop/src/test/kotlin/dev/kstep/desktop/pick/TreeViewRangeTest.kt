package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Shift+click in the tree: only the part rows that are visible between the two rows (ADR-0092). */
class TreeViewRangeTest :
    StringSpec({
        // 0 Top / 1 A [0] / 2 Sub / 3 Deep / 4 B [1] / 5 C [2] / 6 D [3]
        val t =
            AssemblyTree(
                arrayOf("Top", "A", "Sub", "Deep", "B", "C", "D"),
                intArrayOf(-1, 0, 0, 2, 3, 2, 0),
                intArrayOf(-1, 0, -1, -1, 1, 2, 3),
            )
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(20.0, 0.0, 0.0),
                triangle(40.0, 0.0, 0.0),
                triangle(60.0, 0.0, 0.0),
                names = listOf("A", "B", "C", "D"),
            )
        val open = TreeViewLogic.setExpanded(t, TreeViewLogic.initial(t), 3, true)
        val closedDeep = TreeViewLogic.initial(t)

        fun names(r: TreeViewLogic.RangeRefs) = r.refs.map { it.name }

        "the range holds part rows only, in tree order, in either direction" {
            names(TreeViewLogic.rangeRefs(t, open, table, 1, 6)) shouldBe listOf("A", "B", "C", "D")
            names(TreeViewLogic.rangeRefs(t, open, table, 6, 1)) shouldBe listOf("A", "B", "C", "D")
        }

        "nothing from a closed group is taken" {
            names(TreeViewLogic.rangeRefs(t, closedDeep, table, 1, 6)) shouldBe listOf("A", "C", "D")
        }

        "without a usable anchor it is the clicked row alone; a click row that is not shown gives nothing" {
            names(TreeViewLogic.rangeRefs(t, open, table, -1, 5)) shouldBe listOf("C")
            names(TreeViewLogic.rangeRefs(t, closedDeep, table, -1, 4)) shouldBe emptyList()
        }

        "a group row as the click row adds no part of its own" {
            names(TreeViewLogic.rangeRefs(t, open, table, 1, 2)) shouldBe listOf("A")
        }

        "occurrences of repeated names are counted for the whole scene" {
            val (_, dup) =
                TestScenes.scene(
                    triangle(0.0, 0.0, 0.0),
                    triangle(20.0, 0.0, 0.0),
                    triangle(40.0, 0.0, 0.0),
                    triangle(60.0, 0.0, 0.0),
                    names = listOf("X", "X", "X", "D"),
                )
            TreeViewLogic.rangeRefs(t, open, dup, 4, 5).refs shouldBe listOf(PartRef("X", 1), PartRef("X", 2))
        }

        "a very long range keeps at most the limit plus one and counts the real total" {
            val n = MAX_SELECTION + 50
            val bigTable = RawTables.table(n)
            val big = RawTables.flatTree(bigTable)
            val r = TreeViewLogic.rangeRefs(big, TreeViewLogic.initial(big), bigTable, 1, n)
            r.total shouldBe n
            r.refs.size shouldBe MAX_SELECTION + 1
        }
    })
