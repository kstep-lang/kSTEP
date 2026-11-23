package dev.kstep.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import dev.kstep.desktop.menu.FileAction
import dev.kstep.desktop.menu.MenuContext
import dev.kstep.desktop.menu.MenuEntry
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.menu.MenuReasons
import dev.kstep.desktop.menu.Shortcut
import dev.kstep.desktop.menu.fileMenu
import dev.kstep.desktop.menu.registeredShortcut
import dev.kstep.desktop.menu.stateOf
import dev.kstep.desktop.recent.RecentEntry
import dev.kstep.desktop.recent.RecentList

/** The key a menu shortcut letter stands for. Unknown letters fail loudly: no shortcut is ever silently dropped. */
internal fun keyFor(letter: Char): Key =
    when (letter) {
        'O' -> Key.O
        'S' -> Key.S
        'E' -> Key.E
        else -> error("No Compose key mapped for menu shortcut '$letter'")
    }

internal fun keyShortcutOf(
    shortcut: Shortcut,
    os: MenuOs,
): KeyShortcut =
    KeyShortcut(
        key = keyFor(shortcut.key),
        meta = os == MenuOs.MAC,
        ctrl = os != MenuOs.MAC,
        shift = shortcut.shift,
    )

/**
 * The native menu bar with the File menu (ADR-0072). The menu's items are all always present; a disabled one shows
 * greyed. The key handling lives here only: [onAction] merely routes to the lambdas the toolbar uses as well.
 */
@Composable
fun FrameWindowScope.FileMenuBar(
    os: MenuOs,
    ctx: MenuContext,
    recent: RecentList,
    onAction: (FileAction) -> Unit,
    onRecent: (RecentEntry) -> Unit,
    onClearRecent: () -> Unit,
) {
    MenuBar {
        Menu("File", mnemonic = 'F') {
            for (entry in fileMenu(os, recent)) {
                when (entry) {
                    is MenuEntry.Separator -> Separator()
                    is MenuEntry.Action -> actionItem(entry.action, os, ctx, onAction)
                    is MenuEntry.Recent ->
                        Menu("Open Recent") {
                            val sub = entry.submenu
                            for (item in sub.items) {
                                Item(item.text, onClick = { onRecent(item.entry) })
                            }
                            sub.emptyPlaceholder?.let { Item(it, enabled = false, onClick = {}) }
                            Separator()
                            Item(MenuReasons.CLEAR_RECENT, enabled = sub.clearEnabled, onClick = onClearRecent)
                        }
                }
            }
        }
    }
}

@Composable
private fun MenuScope.actionItem(
    action: FileAction,
    os: MenuOs,
    ctx: MenuContext,
    onAction: (FileAction) -> Unit,
) {
    Item(
        action.label,
        enabled = stateOf(action, ctx).enabled,
        shortcut = registeredShortcut(action, ctx)?.let { keyShortcutOf(it, os) },
        onClick = { onAction(action) },
    )
}
