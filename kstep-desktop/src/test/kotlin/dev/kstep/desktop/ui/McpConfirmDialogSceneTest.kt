@file:OptIn(InternalComposeUiApi::class)

package dev.kstep.desktop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import dev.kstep.desktop.mcp.McpConfirmDecision
import dev.kstep.desktop.mcp.McpConfirmRequest
import dev.kstep.desktop.mcp.McpDisplayText
import dev.kstep.desktop.mcp.McpFileAction
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** The confirmation dialog of the file tools (ADR-0098) in a headless scene; time comes from the scene frames. */
class McpConfirmDialogSceneTest :
    StringSpec({
        val request =
            McpConfirmRequest(
                1,
                McpFileAction.SAVE,
                McpDisplayText.forDialog("evil‮file.kts", 80),
                "…/folder",
                "Save: +1/−1 lines vs. file on disk",
                0,
            )

        fun run(body: (SceneClock, MutableList<McpConfirmDecision>) -> Unit): List<McpConfirmDecision> {
            val decisions = mutableListOf<McpConfirmDecision>()
            val scene = ImageComposeScene(width = 700, height = 500)
            val clock = SceneClock(scene)
            try {
                scene.setContent {
                    McpConfirmContent(request, 60_000, { clock.nanos / 1_000_000 }) { decisions.add(it) }
                }
                clock.frame()
                clock.advance(100)
                body(clock, decisions)
            } finally {
                scene.close()
            }
            return decisions
        }

        fun SceneClock.press(
            key: Key,
            shift: Boolean = false,
        ) {
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyDown, isShiftPressed = shift))
            frame()
            scene.sendKeyEvent(KeyEvent(key = key, type = KeyEventType.KeyUp, isShiftPressed = shift))
            frame()
        }

        "the focus starts on Deny: Space presses Deny" {
            val d = run { clock, _ -> clock.press(Key.Spacebar) }
            withClue("decisions=$d") { d shouldBe listOf(McpConfirmDecision.DENY) }
        }

        "Enter and Escape both deny" {
            run { clock, _ -> clock.press(Key.Enter) } shouldBe listOf(McpConfirmDecision.DENY)
            run { clock, _ -> clock.press(Key.Escape) } shouldBe listOf(McpConfirmDecision.DENY)
        }

        "Allow cannot be reached in the first second, and Space on it allows after that".config(timeout = 60.seconds) {
            val early =
                run { clock, _ ->
                    clock.press(Key.Tab, shift = true)
                    clock.press(Key.Spacebar)
                }
            withClue("early=$early") { (McpConfirmDecision.ALLOW in early) shouldBe false }
            val late =
                run { clock, _ ->
                    clock.advance(1_100)
                    clock.press(Key.Tab, shift = true)
                    clock.press(Key.Spacebar)
                }
            withClue("late=$late") { late shouldBe listOf(McpConfirmDecision.ALLOW) }
        }

        "Enter denies even when Allow has the focus" {
            val d =
                run { clock, _ ->
                    clock.advance(1_100)
                    clock.press(Key.Tab, shift = true)
                    clock.press(Key.Enter)
                }
            withClue("d=$d") { (McpConfirmDecision.ALLOW in d) shouldBe false }
            (McpConfirmDecision.DENY in d) shouldBe true
        }

        "the timing and the texts are exact" {
            McpConfirmLogic.allowEnabled(0, 999) shouldBe false
            McpConfirmLogic.allowEnabled(0, 1000) shouldBe true
            McpConfirmLogic.secondsLeft(0, 0, 60_000) shouldBe 60
            McpConfirmLogic.secondsLeft(0, 1, 60_000) shouldBe 60
            McpConfirmLogic.secondsLeft(0, 59_001, 60_000) shouldBe 1
            McpConfirmLogic.secondsLeft(0, 70_000, 60_000) shouldBe 0
            request.fileName shouldBe "evil\\u{202E}file.kts"
        }
    })
