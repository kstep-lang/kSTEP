@file:OptIn(InternalComposeUiApi::class)

package dev.kstep.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.draft.OfferKind
import dev.kstep.desktop.draft.RecoveryOffer
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.LineEnding
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The banner buttons by keyboard in a headless scene (ADR-0086). */
class EditorBannerKeysSceneTest :
    StringSpec({
        val path = Path.of("/tmp/a.kstep.kts").toAbsolutePath()
        val view =
            EditorView(
                EditorState.Editing(path, "a", ByteArray(32), "a", LineEnding.LF, false, false),
                0,
                0,
                0,
                draftOffer = RecoveryOffer(path, OfferKind.CHANGED, 1, "b"),
            )

        fun key(
            type: KeyEventType,
            key: Key,
        ): KeyEvent = KeyEvent(key = key, type = type)

        class Harness {
            val calls = mutableListOf<String>()
            var editorFocused = false
            var bannerHasFocus = false

            /** The focus as it was when the keys were done, before closing the scene clears every focus. */
            var editorFocusedAtEnd = false
        }

        fun run(body: (SceneClock, Harness, (Int) -> Unit) -> Unit): Harness {
            val h = Harness()
            val scene = ImageComposeScene(width = 600, height = 200)
            val clock = SceneClock(scene)
            try {
                var signal by mutableStateOf(0)
                scene.setContent {
                    val editor = remember { FocusRequester() }
                    Column {
                        EditorBanner(
                            view,
                            dark = false,
                            onReload = { h.calls += "reload" },
                            onKeepMine = { h.calls += "keep" },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { h.bannerHasFocus = it.hasFocus },
                            onRestore = { h.calls += "restore" },
                            onDiscard = { h.calls += "discard" },
                            focusSignal = signal,
                            onLeave = { editor.requestFocus() },
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .focusRequester(editor)
                                .onFocusChanged { h.editorFocused = it.isFocused }
                                .focusable(),
                        )
                    }
                }
                clock.frame()
                body(clock, h) {
                    signal = it
                    clock.renderUntil("banner button focus") { h.bannerHasFocus }
                }
                h.editorFocusedAtEnd = h.editorFocused
            } finally {
                scene.close()
            }
            return h
        }

        fun SceneClock.press(key: Key) {
            scene.sendKeyEvent(key(KeyEventType.KeyDown, key))
            frame()
            scene.sendKeyEvent(key(KeyEventType.KeyUp, key))
            frame()
        }

        "the shortcut puts the focus on Restore, Tab moves to Discard, Enter presses it and the editor gets the focus"
            .config(timeout = 60.seconds) {
                val h =
                    run { clock, h, focusBanner ->
                        focusBanner(1)
                        clock.press(Key.Tab)
                        clock.press(Key.Enter)
                        clock.renderUntil("Discard pressed") { h.calls.isNotEmpty() }
                        clock.renderUntil("editor focus") { h.editorFocused }
                    }
                withClue("calls=${h.calls}") { h.calls shouldBe listOf("discard") }
                h.editorFocusedAtEnd shouldBe true
            }

        "Enter on the first button presses Restore exactly once".config(timeout = 60.seconds) {
            val h =
                run { clock, h, focusBanner ->
                    focusBanner(1)
                    clock.press(Key.Enter)
                    clock.renderUntil("Restore pressed") { h.calls.isNotEmpty() }
                    clock.advance(200) // a second, wrong press would show up here
                }
            withClue("calls=${h.calls}") { h.calls shouldBe listOf("restore") }
        }

        "Escape leaves without a callback".config(timeout = 60.seconds) {
            val h =
                run { clock, h, focusBanner ->
                    focusBanner(1)
                    clock.press(Key.Escape)
                    clock.renderUntil("editor focus") { h.editorFocused }
                }
            withClue("calls=${h.calls}") { h.calls shouldBe emptyList() }
            h.editorFocusedAtEnd shouldBe true
        }
    })
