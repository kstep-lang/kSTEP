package dev.kstep.desktop.menu

import dev.kstep.desktop.recent.RecentEntry
import dev.kstep.desktop.recent.RecentList
import dev.kstep.desktop.recent.RecentMessages
import java.util.Locale

/** The operating system as far as the menu bar cares: where the primary key is Cmd and where Quit lives (ADR-0072). */
enum class MenuOs {
    MAC,
    WINDOWS,
    LINUX,
    ;

    companion object {
        fun current(osName: String = System.getProperty("os.name").orEmpty()): MenuOs {
            val name = osName.lowercase(Locale.ROOT)
            return when {
                name.contains("mac") -> MAC
                name.contains("win") -> WINDOWS
                else -> LINUX
            }
        }
    }
}

/** The key of a menu shortcut: a letter or one of the arrow keys. */
sealed interface ShortcutKey {
    data class Letter(
        val c: Char,
    ) : ShortcutKey {
        init {
            require(c in 'A'..'Z') { "a shortcut letter is A-Z" }
        }
    }

    data object Up : ShortcutKey

    data object Down : ShortcutKey
}

/** A key with the primary key (Cmd on macOS, Ctrl elsewhere) and optionally Shift and Alt (Option on macOS). */
data class Shortcut(
    val key: ShortcutKey,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    fun display(os: MenuOs): String {
        val name =
            when (key) {
                is ShortcutKey.Letter -> key.c.toString()
                ShortcutKey.Up -> if (os == MenuOs.MAC) "↑" else "Up"
                ShortcutKey.Down -> if (os == MenuOs.MAC) "↓" else "Down"
            }
        return if (os == MenuOs.MAC) {
            (if (shift) "⇧" else "") + (if (alt) "⌥" else "") + "⌘" + name
        } else {
            "Ctrl+" + (if (shift) "Shift+" else "") + (if (alt) "Alt+" else "") + name
        }
    }
}

private fun letter(c: Char) = ShortcutKey.Letter(c)

/** The entries of the File menu. */
enum class FileAction(
    val label: String,
    val shortcut: Shortcut?,
) {
    OPEN("Open…", Shortcut(letter('O'))),
    OPEN_FOLDER("Open Folder…", Shortcut(letter('O'), shift = true)),
    CLOSE_WORKSPACE("Close Workspace", null),
    SAVE("Save", Shortcut(letter('S'))),
    EXPORT("Export…", Shortcut(letter('E'))),
    CONVERT("Convert…", null),
    QUIT("Quit", null),
}

/** The entries of the Edit menu (ADR-0074, ADR-0075). */
enum class EditAction(
    val label: String,
) {
    UNDO("Undo"),
    REDO("Redo"),
    CUT("Cut"),
    COPY("Copy"),
    PASTE("Paste"),
    SELECT_ALL("Select All"),
    FIND("Find…"),
    REPLACE("Replace…"),
    GO_TO_LINE("Go to Line…"),
}

/** The single key a View entry answers to while the viewer has the focus; shown in the menu text, never registered. */
data class KeyHint(
    val letter: Char,
    val shift: Boolean,
) {
    fun hintText(os: MenuOs): String =
        if (shift) {
            (if (os == MenuOs.MAC) "⇧" else "Shift+") + letter
        } else {
            letter.toString()
        }
}

/** The entries of the View menu (ADR-0074). */
enum class ViewAction(
    val label: String,
    val keyHint: KeyHint?,
) {
    RUN("Run Script", null),
    HOME("Home", KeyHint('H', false)),
    PREVIOUS_BLOCK("Previous Block", null),
    NEXT_BLOCK("Next Block", null),
    TRANSPARENT("Toggle Transparent", KeyHint('T', false)),
    HIDE("Toggle Hidden", KeyHint('V', false)),
    ISOLATE("Isolate", KeyHint('I', false)),
    SHOW_ALL("Show All", KeyHint('V', true)),
    AI_PANEL("Show AI Panel", null),
    ;

    /** The menu text, with the single-key hint in brackets (two blanks before the bracket). */
    fun menuText(os: MenuOs): String = MenuText.safe(keyHint?.let { "$label  (${it.hintText(os)})" } ?: label)
}

