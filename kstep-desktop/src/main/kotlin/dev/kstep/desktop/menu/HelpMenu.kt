package dev.kstep.desktop.menu

/** The entries of the Help menu (ADR-0076). */
enum class HelpAction(
    val label: String,
) {
    SHORTCUTS("Keyboard Shortcuts"),
    ABOUT("About kSTEP Desktop"),
}

/**
 * The entries of the Help menu in order. About stands here on every system, as the last entry; on macOS the system
 * application menu offers a second entry that opens the same dialog (D3d-3, ADR-0076 update).
 */
@Suppress("UNUSED_PARAMETER")
fun helpMenuEntries(os: MenuOs): List<HelpAction> = listOf(HelpAction.SHORTCUTS, HelpAction.ABOUT)

/** The Help entries are always available. */
@Suppress("UNUSED_PARAMETER")
fun stateOf(
    action: HelpAction,
    ctx: MenuContext,
): ActionState = ActionState(true, null)

/** What the About dialog shows; the link is fixed, never user input. */
data class AboutInfo(
    val name: String,
    val version: String,
    val note: String,
    val linkLabel: String,
    val url: String,
) {
    companion object {
        fun of(version: String): AboutInfo =
            AboutInfo("kSTEP Desktop", version, "Unpublished development tool", "kstep.dev", "https://kstep.dev")
    }
}

private const val SHORTCUTS_MIN_HEIGHT_DP = 160f
private const val SHORTCUTS_MAX_HEIGHT_DP = 480f
private const val SHORTCUTS_WINDOW_MARGIN_DP = 200f

/** The maximum height of the shortcut list for a window of [windowHeightDp]: window height minus 200, within 160..480. */
fun shortcutsMaxHeightDp(windowHeightDp: Float): Float =
    (windowHeightDp - SHORTCUTS_WINDOW_MARGIN_DP).coerceIn(SHORTCUTS_MIN_HEIGHT_DP, SHORTCUTS_MAX_HEIGHT_DP)

/** The keys of one row as the dialog shows them: alternatives are separated by " / ". */
fun keysText(row: ShortcutRow): String = row.keys.joinToString(" / ")

/** One line of the shortcut overview: the label as in the menu, and the keys (more than one if there is an alias). */
data class ShortcutRow(
    val group: String,
    val label: String,
    val keys: List<String>,
)

data class ShortcutGroup(
    val title: String,
    val rows: List<ShortcutRow>,
    val footnote: String?,
)

private const val SINGLE_KEY_NOTE = "Single keys work when the script editor does not have focus."

/**
 * The shortcut overview, built from the menu model so it can never drift from the real keys (ADR-0076). Entries without
 * a key and without a single-key hint (Quit, Close Workspace) have no row.
 */
fun shortcutOverview(os: MenuOs): List<ShortcutGroup> {
    val file =
        FileAction.entries.mapNotNull { a ->
            a.shortcut?.let { ShortcutRow("File", a.label, listOf(it.display(os))) }
        }
    val edit =
        EditAction.entries.mapNotNull { a ->
            shortcutOf(a, os)?.let { sc ->
                val keys =
                    if (a == EditAction.REDO) {
                        listOf(sc.display(os), shortcutOf(EditorShortcut.REDO_ALTERNATE, os).display(os))
                    } else {
                        listOf(sc.display(os))
                    }
                ShortcutRow("Edit", a.label, keys)
            }
        }
    val view =
        ViewAction.entries.mapNotNull { a ->
            val key = shortcutOf(a, os)?.display(os) ?: a.keyHint?.hintText(os)
            key?.let { ShortcutRow("View", a.label, listOf(it)) }
        }
    val editor =
        listOf(EditorShortcut.FIND_NEXT, EditorShortcut.FIND_PREVIOUS).map {
            ShortcutRow("Editor", it.label, listOf(shortcutOf(it, os).display(os)))
        }
    return listOf(
        ShortcutGroup("File", file, null),
        ShortcutGroup("Edit", edit, null),
        ShortcutGroup("View", view, SINGLE_KEY_NOTE),
        ShortcutGroup("Editor", editor, null),
    )
}

/** A key without the primary key and without Alt: only then is it a plain single-key command (not a mnemonic). */
fun isPlainKey(
    command: Boolean,
    alt: Boolean,
): Boolean = !command && !alt
