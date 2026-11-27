@file:OptIn(InternalComposeUiApi::class)

package dev.kstep.desktop.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.PartRef
import dev.kstep.desktop.pick.SelectionMachine
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.TestScenes
import dev.kstep.desktop.pick.TestScenes.triangle
import dev.kstep.desktop.pick.TreeViewLogic
import dev.kstep.desktop.pick.VisibilityCounts
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** Cmd/Ctrl+click, Shift+click, secondary click, Menu key and Escape in the assembly tree (ADR-0092). */
class TreePanelMultiSelectSceneTest :
    StringSpec({
        // 0 Top / 1 A / 2 B / 3 C / 4 D, rows of 24 px below a 28 px header
        val tree =
            AssemblyTree(
                arrayOf("Top", "A", "B", "C", "D"),
                intArrayOf(-1, 0, 0, 0, 0),
                intArrayOf(-1, 0, 1, 2, 3),
            )
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(20.0, 0.0, 0.0),
                triangle(40.0, 0.0, 0.0),
                triangle(60.0, 0.0, 0.0),
                names = listOf("A", "B", "C", "D"),
            )

        fun rowY(row: Int) = 28f + 24f * row + 12f

        class Rig(
            val scene: ImageComposeScene,
        ) {
            val selection = mutableStateOf(SelectionState())
            val requests = mutableListOf<MenuRequest>()

            fun press(
                row: Int,
                button: PointerButton = PointerButton.Primary,
                mods: PointerKeyboardModifiers = PointerKeyboardModifiers(),
            ) {
                val at = Offset(110f, rowY(row))
                scene.sendPointerEvent(PointerEventType.Move, at)
                scene.sendPointerEvent(PointerEventType.Press, at, button = button, keyboardModifiers = mods)
                scene.sendPointerEvent(PointerEventType.Release, at, button = button, keyboardModifiers = mods)
                scene.render()
            }

            fun key(
                key: Key,
                shift: Boolean = false,
            ) {
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
                scene.render()
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
                scene.render()
            }

            val parts get() = selection.value.parts
        }

        fun run(
            os: MenuOs,
            body: (Rig) -> Unit,
        ) {
            val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f))
            val rig = Rig(scene)
            try {
                scene.setContent {
                    AssemblyTreePanel(
                        tree = tree,
                        table = table,
                        view = TreeViewLogic.initial(tree),
                        onView = {},
                        selection = rig.selection,
                        dispatch = { rig.selection.value = SelectionMachine.reduce(rig.selection.value, it) },
                        dark = false,
                        background = Color.White,
                        nodeVisibility = null,
                        counts = VisibilityCounts(0, 0),
                        onCycleNode = {},
                        onCycleSelected = {},
                        os = os,
                        onContext = { rig.requests += it },
                    )
                }
                scene.render()
                body(rig)
            } finally {
                scene.close()
            }
        }

        val ctrl = PointerKeyboardModifiers(isCtrlPressed = true)
        val meta = PointerKeyboardModifiers(isMetaPressed = true)
        val shift = PointerKeyboardModifiers(isShiftPressed = true)

        "Ctrl+click on Windows adds and removes parts".config(timeout = 90.seconds) {
            run(MenuOs.WINDOWS) { r ->
                r.press(1)
                r.press(3, mods = ctrl)
                r.parts shouldBe listOf(PartRef("A", 0), PartRef("C", 0))
                r.press(1, mods = ctrl)
                r.parts shouldBe listOf(PartRef("C", 0))
            }
        }

        "Cmd+click on macOS toggles, Ctrl+click opens the menu".config(timeout = 90.seconds) {
            run(MenuOs.MAC) { r ->
                r.press(1)
                r.press(2, mods = meta)
                r.parts shouldBe listOf(PartRef("A", 0), PartRef("B", 0))
                r.press(4, mods = ctrl)
                r.parts shouldBe listOf(PartRef("D", 0))
                r.requests.size shouldBe 1
                r.requests[0].origin shouldBe MenuOrigin.Tree
            }
        }

        "Shift+click selects the part rows between the anchor and the click".config(timeout = 90.seconds) {
            run(MenuOs.LINUX) { r ->
                r.press(2)
                r.press(4, mods = shift)
                r.parts shouldBe listOf(PartRef("B", 0), PartRef("C", 0), PartRef("D", 0))
                // the anchor stays on B: shifting back up gives the other side
                r.press(1, mods = shift)
                r.parts shouldBe listOf(PartRef("A", 0), PartRef("B", 0))
            }
        }

        "a secondary click selects the row if needed and asks for the menu".config(timeout = 90.seconds) {
            run(MenuOs.LINUX) { r ->
                r.press(1)
                r.press(3, mods = ctrl)
                r.press(3, button = PointerButton.Secondary)
                r.parts shouldBe listOf(PartRef("A", 0), PartRef("C", 0))
                r.press(4, button = PointerButton.Secondary)
                r.parts shouldBe listOf(PartRef("D", 0))
                r.requests.size shouldBe 2
                r.requests.all { it.origin == MenuOrigin.Tree } shouldBe true
            }
        }

        "Shift+F10 and the Menu key open the menu at the selected row, nothing selected stays free".config(
            timeout = 90.seconds,
        ) {
            run(MenuOs.LINUX) { r ->
                r.press(2)
                r.key(Key.F10, shift = true)
                r.key(Key.Menu)
                r.requests.size shouldBe 2
                r.requests[0].y.let { (it > 28f) shouldBe true }
                r.selection.value = SelectionState()
                r.key(Key.Menu)
                r.requests.size shouldBe 2
            }
        }

        "Escape ends a multi-selection and stays free without one".config(timeout = 90.seconds) {
            run(MenuOs.LINUX) { r ->
                r.press(1)
                r.press(2, mods = ctrl)
                r.parts.size shouldBe 2
                r.key(Key.Escape)
                r.parts shouldBe emptyList()
                r.selection.value.selected shouldBe null
                r.selection.value.shouldNotBeNull()
            }
        }
    })
