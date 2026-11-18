package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VisibilityArraysTest :
    StringSpec({
        fun tree() =
            AssemblyTree(
                arrayOf("Top", "Sub", "P0", "P1", "P2"),
                intArrayOf(-1, 0, 1, 1, 0),
                intArrayOf(-1, -1, 0, 1, 2),
            )

        val table =
            TestScenes
                .scene(triangle(0.0, 0.0, 0.0), triangle(20.0, 0.0, 0.0), triangle(40.0, 0.0, 0.0))
                .second
        val scene = VisibilityScene.of(table, tree())

        "part modes and counts follow the state" {
            var s = VisibilityMachine.hide(VisibilityState(), scene, 0..0)
            s = VisibilityMachine.toggleTransparent(s, scene, 2..2)
            val a = VisibilityArrays.build(s, scene, tree())
            a.partMode.toList() shouldBe listOf(PartMode.HIDDEN, PartMode.VISIBLE, PartMode.TRANSPARENT)
            a.counts shouldBe VisibilityCounts(hidden = 1, transparent = 1)
            a.alpha shouldBe 0.25f
        }

        "group states are computed bottom-up, mixed where the parts differ" {
            val t = tree()
            val s = VisibilityMachine.hide(VisibilityState(), scene, 0..0)
            val states = VisibilityArrays.build(s, scene, t).nodeState!!
            states[2] shouldBe PartMode.HIDDEN
            states[3] shouldBe PartMode.VISIBLE
            states[1] shouldBe VisibilityArrays.MIXED
            states[4] shouldBe PartMode.VISIBLE
            states[0] shouldBe VisibilityArrays.MIXED
        }

        "a group whose parts are all hidden is hidden, and so is its parent when all are" {
            val t = tree()
            val s = VisibilityMachine.hide(VisibilityState(), scene, 0..1)
            val states = VisibilityArrays.build(s, scene, t).nodeState!!
            states[1] shouldBe PartMode.HIDDEN
            states[0] shouldBe VisibilityArrays.MIXED
            val all =
                VisibilityArrays
                    .build(
                        VisibilityMachine.hide(VisibilityState(), scene, 0..2),
                        scene,
                        t,
                    ).nodeState!!
            all[0] shouldBe PartMode.HIDDEN
        }

        "without a tree there are no node states" {
            val noTree = VisibilityScene.of(table, null)
            VisibilityArrays.build(VisibilityState(), noTree, null).nodeState shouldBe null
        }

        "the status and header texts leave out zero counts" {
            visibilityStatusText(VisibilityCounts(0, 0)) shouldBe ""
            visibilityStatusText(VisibilityCounts(2, 0)) shouldBe "2 hidden"
            visibilityStatusText(VisibilityCounts(0, 5)) shouldBe "5 transparent"
            visibilityStatusText(VisibilityCounts(2, 5)) shouldBe "2 hidden · 5 transparent"
            visibilityHeaderText(12, VisibilityCounts(2, 5)) shouldBe "12 parts · 2 hidden · 5 transparent"
            visibilityHeaderText(12, VisibilityCounts(0, 0)) shouldBe "12 parts"
        }
    })
