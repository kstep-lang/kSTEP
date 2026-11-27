package dev.kstep.desktop.pick

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.model.PartTable

/**
 * The text "Copy Name(s)" puts on the clipboard (ADR-0092): one line per part, control and bidirectional characters
 * made visible, long names cut, and `#k` after a name that exists more than once. Only ever written on a user action.
 */
object CopyNames {
    const val NAME_CLIP = 256

    fun text(
        refs: List<PartRef>,
        table: PartTable,
    ): String =
        refs.take(MAX_SELECTION).joinToString("\n") { r ->
            val suffix = if (table.countOfName(r.name) > 1) " #${r.occurrence + 1}" else ""
            DisplayName.escaped(r.name, NAME_CLIP) + suffix
        }

    /** The text for a single name (a group), without an occurrence. */
    fun text(name: String): String = DisplayName.escaped(name, NAME_CLIP)
}
