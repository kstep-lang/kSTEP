package dev.kstep.desktop.edit

/** The two small typing aids of the editor (ADR-0052): indentation on Enter and Tab as four spaces. */
object Indent {
    const val TAB = "    "

    /** Replaces the selection by a line break plus the indentation of the current line (one level deeper after `{`). */
    fun onEnter(
        text: String,
        selStart: Int,
        selEnd: Int,
    ): Pair<String, Int> {
        val start = minOf(selStart, selEnd).coerceIn(0, text.length)
        val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        var p = lineStart
        while (p < start && (text[p] == ' ' || text[p] == '\t')) p++
        val base = text.substring(lineStart, p)
        val before = text.substring(lineStart, start).trimEnd()
        val opens = before.endsWith("{")
        val closesNext = end < text.length && text[end] == '}'
        return if (opens && closesNext) {
            val insert = "\n$base$TAB\n$base"
            text.substring(0, start) + insert + text.substring(end) to start + 1 + base.length + TAB.length
        } else {
            val insert = "\n" + base + if (opens) TAB else ""
            text.substring(0, start) + insert + text.substring(end) to start + insert.length
        }
    }

    /** Replaces the selection by four spaces. */
    fun onTab(
        text: String,
        selStart: Int,
        selEnd: Int,
    ): Pair<String, Int> {
        val start = minOf(selStart, selEnd).coerceIn(0, text.length)
        val end = maxOf(selStart, selEnd).coerceIn(0, text.length)
        return text.substring(0, start) + TAB + text.substring(end) to start + TAB.length
    }
}
