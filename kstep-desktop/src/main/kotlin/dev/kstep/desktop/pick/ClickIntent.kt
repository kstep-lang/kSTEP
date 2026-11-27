package dev.kstep.desktop.pick

import dev.kstep.desktop.menu.MenuOs

/** What a click with the given modifiers means (ADR-0092). */
enum class ClickIntent { Select, Toggle, Menu }

object ClickIntents {
    /**
     * macOS: Cmd+click toggles, Ctrl+click is the context menu (the system's secondary click). Windows and Linux:
     * Ctrl+click toggles. The secondary button always asks for the menu. Alt and Shift are not part of the rule here
     * (Shift is the range in the tree and the pan in the viewer).
     */
    fun of(
        secondary: Boolean,
        meta: Boolean,
        ctrl: Boolean,
        os: MenuOs,
    ): ClickIntent =
        when {
            secondary -> ClickIntent.Menu
            os == MenuOs.MAC && ctrl -> ClickIntent.Menu
            os == MenuOs.MAC && meta -> ClickIntent.Toggle
            os != MenuOs.MAC && ctrl -> ClickIntent.Toggle
            else -> ClickIntent.Select
        }
}
