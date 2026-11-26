package dev.kstep.desktop.recent

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes

/** The texts of the Recent menu and its errors (ADR-0066). Every name goes through [DisplayName]: no raw control or direction character. */
object RecentMessages {
    private const val NAME_MAX = 80
    private const val PARENT_MAX = 60
    private const val MESSAGE_PATH_MAX = 120

    /** A menu line: the [name] (folders with a trailing `/`) and the shortened [parent] folder, both escaped. */
    data class Label(
        val name: String,
        val parent: String,
    )

    fun label(entry: RecentEntry): Label {
        val rawName = entry.path.fileName?.toString() ?: entry.path.toString()
        val name = DisplayName.escaped(rawName, NAME_MAX) + if (entry.kind == RecentKind.FOLDER) "/" else ""
        val parent =
            entry.path.parent
                ?.toString()
                .orEmpty()
        return Label(name, shortenLeft(DisplayName.escaped(parent, 4096), PARENT_MAX))
    }

    /** Cut from the left: `…` plus the tail; a `‹U+xxxx›` marker is never cut in half. */
    internal fun shortenLeft(
        text: String,
        maxChars: Int,
    ): String {
        if (text.length <= maxChars) return text
        var start = text.length - (maxChars - 1)
        val open = text.lastIndexOf('‹', start)
        val close = text.indexOf('›', open.coerceAtLeast(0))
        if (open >= 0 && open < start && close >= start) start = close + 1
        if (start in 1 until text.length && Character.isLowSurrogate(text[start])) start++
        return "…" + text.substring(start.coerceAtMost(text.length))
    }

    fun missing(entry: RecentEntry): DesktopError = error(entry, "is no longer available and was removed from Recent.")

    fun link(entry: RecentEntry): DesktopError = error(entry, "is a link and was removed from Recent.")

    fun wrongType(entry: RecentEntry): DesktopError =
        error(
            entry,
            "is no longer a ${
                when (entry.kind) {
                    RecentKind.FOLDER -> "folder"
                    RecentKind.NOTE -> "note"
                    RecentKind.SCRIPT -> "script"
                }
            } and was removed from Recent.",
        )

    fun forCheck(
        entry: RecentEntry,
        check: RecentCheck,
    ): DesktopError? =
        when (check) {
            is RecentCheck.Ok -> null
            RecentCheck.Missing -> missing(entry)
            RecentCheck.Link -> link(entry)
            RecentCheck.WrongType -> wrongType(entry)
        }

    private fun error(
        entry: RecentEntry,
        what: String,
    ) = DesktopError(
        DesktopErrorCodes.RECENT_UNAVAILABLE,
        null,
        null,
        "${DisplayName.escaped(entry.path.toString(), MESSAGE_PATH_MAX)} $what",
    )
}
