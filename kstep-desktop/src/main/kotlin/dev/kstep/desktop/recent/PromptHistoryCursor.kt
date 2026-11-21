package dev.kstep.desktop.recent

/** Where the prompt field is in the history; `index == -1` is the user's own draft (ADR-0069). */
data class HistoryNav(
    val index: Int = -1,
    val draft: String? = null,
)

/** Up/Down navigation through the prompt history. Pure. `null` means: do nothing, the key keeps its normal meaning. */
object PromptHistoryCursor {
    fun isFirstLine(
        text: String,
        caret: Int,
    ): Boolean = text.lastIndexOf('\n', caret - 1) < 0

    fun isLastLine(
        text: String,
        caret: Int,
    ): Boolean = text.indexOf('\n', caret) < 0

    fun up(
        nav: HistoryNav,
        entries: List<String>,
        current: String,
    ): Pair<HistoryNav, String>? {
        val next = nav.index + 1
        if (next >= entries.size) return null
        val draft = if (nav.index < 0) current else nav.draft
        return HistoryNav(next, draft) to entries[next]
    }

    fun down(
        nav: HistoryNav,
        entries: List<String>,
    ): Pair<HistoryNav, String>? {
        if (nav.index < 0) return null
        val next = nav.index - 1
        return if (next < 0) {
            HistoryNav() to nav.draft.orEmpty()
        } else if (next < entries.size) {
            HistoryNav(next, nav.draft) to entries[next]
        } else {
            null
        }
    }
}
