@file:OptIn(InternalComposeUiApi::class)

package dev.kstep.desktop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi

/**
 * A scene with its own frame clock, so a test waits for an effect by frames instead of guessing a fixed number of
 * renders (ADR-0090: a slow CI runner finished the focus effect after the keys were already sent) and time-driven
 * code (debounces) is driven by frame time, never by the wall clock.
 */
internal class SceneClock(
    val scene: ImageComposeScene,
) {
    var nanos = 0L
        private set

    fun frame() {
        nanos += FRAME_NANOS
        scene.render(nanos)
    }

    /** Renders frames until [cond] holds; fails loudly after [maxFrames] (nothing is ever skipped silently). */
    fun renderUntil(
        what: String,
        maxFrames: Int = 120,
        cond: () -> Boolean,
    ) {
        var n = 0
        while (!cond()) {
            if (n++ >= maxFrames) throw AssertionError("$what never happened after $maxFrames frames")
            frame()
        }
    }

    /** Renders for [millis] of scene time. */
    fun advance(millis: Long) {
        val end = nanos + millis * 1_000_000L
        while (nanos < end) frame()
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
    }
}
