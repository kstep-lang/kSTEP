package dev.kstep.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.MenuScope
import dev.kstep.desktop.menu.EditAction
import dev.kstep.desktop.menu.FileAction
import dev.kstep.desktop.menu.HelpAction
import dev.kstep.desktop.menu.MenuContext
import dev.kstep.desktop.menu.MenuEntry
import dev.kstep.desktop.menu.MenuIcons
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.menu.MenuReasons
import dev.kstep.desktop.menu.MenuTitles
import dev.kstep.desktop.menu.Shortcut
import dev.kstep.desktop.menu.ShortcutKey
import dev.kstep.desktop.menu.ViewAction
import dev.kstep.desktop.menu.fileMenu
import dev.kstep.desktop.menu.iconOf
import dev.kstep.desktop.menu.registeredShortcut
import dev.kstep.desktop.menu.stateOf
import dev.kstep.desktop.recent.RecentEntry
import dev.kstep.desktop.recent.RecentList
import kotlinx.coroutines.delay

/** The Compose key a menu shortcut key stands for. Unknown keys fail loudly: no shortcut is ever silently dropped. */
internal fun keyFor(key: ShortcutKey): Key =
    when (key) {
        ShortcutKey.Up -> Key.DirectionUp
        ShortcutKey.Down -> Key.DirectionDown
        is ShortcutKey.Letter ->
            when (key.c) {
                'O' -> Key.O
                'S' -> Key.S
                'E' -> Key.E
                'Z' -> Key.Z
                'Y' -> Key.Y
                'F' -> Key.F
                'H' -> Key.H
                'L' -> Key.L
                'R' -> Key.R
                'K' -> Key.K
                'X' -> Key.X
                'C' -> Key.C
                'V' -> Key.V
                'A' -> Key.A
                else -> error("No Compose key mapped for menu shortcut '${key.c}'")
            }
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
        alt = shortcut.alt,
    )

private fun mnemonicOf(title: String): Char = MenuTitles.MNEMONICS.getValue(title)

private const val SUBMENU_ICON_ATTEMPTS = 10
private const val SUBMENU_ICON_RETRY_MS = 100L

/**
 * The native menu bar with the File, Edit, View and Help menus (ADR-0072, ADR-0076). The menu's items are all always present; a disabled one shows
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
    onEdit: (EditAction) -> Unit,
    onView: (ViewAction) -> Unit,
    aboutInAppMenu: Boolean,
    onHelp: (HelpAction) -> Unit,
) {
    // The Compose Menu has no icon parameter: the icon of the submenu is set on the Swing menu (ADR-0073).
    val recentIcon = menuIconToSwing(MenuIcons.OPEN_RECENT_SUBMENU, enabled = true)
    LaunchedEffect(recentIcon, recent.entries.size) {
        // The Swing menu bar is built after composition; try a few times, setting is idempotent.
        repeat(SUBMENU_ICON_ATTEMPTS) {
            applySubmenuIcons(window.jMenuBar, MenuTitles.FILE, mapOf(MenuTitles.OPEN_RECENT to recentIcon))
            delay(SUBMENU_ICON_RETRY_MS)
        }
    }
    MenuBar {
        Menu(MenuTitles.FILE, mnemonic = mnemonicOf(MenuTitles.FILE)) {
            for (entry in fileMenu(os, recent)) {
                when (entry) {
                    is MenuEntry.Separator -> Separator()
                    is MenuEntry.Action -> actionItem(entry.action, os, ctx, onAction)
                    is MenuEntry.EditItem, is MenuEntry.ViewItem -> Unit
                    is MenuEntry.Recent ->
                        Menu(MenuTitles.OPEN_RECENT) {
                            val sub = entry.submenu
                            for (item in sub.items) {
                                // No icon on dynamic entries (MenuIcons.RECENT_ITEM, ADR-0073).
                                Item(item.text, onClick = { onRecent(item.entry) })
                            }
                            sub.emptyPlaceholder?.let { Item(it, enabled = false, onClick = {}) }
                            Separator()
                            Item(
                                MenuReasons.CLEAR_RECENT,
                                icon = menuIcon(MenuIcons.CLEAR_RECENT_ITEM, sub.clearEnabled),
                                enabled = sub.clearEnabled,
                                onClick = onClearRecent,
                            )
                        }
                }
            }
        }
        Menu(MenuTitles.EDIT, mnemonic = mnemonicOf(MenuTitles.EDIT)) { editMenuItems(os, ctx, onEdit) }
        Menu(MenuTitles.VIEW, mnemonic = mnemonicOf(MenuTitles.VIEW)) { viewMenuItems(os, ctx, onView) }
        Menu(MenuTitles.HELP, mnemonic = mnemonicOf(MenuTitles.HELP)) { helpMenuItems(os, aboutInAppMenu, onHelp) }
    }
}

@Composable
private fun MenuScope.actionItem(
    action: FileAction,
    os: MenuOs,
    ctx: MenuContext,
    onAction: (FileAction) -> Unit,
) {
    val state = stateOf(action, ctx)
    Item(
        action.label,
        icon = menuIcon(iconOf(action), enabled = state.enabled),
        enabled = state.enabled,
        shortcut = registeredShortcut(action, ctx, os)?.let { keyShortcutOf(it, os) },
        onClick = { onAction(action) },
    )
}
