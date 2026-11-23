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

/** A letter with the primary key (Cmd on macOS, Ctrl elsewhere) and optionally Shift. */
data class Shortcut(
    val key: Char,
    val shift: Boolean = false,
) {
    fun display(os: MenuOs): String =
        if (os == MenuOs.MAC) {
            (if (shift) "⇧" else "") + "⌘" + key
        } else {
            "Ctrl+" + (if (shift) "Shift+" else "") + key
        }
}

/** The entries of the File menu. */
enum class FileAction(
    val label: String,
    val shortcut: Shortcut?,
) {
    OPEN("Open…", Shortcut('O')),
    OPEN_FOLDER("Open Folder…", Shortcut('O', shift = true)),
    CLOSE_WORKSPACE("Close Workspace", null),
    SAVE("Save", Shortcut('S')),
    EXPORT("Export…", Shortcut('E')),
    QUIT("Quit", null),
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
)

object MenuReasons {
    const val KNOWLEDGE_UNAVAILABLE = "Not available in a knowledge workspace yet (planned)"
    const val OPEN_SCRIPT_FIRST = "Open a script first"
    const val NO_FOLDER_OPEN = "No folder is open"
    const val NO_RECENT = "No Recent Items"
    const val CLEAR_RECENT = "Clear Recent"
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
                else -> ENABLED
            }
    }

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

    data object Separator : MenuEntry
}

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
        // On macOS Quit lives in the application menu (Cmd+Q, handled by the quit handler).
        if (os != MenuOs.MAC) {
            add(MenuEntry.Separator)
            add(MenuEntry.Action(FileAction.QUIT))
        }
    }