private fun sc(
    c: Char,
    shift: Boolean = false,
    alt: Boolean = false,
) = Shortcut(letter(c), shift, alt)

/** The shortcut the menu registers for an Edit entry on [os], or null. */
fun shortcutOf(
    action: EditAction,
    os: MenuOs,
): Shortcut? {
    val m = os == MenuOs.MAC
    return when (action) {
        EditAction.UNDO -> sc('Z')
        EditAction.REDO -> if (m) sc('Z', shift = true) else sc('Y')
        EditAction.CUT -> sc('X')
        EditAction.COPY -> sc('C')
        EditAction.PASTE -> sc('V')
        EditAction.SELECT_ALL -> sc('A')
        EditAction.FIND -> sc('F')
        EditAction.REPLACE -> if (m) sc('F', alt = true) else sc('H')
        EditAction.GO_TO_LINE -> sc('L')
    }
}

/** The shortcut the menu registers for a View entry on [os]; the single-key entries have none. */
@Suppress("UNUSED_PARAMETER")
fun shortcutOf(
    action: ViewAction,
    os: MenuOs,
): Shortcut? =
    when (action) {
        ViewAction.RUN -> sc('R')
        ViewAction.AI_PANEL -> sc('K')
        ViewAction.PREVIOUS_BLOCK -> Shortcut(ShortcutKey.Up, alt = true)
        ViewAction.NEXT_BLOCK -> Shortcut(ShortcutKey.Down, alt = true)
        ViewAction.HOME,
        ViewAction.TRANSPARENT,
        ViewAction.HIDE,
        ViewAction.ISOLATE,
        ViewAction.SHOW_ALL,
        -> null
    }

/** Enabled, or disabled with the reason (the reason is shown in the status line when the shortcut is pressed). */
data class ActionState(
    val enabled: Boolean,
    val reason: String?,
) {
    init {
        require(enabled == (reason == null)) { "an action is enabled exactly when it has no reason" }
    }
}

data class MenuContext(
    val scriptOpen: Boolean,
    val knowledgeOpen: Boolean,
    val exportEnabled: Boolean,
    /** An editor buffer is open (a script or a note). */
    val editorOpen: Boolean = false,
    val editorFocused: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** A file is open, so Run has something to run. */
    val fileOpen: Boolean = false,
    /** A model is shown, so the visibility actions can work. */
    val visibilityAvailable: Boolean = false,
    val hasSelection: Boolean = false,
    /** Nothing is hidden or transparent, so Show All has nothing to do. */
    val nothingChanged: Boolean = false,
    /** The editor selection is not empty (not [hasSelection], which belongs to the part selection). */
    val textSelected: Boolean = false,
    /** The system clipboard holds text. */
    val clipboardHasText: Boolean = false,
    /** The editor is the focus target (see [FocusTarget]): it had the focus last, or the window is not focused. */
    val editorFocusTarget: Boolean = false,
    /** A conversion is running (File > Convert, ADR-0078). */
    val convertRunning: Boolean = false,
    /** The export dialog is open, so the convert dialog does not open on top of it. */
    val exportDialogVisible: Boolean = false,
    /** The convert dialog is open (in any state), so the export dialog does not open on top of it. */
    val convertDialogVisible: Boolean = false,
)

