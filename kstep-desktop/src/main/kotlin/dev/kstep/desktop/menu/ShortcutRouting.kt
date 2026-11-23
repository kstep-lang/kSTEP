package dev.kstep.desktop.menu

/**
 * Who handles a key press: the menu owns the shortcuts of its File, Edit and View entries (ADR-0072, ADR-0074). The
 * window's key handler never runs them; it lets the press through to the menu, or shows why the entry is disabled.
 */
object ShortcutRouting {
    sealed interface Route {
        data object NotMenuOwned : Route

        data object PassToMenu : Route

        data class ShowReason(
            val reason: String,
        ) : Route
    }

    /** The entry a shortcut belongs to. */
    sealed interface MenuOwned {
        data class File(
            val action: FileAction,
        ) : MenuOwned

        data class Edit(
            val action: EditAction,
        ) : MenuOwned

        data class View(
            val action: ViewAction,
        ) : MenuOwned
    }

    fun menuOwnedShortcuts(os: MenuOs): Map<Shortcut, MenuOwned> {
        val owned = LinkedHashMap<Shortcut, MenuOwned>()
        for (a in FileAction.entries) a.shortcut?.let { owned[it] = MenuOwned.File(a) }
        for (a in EditAction.entries) shortcutOf(a, os)?.let { owned[it] = MenuOwned.Edit(a) }
        for (a in ViewAction.entries) shortcutOf(a, os)?.let { owned[it] = MenuOwned.View(a) }
        return owned
    }

    private fun stateOf(
        owned: MenuOwned,
        ctx: MenuContext,
    ): ActionState =
        when (owned) {
            is MenuOwned.File -> stateOf(owned.action, ctx)
            is MenuOwned.Edit -> stateOf(owned.action, ctx)
            is MenuOwned.View -> stateOf(owned.action, ctx)
        }

    fun route(
        key: ShortcutKey,
        primary: Boolean,
        shift: Boolean,
        alt: Boolean,
        os: MenuOs,
        ctx: MenuContext,
    ): Route {
        if (!primary) return Route.NotMenuOwned
        val owned = menuOwnedShortcuts(os)[Shortcut(key, shift, alt)] ?: return Route.NotMenuOwned
        // A focused text field keeps its own undo and clipboard keys: without the editor focus they are not ours.
        if (owned is MenuOwned.Edit && owned.action in FOCUS_OWNED && !ctx.editorFocused) return Route.NotMenuOwned
        val state = stateOf(owned, ctx)
        return if (state.enabled) Route.PassToMenu else Route.ShowReason(checkNotNull(state.reason))
    }
}

/**
 * Aliases only the editor handles (ADR-0074): they are no menu entry and are never registered with the menu. Per
 * operating system, so the tables of the menu and of the editor never overlap. F3 and Shift+F3 (without the primary
 * key) and Ctrl+H on macOS (the old Replace key, kept as an editor alias) are not part of these tables.
 */
object EditorOwnedShortcuts {
    fun of(os: MenuOs): Set<Shortcut> {
        val g = ShortcutKey.Letter('G')
        val find = setOf(Shortcut(g), Shortcut(g, shift = true))
        return find +
            if (os == MenuOs.MAC) {
                setOf(Shortcut(ShortcutKey.Letter('Y')))
            } else {
                setOf(Shortcut(ShortcutKey.Letter('Z'), shift = true))
            }
    }
}

/**
 * What the editor does with a Cut, Copy, Paste or Select All key that the menu lets through (ADR-0075). On macOS the
 * native menu runs the entry itself, so the editor only swallows the key; elsewhere the editor runs the action.
 */
object ClipboardKeys {
    enum class Handling {
        SWALLOW,
        DISPATCH,
    }

    val ACTIONS: Set<EditAction> = CLIPBOARD_ACTIONS

    fun of(os: MenuOs): Handling = if (os == MenuOs.MAC) Handling.SWALLOW else Handling.DISPATCH

    fun actionOf(key: ShortcutKey): EditAction? =
        when ((key as? ShortcutKey.Letter)?.c) {
            'X' -> EditAction.CUT
            'C' -> EditAction.COPY
            'V' -> EditAction.PASTE
            'A' -> EditAction.SELECT_ALL
            else -> null
        }
}
