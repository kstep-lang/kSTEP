package dev.kstep.desktop.ui

import javax.swing.Icon
import javax.swing.JMenu
import javax.swing.JMenuBar

/**
 * Sets the icon of submenus (the Compose Menu cannot, ADR-0073). Only direct submenus of the top menu called
 * [topMenuText] are looked at, found by their text; an icon is only set when it is not already the same object (so
 * calling this repeatedly is harmless and does not flicker). Returns how many icons were set. A plain menu item is never
 * touched.
 */
internal fun applySubmenuIcons(
    menuBar: JMenuBar?,
    topMenuText: String,
    icons: Map<String, Icon?>,
): Int {
    if (menuBar == null) return 0
    var set = 0
    for (i in 0 until menuBar.menuCount) {
        val top = menuBar.getMenu(i) ?: continue
        if (top.text != topMenuText) continue
        for (j in 0 until top.itemCount) {
            val child = top.getItem(j)
            if (child !is JMenu) continue
            val icon = icons[child.text] ?: continue
            if (child.icon !== icon) {
                child.icon = icon
                set++
            }
        }
    }
    return set
}