object MenuReasons {
    const val KNOWLEDGE_UNAVAILABLE = "Not available in a knowledge workspace yet (planned)"
    const val OPEN_SCRIPT_FIRST = "Open a script first"
    const val NO_FOLDER_OPEN = "No folder is open"
    const val NO_RECENT = "No Recent Items"
    const val CLEAR_RECENT = "Clear Recent"
    const val NOTHING_TO_UNDO = "Nothing to undo"
    const val NOTHING_TO_REDO = "Nothing to redo"
    const val SELECT_PART_FIRST = "Select a part first"
    const val NOTHING_CHANGED = "Nothing is hidden or transparent"
    const val SELECT_TEXT_FIRST = "Select text first"
    const val CLIPBOARD_EMPTY = "Clipboard is empty"
    const val CLICK_INTO_EDITOR = "Click into the editor first"
    const val CONVERSION_RUNNING = "A conversion is running"
    const val CLOSE_EXPORT_FIRST = "Close the export dialog first"
    const val CLOSE_CONVERT_FIRST = "Close the convert dialog first"
    const val NO_TRANSPARENT_PARTS = "No transparent parts"
}

private val ENABLED = ActionState(true, null)

private fun disabled(reason: String) = ActionState(false, reason)

fun stateOf(
    action: FileAction,
    ctx: MenuContext,
): ActionState =
    when (action) {
        FileAction.OPEN, FileAction.OPEN_FOLDER, FileAction.QUIT -> ENABLED
        FileAction.CLOSE_WORKSPACE -> if (ctx.knowledgeOpen) ENABLED else disabled(MenuReasons.NO_FOLDER_OPEN)
        FileAction.SAVE -> if (ctx.scriptOpen) ENABLED else disabled(MenuReasons.OPEN_SCRIPT_FIRST)
        FileAction.EXPORT ->
            when {
                ctx.knowledgeOpen -> disabled(MenuReasons.KNOWLEDGE_UNAVAILABLE)
                !ctx.exportEnabled -> disabled(MenuReasons.OPEN_SCRIPT_FIRST)
                ctx.convertDialogVisible -> disabled(MenuReasons.CLOSE_CONVERT_FIRST)
                else -> ENABLED
            }
        FileAction.CONVERT ->
            when {
                ctx.convertRunning -> disabled(MenuReasons.CONVERSION_RUNNING)
                ctx.exportDialogVisible -> disabled(MenuReasons.CLOSE_EXPORT_FIRST)
                else -> ENABLED
            }
    }

/** The Edit entries that act on the focused editor: without the editor as focus target they are disabled. */
val CLIPBOARD_ACTIONS: Set<EditAction> =
    setOf(EditAction.CUT, EditAction.COPY, EditAction.PASTE, EditAction.SELECT_ALL)

/** The Edit entries whose shortcut is registered and routed only while the editor really has the focus. */
val FOCUS_OWNED: Set<EditAction> = setOf(EditAction.UNDO, EditAction.REDO) + CLIPBOARD_ACTIONS

fun stateOf(
    action: EditAction,
    ctx: MenuContext,
): ActionState =
    when {
        !ctx.editorOpen -> disabled(MenuReasons.OPEN_SCRIPT_FIRST)
        action in CLIPBOARD_ACTIONS && !ctx.editorFocusTarget -> disabled(MenuReasons.CLICK_INTO_EDITOR)
        (action == EditAction.CUT || action == EditAction.COPY) && !ctx.textSelected ->
            disabled(MenuReasons.SELECT_TEXT_FIRST)
        action == EditAction.PASTE && !ctx.clipboardHasText -> disabled(MenuReasons.CLIPBOARD_EMPTY)
        action == EditAction.UNDO && !ctx.canUndo -> disabled(MenuReasons.NOTHING_TO_UNDO)
        action == EditAction.REDO && !ctx.canRedo -> disabled(MenuReasons.NOTHING_TO_REDO)
        else -> ENABLED
    }

