package dev.kstep.desktop.ui

import androidx.compose.ui.input.key.Key

/** What a visibility key does (ADR-0055). */
internal enum class VisibilityAction { ToggleTransparent, ToggleHidden, Isolate, ShowAll, Cycle }

/**
 * Maps a key to a visibility action, or `null` when the key is not ours: with Meta, Ctrl or Alt held, while the script
 * editor has the focus, and for Shift+T and Shift+I. Spacebar (cycle) only works inside the assembly tree. `H` stays the
 * camera reset, so "hide" is `V`.
 */
internal fun visibilityAction(
    key: Key,
    shift: Boolean,
    command: Boolean,
    alt: Boolean,
    editorFocused: Boolean,
    inTree: Boolean,
): VisibilityAction? {
    if (command || alt || editorFocused) return null
    return when (key) {
        Key.T -> if (shift) null else VisibilityAction.ToggleTransparent
        Key.V -> if (shift) VisibilityAction.ShowAll else VisibilityAction.ToggleHidden
        Key.I -> if (shift) null else VisibilityAction.Isolate
        Key.Spacebar -> if (inTree && !shift) VisibilityAction.Cycle else null
        else -> null
    }
}

/** Where a press in a tree row landed: the eye column, the disclosure arrow, or the rest of the row. */
internal enum class PressZone { Eye, Arrow, Row }

/** The zone of a press at [x] (px). The arrow only exists on groups. The eye column comes first, then the indent. */
internal fun pressZone(
    x: Float,
    eyeStart: Float,
    eyeEnd: Float,
    arrowStart: Float,
    arrowEnd: Float,
    isGroup: Boolean,
): PressZone =
    when {
        x >= eyeStart && x < eyeEnd -> PressZone.Eye
        isGroup && x >= arrowStart && x < arrowEnd -> PressZone.Arrow
        else -> PressZone.Row
    }
