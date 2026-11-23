package dev.kstep.desktop.menu

/** The icons of the File menu (ADR-0073). A pure identifier; the drawing lives in the ui package. */
enum class MenuIconId {
    OPEN,
    OPEN_FOLDER,
    OPEN_RECENT,
    CLEAR_RECENT,
    CLOSE_WORKSPACE,
    SAVE,
    EXPORT,
    QUIT,
    UNDO,
    REDO,
    CUT,
    COPY,
    PASTE,
    SELECT_ALL,
    FIND,
    REPLACE,
    GO_TO_LINE,
    RUN,
    HOME,
    PREVIOUS_BLOCK,
    NEXT_BLOCK,
    TRANSPARENT,
    HIDE,
    ISOLATE,
    SHOW_ALL,
    AI_PANEL,
}

/** The icon of a File action. Exhaustive: a new [FileAction] does not compile without an icon. */
fun iconOf(action: FileAction): MenuIconId =
    when (action) {
        FileAction.OPEN -> MenuIconId.OPEN
        FileAction.OPEN_FOLDER -> MenuIconId.OPEN_FOLDER
        FileAction.CLOSE_WORKSPACE -> MenuIconId.CLOSE_WORKSPACE
        FileAction.SAVE -> MenuIconId.SAVE
        FileAction.EXPORT -> MenuIconId.EXPORT
        FileAction.QUIT -> MenuIconId.QUIT
    }

/** The icon of an Edit action. Exhaustive. */
fun iconOf(action: EditAction): MenuIconId =
    when (action) {
        EditAction.UNDO -> MenuIconId.UNDO
        EditAction.REDO -> MenuIconId.REDO
        EditAction.CUT -> MenuIconId.CUT
        EditAction.COPY -> MenuIconId.COPY
        EditAction.PASTE -> MenuIconId.PASTE
        EditAction.SELECT_ALL -> MenuIconId.SELECT_ALL
        EditAction.FIND -> MenuIconId.FIND
        EditAction.REPLACE -> MenuIconId.REPLACE
        EditAction.GO_TO_LINE -> MenuIconId.GO_TO_LINE
    }

/** The icon of a View action. Exhaustive. */
fun iconOf(action: ViewAction): MenuIconId =
    when (action) {
        ViewAction.RUN -> MenuIconId.RUN
        ViewAction.HOME -> MenuIconId.HOME
        ViewAction.PREVIOUS_BLOCK -> MenuIconId.PREVIOUS_BLOCK
        ViewAction.NEXT_BLOCK -> MenuIconId.NEXT_BLOCK
        ViewAction.TRANSPARENT -> MenuIconId.TRANSPARENT
        ViewAction.HIDE -> MenuIconId.HIDE
        ViewAction.ISOLATE -> MenuIconId.ISOLATE
        ViewAction.SHOW_ALL -> MenuIconId.SHOW_ALL
        ViewAction.AI_PANEL -> MenuIconId.AI_PANEL
    }

/** The icons of the entries that are not a [FileAction]. */
object MenuIcons {
    val OPEN_RECENT_SUBMENU: MenuIconId = MenuIconId.OPEN_RECENT
    val CLEAR_RECENT_ITEM: MenuIconId = MenuIconId.CLEAR_RECENT

    /** Dynamic recent entries and the "No Recent Items" placeholder deliberately have no icon (ADR-0073). */
    val RECENT_ITEM: MenuIconId? = null
    val EMPTY_PLACEHOLDER: MenuIconId? = null
}

/** The texts the Swing workaround for the submenu icon looks a menu up by; one source for the menu and the lookup. */
object MenuTitles {
    const val FILE = "File"
    const val EDIT = "Edit"
    const val VIEW = "View"
    const val OPEN_RECENT = "Open Recent"
}