fun stateOf(
    action: ViewAction,
    ctx: MenuContext,
): ActionState =
    when (action) {
        ViewAction.RUN -> if (ctx.fileOpen) ENABLED else disabled(MenuReasons.OPEN_SCRIPT_FIRST)
        ViewAction.HOME -> ENABLED
        ViewAction.PREVIOUS_BLOCK, ViewAction.NEXT_BLOCK ->
            if (ctx.knowledgeOpen) ENABLED else disabled(MenuReasons.NO_FOLDER_OPEN)
        ViewAction.TRANSPARENT, ViewAction.HIDE, ViewAction.ISOLATE ->
            when {
                !ctx.visibilityAvailable -> disabled(MenuReasons.OPEN_SCRIPT_FIRST)
                !ctx.hasSelection -> disabled(MenuReasons.SELECT_PART_FIRST)
                else -> ENABLED
            }
        ViewAction.SHOW_ALL ->
            when {
                !ctx.visibilityAvailable -> disabled(MenuReasons.OPEN_SCRIPT_FIRST)
                ctx.nothingChanged -> disabled(MenuReasons.NOTHING_CHANGED)
                else -> ENABLED
            }
        ViewAction.AI_PANEL -> if (ctx.knowledgeOpen) disabled(MenuReasons.KNOWLEDGE_UNAVAILABLE) else ENABLED
    }

/**
 * The shortcut the native menu registers for an entry: only while the entry is enabled. A disabled entry registers
 * none, so the native menu cannot swallow the key press; it reaches the window's key handler, which shows the reason
 * ([ShortcutRouting], ADR-0072). Without this a disabled shortcut could fail silently. Undo and Redo register only
 * while the editor has the focus, so a focused text field keeps its own undo.
 */
fun registeredShortcut(
    action: FileAction,
    ctx: MenuContext,
    @Suppress("UNUSED_PARAMETER") os: MenuOs,
): Shortcut? = if (stateOf(action, ctx).enabled) action.shortcut else null

fun registeredShortcut(
    action: EditAction,
    ctx: MenuContext,
    os: MenuOs,
): Shortcut? {
    if (!stateOf(action, ctx).enabled) return null
    if (action in FOCUS_OWNED && !ctx.editorFocused) return null
    return shortcutOf(action, os)
}

fun registeredShortcut(
    action: ViewAction,
    ctx: MenuContext,
    os: MenuOs,
): Shortcut? = if (stateOf(action, ctx).enabled) shortcutOf(action, os) else null

/** Keeps Swing from reading a menu text as HTML (`<html>...`): a leading `<` (after blanks) becomes `‹`. */
object MenuText {
    fun safe(s: String): String {
        val first = s.indexOfFirst { !it.isWhitespace() && !it.isISOControl() }
        if (first < 0) return s
        // Every '<' directly at the start (after blanks) is replaced, not just the first one.
        val sb = StringBuilder(s)
        var i = first
        while (i < sb.length && sb[i] == '<') {
            sb[i] = '‹'
            i++
        }
        return sb.toString()
    }
}

data class RecentItem(
    val entry: RecentEntry,
    val text: String,
)

data class RecentSubmenu(
    val items: List<RecentItem>,
    val emptyPlaceholder: String?,
    val clearEnabled: Boolean,
)

object RecentSubmenuModel {
    fun of(list: RecentList): RecentSubmenu {
        val items =
            list.entries.map { entry ->
                val label = RecentMessages.label(entry)
                val text = if (label.parent.isEmpty()) label.name else "${label.name}  —  ${label.parent}"
                RecentItem(entry, MenuText.safe(text))
            }
        return RecentSubmenu(
            items = items,
            emptyPlaceholder = if (items.isEmpty()) MenuReasons.NO_RECENT else null,
            clearEnabled = items.isNotEmpty(),
        )
    }
}

sealed interface MenuEntry {
    data class Action(
        val action: FileAction,
    ) : MenuEntry

    data class Recent(
        val submenu: RecentSubmenu,
    ) : MenuEntry

    data class EditItem(
        val action: EditAction,
    ) : MenuEntry

