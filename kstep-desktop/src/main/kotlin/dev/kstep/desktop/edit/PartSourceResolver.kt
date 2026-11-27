package dev.kstep.desktop.edit

/** What "Show in Script" marks (ADR-0091). `start`/`end` are buffer offsets that were cross-checked against the text. */
sealed interface PartSourceResolution {
    /** The name itself, without quotes. */
    data class Name(
        val start: Int,
        val end: Int,
    ) : PartSourceResolution

    /** The whole line of the call, trimmed; [findQuery] is the name literal for the find bar, when searchable. */
    data class Line(
        val start: Int,
        val end: Int,
        val line: Int,
        val findQuery: String?,
    ) : PartSourceResolution

    /** No usable position: the name heuristic of ADR-0068 answered with [location] (never a `Found`). */
    data class Heuristic(
        val location: SourceLocation,
    ) : PartSourceResolution
}

/**
 * Turns the script line the worker reported for a part into a selection in the buffer (ADR-0091). Pure. The line comes
 * from untrusted worker output, so every offset is checked against [text] before it is returned, and the text is only
 * ever read, never interpreted.
 */
object PartSourceResolver {
    /**
     * [positionUsable] says [sourceLine] belongs to exactly this [text]; when it does not, only the name heuristic
     * runs. [sameNameCount] is how many parts carry [name].
     */
    fun resolve(
        text: String,
        name: String,
        sourceLine: Int?,
        positionUsable: Boolean,
        sameNameCount: Int,
        budgetNanos: Long = PartSourceLocator.DEFAULT_BUDGET_NANOS,
        nanoTime: () -> Long = System::nanoTime,
    ): PartSourceResolution {
        val query = PartSourceLocator.literalQuery(name)
        if (positionUsable && sourceLine != null && sourceLine >= 1) {
            val index = LineIndex(text)
            if (sourceLine <= index.lineCount) {
                val lineStart = index.lineStart(sourceLine)
                val lineEnd = index.lineEnd(sourceLine)
                if (query != null) {
                    onLine(text, name, query, lineStart, lineEnd)?.let { return it }
                    if (sameNameCount == 1) {
                        val located = PartSourceLocator.locate(text, name, budgetNanos, nanoTime)
                        if (located is SourceLocation.Found &&
                            isNameAt(text, located.offset, located.offset + name.length, name)
                        ) {
                            return PartSourceResolution.Name(located.offset, located.offset + name.length)
                        }
                    }
                }
                return wholeLine(text, sourceLine, lineStart, lineEnd, query)
            }
        }
        return when (val located = PartSourceLocator.locate(text, name, budgetNanos, nanoTime)) {
            is SourceLocation.Found ->
                if (isNameAt(text, located.offset, located.offset + name.length, name)) {
                    PartSourceResolution.Name(located.offset, located.offset + name.length)
                } else {
                    PartSourceResolution.Heuristic(SourceLocation.NotFound)
                }
            else -> PartSourceResolution.Heuristic(located)
        }
    }

    // Exactly one literal on the line: that is the name. Two or none: undecided.
    private fun onLine(
        text: String,
        name: String,
        query: String,
        lineStart: Int,
        lineEnd: Int,
    ): PartSourceResolution.Name? {
        val first = text.indexOf(query, lineStart)
        if (first < 0 || first + query.length > lineEnd) return null
        val second = text.indexOf(query, first + 1)
        if (second >= 0 && second + query.length <= lineEnd) return null
        val start = first + 1
        val end = start + name.length
        return if (isNameAt(text, start, end, name)) PartSourceResolution.Name(start, end) else null
    }

    private fun wholeLine(
        text: String,
        line: Int,
        lineStart: Int,
        lineEnd: Int,
        query: String?,
    ): PartSourceResolution.Line {
        var start = lineStart
        var end = lineEnd
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) {
            start = lineStart
            end = lineStart
        }
        return PartSourceResolution.Line(start, end, line, query)
    }

    /** `true` when `text[start, end)` is [name] and sits between two double quotes. */
    internal fun isNameAt(
        text: String,
        start: Int,
        end: Int,
        name: String,
    ): Boolean =
        start >= 1 &&
            start <= end &&
            end < text.length &&
            end - start == name.length &&
            text[start - 1] == '"' &&
            text[end] == '"' &&
            text.regionMatches(start, name, 0, name.length)
}
