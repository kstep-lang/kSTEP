package dev.kstep.desktop.ui

/** What an Enter press does while the find or go-to bar is open; `null` leaves Enter to the editor. */
internal enum class BarEnterAction { NextMatch, PreviousMatch, Replace, CommitGoTo }

/**
 * The focus of the bar fields, tracked per field so that leaving one field never leaves the bar
 * "focused" (an Enter in the editor would otherwise be swallowed while the bar stays open).
 */
internal data class BarFocus(
    val query: Boolean = false,
    val replace: Boolean = false,
    val goTo: Boolean = false,
) {
    val any: Boolean get() = query || replace || goTo
}

/** Decides what Enter does; pure, so the focus rules are testable without a window. */
internal fun barEnterAction(
    findOpen: Boolean,
    goToOpen: Boolean,
    focus: BarFocus,
    shift: Boolean,
): BarEnterAction? =
    when {
        findOpen && focus.replace -> BarEnterAction.Replace
        findOpen && focus.query -> if (shift) BarEnterAction.PreviousMatch else BarEnterAction.NextMatch
        goToOpen && focus.goTo -> BarEnterAction.CommitGoTo
        else -> null
    }
