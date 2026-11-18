package dev.kstep.desktop.okf

/** Maps a line of the temporary block script to the line of the Markdown note (ADR-0058, D9). */
class BlockLineMap(
    val contentStartLine: Int,
    val contentLineCount: Int,
) {
    /** `null` for a missing or out-of-range line (nothing to jump to). */
    fun toMarkdown(scriptLine: Int?): Int? =
        if (scriptLine == null || scriptLine < 1 || scriptLine > contentLineCount) {
            null
        } else {
            contentStartLine + scriptLine - 1
        }
}
