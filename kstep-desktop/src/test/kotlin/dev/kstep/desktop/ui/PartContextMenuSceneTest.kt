@file:OptIn(InternalComposeUiApi::class)

package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.Density
import dev.kstep.desktop.pick.MenuAction
import dev.kstep.desktop.pick.MenuEntry
import dev.kstep.desktop.pick.MenuKey
import dev.kstep.desktop.pick.MenuModel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** Headless checks of the context menu popup: keyboard operation, disabled entries, Escape, focus return (ADR-0092). */
class PartContextMenuSceneTest :
    StringSpec({
        val model =
            MenuModel(
                "Bolt",
                listOf(
                    MenuEntry(MenuAction.ShowInScript, "Show in Script", true),
                    MenuEntry(MenuAction.Hide, "Hide", true),
                    MenuEntry(MenuAction.Isolate, "Isolate (others transparent)", true),
                    MenuEntry(MenuAction.ShowAll, "Show All", false, "Nothing is hidden or transparent"),
                    MenuEntry(MenuAction.CopyName, "Copy Name", true),
                ),
            )

        class Harness {
            val actions = mutableListOf<MenuAction>()
            var dismissed = 0
            var focusRestored = 0
        }

        fun run(body: (SceneClock, Harness) -> Unit): Harness {
            val h = Harness()
            val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f))
            val clock = SceneClock(scene)
            try {
                var open by mutableStateOf(true)
                scene.setContent {
                    Box(Modifier.fillMaxSize()) {
                        if (open) {
                            PartContextMenuPopup(
                                initial = model,
                                onAction = { h.actions += it },
                                onDismiss = {
                                    h.dismissed++
                                    open = false
                                },
                                restoreFocus = { h.focusRestored++ },
                            )
                        }
                    }
                }
                clock.advance(400)
                body(clock, h)
            } finally {
                scene.close()
            }
            return h
        }

        fun SceneClock.press(key: Key) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown))
            frame()
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp))
            frame()
        }

        "Down then Enter runs the second entry and closes the menu, returning the focus".config(timeout = 60.seconds) {
            val h =
                run { clock, _ ->
                    clock.press(Key.DirectionDown)
                    clock.press(Key.Enter)
                }
            h.actions shouldBe listOf(MenuAction.Hide)
            h.dismissed shouldBe 1
            h.focusRestored shouldBe 1
        }

        "a disabled entry can be reached with the arrow keys but Enter does nothing there".config(
            timeout = 60.seconds,
        ) {
            val h =
                run { clock, _ ->
                    repeat(3) { clock.press(Key.DirectionDown) }
                    clock.press(Key.Enter)
                }
            h.actions shouldBe emptyList()
            h.dismissed shouldBe 0
        }

        "Up from the first entry wraps to the last".config(timeout = 60.seconds) {
            val h =
                run { clock, _ ->
                    clock.press(Key.DirectionUp)
                    clock.press(Key.Enter)
                }
            h.actions shouldBe listOf(MenuAction.CopyName)
        }

        "Escape closes the menu without an action and returns the focus".config(timeout = 60.seconds) {
            val h = run { clock, _ -> clock.press(Key.Escape) }
            h.actions shouldBe emptyList()
            (h.dismissed >= 1) shouldBe true
            (h.focusRestored >= 1) shouldBe true
        }

        "only the menu keys are understood".config(timeout = 60.seconds) {
            menuKeyOf(KeyEvent(Key.DirectionDown, KeyEventType.KeyDown)) shouldBe MenuKey.Down
            menuKeyOf(KeyEvent(Key.DirectionDown, KeyEventType.KeyUp)) shouldBe null
            menuKeyOf(KeyEvent(Key.A, KeyEventType.KeyDown)) shouldBe null
            menuKeyOf(KeyEvent(Key.NumPadEnter, KeyEventType.KeyDown)) shouldBe MenuKey.Enter
        }
    })
