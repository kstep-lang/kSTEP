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
    const val OPEN_RECENT = "Open Recent"
}
