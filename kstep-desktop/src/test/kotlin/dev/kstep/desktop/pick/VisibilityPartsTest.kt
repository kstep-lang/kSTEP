package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Hide and isolate for an arbitrary set of parts (ADR-0092) give what the range versions give. */
class VisibilityPartsTest :
    StringSpec({
        val tree =
            AssemblyTree(
                arrayOf("Top", "P0", "P1", "P2", "P3"),
                intArrayOf(-1, 0, 0, 0, 0),
                intArrayOf(-1, 0, 1, 2, 3),
            )
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(20.0, 0.0, 0.0),
                triangle(40.0, 0.0, 0.0),
                triangle(60.0, 0.0, 0.0),
            )
        val scene = VisibilityScene.of(table, tree)
        val none = VisibilityState()

        fun modes(s: VisibilityState) = (0..3).map { VisibilityMachine.effective(s, scene, it) }

        "a contiguous array equals the range" {
            modes(VisibilityMachine.hideParts(none, scene, intArrayOf(1, 2))) shouldBe
                modes(VisibilityMachine.hide(none, scene, 1..2))
            modes(VisibilityMachine.isolateParts(none, scene, intArrayOf(1, 2))) shouldBe
                modes(VisibilityMachine.isolate(none, scene, 1..2))
        }

        "hiding parts that are not adjacent hides exactly those" {
            modes(VisibilityMachine.hideParts(none, scene, intArrayOf(0, 3))) shouldBe
                listOf(Visibility.Hidden, Visibility.Visible, Visibility.Visible, Visibility.Hidden)
        }

        "an operation that changes nothing returns the very same state" {
            val hidden = VisibilityMachine.hideParts(none, scene, intArrayOf(0, 3))
            (VisibilityMachine.hideParts(hidden, scene, intArrayOf(3, 0)) === hidden) shouldBe true
            (VisibilityMachine.hideParts(none, scene, IntArray(0)) === none) shouldBe true
            (VisibilityMachine.hideParts(none, scene, intArrayOf(-1, 99)) === none) shouldBe true
            (VisibilityMachine.isolateParts(none, scene, IntArray(0)) === none) shouldBe true
            (VisibilityMachine.isolateParts(none, scene, intArrayOf(7)) === none) shouldBe true
            val isolated = VisibilityMachine.isolateParts(none, scene, intArrayOf(1, 2))
            (VisibilityMachine.isolateParts(isolated, scene, intArrayOf(1, 2)) === isolated) shouldBe true
        }

        "isolating keeps already hidden parts hidden and makes the rest transparent" {
            val hidden = VisibilityMachine.hideParts(none, scene, intArrayOf(3))
            modes(VisibilityMachine.isolateParts(hidden, scene, intArrayOf(0, 2))) shouldBe
                listOf(Visibility.Visible, Visibility.Transparent, Visibility.Visible, Visibility.Hidden)
        }

        "anyNotVisible knows whether Show All would do something" {
            VisibilityMachine.anyNotVisible(none, scene) shouldBe false
            VisibilityMachine.anyNotVisible(VisibilityMachine.hideParts(none, scene, intArrayOf(1)), scene) shouldBe
                true
            val foreign = VisibilityState(mapOf("gone" to Visibility.Hidden))
            VisibilityMachine.anyNotVisible(foreign, scene) shouldBe false
        }

        "showParts makes the parts visible again and is the same instance when nothing changes" {
            val hidden = VisibilityMachine.hideParts(none, scene, intArrayOf(0, 1, 2))
            val shown = VisibilityMachine.showParts(hidden, scene, intArrayOf(1))
            modes(shown) shouldBe
                listOf(Visibility.Hidden, Visibility.Visible, Visibility.Hidden, Visibility.Visible)
            (VisibilityMachine.showParts(shown, scene, intArrayOf(1, 3)) === shown) shouldBe true
            (VisibilityMachine.showParts(none, scene, intArrayOf(99, -1)) === none) shouldBe true
        }

        "toggleTransparentParts makes all transparent, or visible again when all already are" {
            val t = VisibilityMachine.toggleTransparentParts(none, scene, intArrayOf(0, 1))
            modes(t) shouldBe
                listOf(Visibility.Transparent, Visibility.Transparent, Visibility.Visible, Visibility.Visible)
            val mixed = VisibilityMachine.toggleTransparentParts(t, scene, intArrayOf(1, 2))
            modes(mixed)[2] shouldBe Visibility.Transparent
            modes(VisibilityMachine.toggleTransparentParts(mixed, scene, intArrayOf(1, 2)))[1] shouldBe
                Visibility.Visible
            (VisibilityMachine.toggleTransparentParts(none, scene, intArrayOf(77)) === none) shouldBe true
        }
    })
