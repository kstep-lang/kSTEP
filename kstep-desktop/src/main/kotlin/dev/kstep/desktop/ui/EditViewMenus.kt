package dev.kstep.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.MenuScope
import dev.kstep.desktop.menu.EditAction
import dev.kstep.desktop.menu.MenuContext
import dev.kstep.desktop.menu.MenuEntry
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.menu.MenuText
import dev.kstep.desktop.menu.ViewAction
import dev.kstep.desktop.menu.editMenu
import dev.kstep.desktop.menu.iconOf
import dev.kstep.desktop.menu.registeredShortcut
import dev.kstep.desktop.menu.stateOf
import dev.kstep.desktop.menu.viewMenu

/** The items of the Edit menu (ADR-0074). The key handling lives in the window; [onEdit] merely routes. */
@Composable
internal fun MenuScope.editMenuItems(
    os: MenuOs,
    ctx: MenuContext,
    onEdit: (EditAction) -> Unit,
) {
    for (entry in editMenu()) {
        when (entry) {
            is MenuEntry.Separator -> Separator()
            is MenuEntry.EditItem -> {
                val a = entry.action
                val state = stateOf(a, ctx)
                Item(
                    MenuText.safe(a.label),
                    icon = menuIcon(iconOf(a), enabled = state.enabled),
                    enabled = state.enabled,
                    shortcut = registeredShortcut(a, ctx, os)?.let { keyShortcutOf(it, os) },
                    onClick = { onEdit(a) },
                )
            }
            else -> Unit
        }
    }
}

/** The items of the View menu (ADR-0074). Entries with a single-key hint register no shortcut. */
@Composable
internal fun MenuScope.viewMenuItems(
    os: MenuOs,
    ctx: MenuContext,
    onView: (ViewAction) -> Unit,
) {
    for (entry in viewMenu()) {
        when (entry) {
            is MenuEntry.Separator -> Separator()
            is MenuEntry.ViewItem -> {
                val a = entry.action
                val state = stateOf(a, ctx)
                Item(
                    a.menuText(os),
                    icon = menuIcon(iconOf(a), enabled = state.enabled),
                    enabled = state.enabled,
                    shortcut = registeredShortcut(a, ctx, os)?.let { keyShortcutOf(it, os) },
                    onClick = { onView(a) },
                )
            }
            else -> Unit
        }
    }
}