    data class ViewItem(
        val action: ViewAction,
    ) : MenuEntry

    data object Separator : MenuEntry
}

fun editMenu(): List<MenuEntry> =
    listOf(
        MenuEntry.EditItem(EditAction.UNDO),
        MenuEntry.EditItem(EditAction.REDO),
        MenuEntry.Separator,
        MenuEntry.EditItem(EditAction.CUT),
        MenuEntry.EditItem(EditAction.COPY),
        MenuEntry.EditItem(EditAction.PASTE),
        MenuEntry.EditItem(EditAction.SELECT_ALL),
        MenuEntry.Separator,
        MenuEntry.EditItem(EditAction.FIND),
        MenuEntry.EditItem(EditAction.REPLACE),
        MenuEntry.EditItem(EditAction.GO_TO_LINE),
    )

fun viewMenu(): List<MenuEntry> =
    listOf(
        MenuEntry.ViewItem(ViewAction.RUN),
        MenuEntry.Separator,
        MenuEntry.ViewItem(ViewAction.HOME),
        MenuEntry.ViewItem(ViewAction.PREVIOUS_BLOCK),
        MenuEntry.ViewItem(ViewAction.NEXT_BLOCK),
        MenuEntry.Separator,
        MenuEntry.ViewItem(ViewAction.TRANSPARENT),
        MenuEntry.ViewItem(ViewAction.HIDE),
        MenuEntry.ViewItem(ViewAction.ISOLATE),
        MenuEntry.ViewItem(ViewAction.SHOW_ALL),
        MenuEntry.Separator,
        MenuEntry.ViewItem(ViewAction.AI_PANEL),
    )

fun fileMenu(
    os: MenuOs,
    recent: RecentList,
): List<MenuEntry> =
    buildList {
        add(MenuEntry.Action(FileAction.OPEN))
        add(MenuEntry.Action(FileAction.OPEN_FOLDER))
        add(MenuEntry.Recent(RecentSubmenuModel.of(recent)))
        add(MenuEntry.Separator)
        add(MenuEntry.Action(FileAction.CLOSE_WORKSPACE))
        add(MenuEntry.Separator)
        add(MenuEntry.Action(FileAction.SAVE))
        add(MenuEntry.Action(FileAction.EXPORT))
        add(MenuEntry.Action(FileAction.CONVERT))
        // On macOS Quit lives in the application menu (Cmd+Q, handled by the quit handler).
        if (os != MenuOs.MAC) {
            add(MenuEntry.Separator)
            add(MenuEntry.Action(FileAction.QUIT))
        }
    }

/**
 * The clipboard focus target (ADR-0075). The menu state is measured at the target, not at the real focus: opening a
 * menu may take the focus from the editor, and the entries must not turn grey just because of that. The editor having
 * the focus makes it the target; a focused window whose editor has no focus clears it (another field has it); a
 * window without focus keeps the previous value.
 */
object FocusTarget {
    fun next(
        current: Boolean,
        editorFocused: Boolean,
        windowFocused: Boolean,
    ): Boolean =
        when {
            editorFocused -> true
            windowFocused -> false
            else -> current
        }
}

/** The text that may be pasted into the editor (ADR-0075): line ends normalised, size capped. */
object PasteText {
    const val MAX_CHARS = 4_194_304

    /** The text with CRLF and lone CR turned into LF, or null when it is longer than [MAX_CHARS]. */
    fun normalize(s: String): String? {
        // The result is never longer than the raw text: checking first avoids copying a huge string.
        if (s.length > MAX_CHARS * 2) return null
        if (s.indexOf('\r') < 0) return if (s.length > MAX_CHARS) null else s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\r') {
                sb.append('\n')
                if (i + 1 < s.length && s[i + 1] == '\n') i++
            } else {
                sb.append(c)
            }
            i++
        }
        return if (sb.length > MAX_CHARS) null else sb.toString()
    }
}
