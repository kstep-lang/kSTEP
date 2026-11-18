package dev.kstep.desktop.ui

import androidx.compose.ui.input.key.Key
import dev.kstep.desktop.pick.PartMode
import dev.kstep.viewer.ui.CanvasVisibility
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class VisibilityShortcutsTest :
    StringSpec({
        fun action(
            key: Key,
            shift: Boolean = false,
            command: Boolean = false,
            alt: Boolean = false,
            editor: Boolean = false,
            tree: Boolean = false,
        ) = visibilityAction(key, shift, command, alt, editor, tree)

        "T, V, I and Shift+V map to their actions" {
            action(Key.T) shouldBe VisibilityAction.ToggleTransparent
            action(Key.V) shouldBe VisibilityAction.ToggleHidden
            action(Key.I) shouldBe VisibilityAction.Isolate
            action(Key.V, shift = true) shouldBe VisibilityAction.ShowAll
        }

        "Shift+T and Shift+I are not assigned" {
            action(Key.T, shift = true).shouldBeNull()
            action(Key.I, shift = true).shouldBeNull()
        }

        "Spacebar cycles only inside the tree" {
            action(Key.Spacebar, tree = true) shouldBe VisibilityAction.Cycle
            action(Key.Spacebar).shouldBeNull()
            action(Key.Spacebar, shift = true, tree = true).shouldBeNull()
        }

        "H stays the camera reset and is no visibility key" {
            action(Key.H).shouldBeNull()
            action(Key.H, tree = true).shouldBeNull()
        }

        "Meta, Ctrl, Alt and the script editor keep their keys" {
            for (key in listOf(Key.T, Key.V, Key.I)) {
                action(key, command = true).shouldBeNull()
                action(key, alt = true).shouldBeNull()
                action(key, editor = true).shouldBeNull()
            }
            action(Key.Spacebar, editor = true, tree = true).shouldBeNull()
        }

        "the part mode codes equal the canvas codes" {
            PartMode.VISIBLE.toInt() shouldBe CanvasVisibility.VISIBLE
            PartMode.TRANSPARENT.toInt() shouldBe CanvasVisibility.TRANSPARENT
            PartMode.HIDDEN.toInt() shouldBe CanvasVisibility.HIDDEN
        }

        "a press lands in the eye column first, then on the arrow, otherwise on the row" {
            // eye 2..22, arrow 40..56 (a group indented by 10 px)
            pressZone(10f, 2f, 22f, 40f, 56f, isGroup = true) shouldBe PressZone.Eye
            pressZone(10f, 2f, 22f, 40f, 56f, isGroup = false) shouldBe PressZone.Eye
            pressZone(1f, 2f, 22f, 40f, 56f, isGroup = true) shouldBe PressZone.Row // the accent bar
            pressZone(22f, 2f, 22f, 40f, 56f, isGroup = true) shouldBe PressZone.Row
            pressZone(45f, 2f, 22f, 40f, 56f, isGroup = true) shouldBe PressZone.Arrow
            pressZone(45f, 2f, 22f, 40f, 56f, isGroup = false) shouldBe PressZone.Row
            pressZone(56f, 2f, 22f, 40f, 56f, isGroup = true) shouldBe PressZone.Row
            pressZone(100f, 2f, 22f, 40f, 56f, isGroup = true) shouldBe PressZone.Row
        }
    })
