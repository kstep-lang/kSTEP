package dev.kstep.desktop.pick

/** The one-line messages for a selection that was cut short (ADR-0092). */
object SelectionNotices {
    fun text(notice: SelectionNotice): String =
        when (notice) {
            SelectionNotice.LimitReached -> "Selection limit of $MAX_SELECTION parts reached"
            is SelectionNotice.Truncated -> "Selection limited to $MAX_SELECTION parts (of ${notice.total})"
        }
}
