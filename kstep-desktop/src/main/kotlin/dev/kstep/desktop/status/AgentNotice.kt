package dev.kstep.desktop.status

/** The short notices after an agent acted through the MCP server (ADR-0097). Pure. */
object AgentNotice {
    const val EDITED = "Edited by agent — Undo to revert"
    const val RUN = "Run started by agent"
    const val DURATION_MS = 4000L

    /** What the notice state becomes: the same text again still gets a new [nonce], so its timer starts anew. */
    fun nextNotice(
        current: Pair<String?, Int>,
        text: String,
    ): Pair<String?, Int> = text to current.second + 1
}
