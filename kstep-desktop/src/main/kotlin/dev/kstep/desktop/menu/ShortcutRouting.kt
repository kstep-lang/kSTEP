package dev.kstep.desktop.menu

/**
 * Who handles a key press: the menu owns Open, Open Folder, Save and Export (ADR-0072). The window's key handler never
 * runs them; it lets the press through to the menu, or shows why the entry is disabled.
 */
object ShortcutRouting {
    sealed interface Route {
        data object NotMenuOwned : Route

        data object PassToMenu : Route

        data class ShowReason(
            val reason: String,
        ) : Route
    }

    fun menuOwnedShortcuts(
        @Suppress("UNUSED_PARAMETER") os: MenuOs,
    ): Map<Shortcut, FileAction> =
        FileAction.entries.mapNotNull { action -> action.shortcut?.let { it to action } }.toMap()

    fun route(
        key: Char,
        primary: Boolean,
        shift: Boolean,
        alt: Boolean,
        os: MenuOs,
        ctx: MenuContext,
    ): Route {
        if (!primary || alt) return Route.NotMenuOwned
        val action = menuOwnedShortcuts(os)[Shortcut(key.uppercaseChar(), shift)] ?: return Route.NotMenuOwned
        val state = stateOf(action, ctx)
        return if (state.enabled) Route.PassToMenu else Route.ShowReason(checkNotNull(state.reason))
    }
}
