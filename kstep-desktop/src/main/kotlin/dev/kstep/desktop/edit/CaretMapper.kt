package dev.kstep.desktop.edit

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.Severity

/** Maps error positions (1-based line and column, UTF-16 units) to editor offsets and back (ADR-0052). */
object CaretMapper {
    /**
     * The offset for a [line]/[column]: `null` line means no jump (returns `null`). A line past the end clamps to the
     * last line; a `null` column means the first non-blank character of the line (the line end for a blank line); a
     * column past the line end clamps to the line end; values below 1 count as 1.
     */
    fun toOffset(
        text: CharSequence,
        line: Int?,
        column: Int?,
    ): Int? {
        if (line == null) return null
        val index = LineIndex(text)
        val l = line.coerceIn(1, index.lineCount)
        val start = index.lineStart(l)
        val end = index.lineEnd(l)
        if (column == null) {
            var p = start
            while (p < end && (text[p] == ' ' || text[p] == '\t')) p++
            return p
        }
        return minOf(start + (column.coerceAtLeast(1) - 1), end)
    }

    /** 1-based line and column of [offset]. */
    fun lineColumn(
        text: CharSequence,
        offset: Int,
    ): Pair<Int, Int> {
        val index = LineIndex(text)
        val o = offset.coerceIn(0, text.length)
        val line = index.lineOf(o)
        return line to (o - index.lineStart(line) + 1)
    }

    /** The worst severity per 1-based line for the gutter; errors past the last line or without a line are ignored. */
    fun markedLines(
        errors: List<DesktopError>,
        lineCount: Int,
    ): Map<Int, Severity> {
        val marks = HashMap<Int, Severity>()
        for (error in errors) {
            val line = error.line ?: continue
            if (line < 1 || line > lineCount) continue
            val old = marks[line]
            if (old == null ||
                (old == Severity.WARNING && error.severity == Severity.ERROR)
            ) {
                marks[line] = error.severity
            }
        }
        return marks
    }
}
