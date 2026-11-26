package dev.kstep.desktop.edit

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.ErrorBarText
import dev.kstep.desktop.run.Severity

/**
 * An error pinned to a span `[start, end)` of the editor text. [touched] is set once an edit overlapped the span: the
 * mark still points at the place but the text there is no longer the text the error was reported for (ADR-0085).
 * A span may be empty (an error on a blank line or at the very end of the text).
 */
data class Anchor(
    val error: DesktopError,
    val start: Int,
    val end: Int,
    val touched: Boolean,
)

/** What the gutter draws per 1-based line: the worst severity, whether the bar is hollow, and the tooltip. */
data class LineMarks(
    val severities: Map<Int, Severity>,
    val hollow: Set<Int>,
    val tooltips: Map<Int, String>,
)

/**
 * The error marks of the gutter, moving with the text (ADR-0085). [build] pins every error with a line to a span of
 * the text the run saw; [applyEdit] keeps the spans in step with later edits (a mark whose text is deleted disappears,
 * a mark whose text is partly changed turns hollow). Undo never brings a vanished mark back. Immutable, pure.
 */
class ErrorAnchors private constructor(
    val anchors: List<Anchor>,
    private val textLength: Int,
) {
    fun applyEdit(
        edit: TextEdit,
        newLength: Int,
    ): ErrorAnchors {
        if (newLength > MAX_TEXT_CHARS) return EMPTY
        if (anchors.isEmpty()) return ErrorAnchors(emptyList(), newLength)
        val es = edit.start
        val eo = edit.oldEnd
        val ne = edit.newEnd
        val d = edit.delta
        val moved = ArrayList<Anchor>(anchors.size)
        for (a in anchors) {
            val s = a.start
            val e = a.end
            val result =
                when {
                    // the edit swallows the whole mark (an empty mark only when it lies strictly inside)
                    (if (s == e) es < s && eo > e else es <= s && eo >= e) -> null
                    // entirely before the mark, including an insertion exactly at its start: the mark moves
                    eo <= s -> a.copy(start = s + d, end = e + d)
                    // entirely behind the mark, including an insertion exactly at its end
                    es >= e -> a
                    else -> {
                        val newStart = if (es < s) es else s
                        val newEnd = if (eo >= e) ne else e + d
                        a.copy(start = newStart, end = newEnd, touched = true)
                    }
                }
            if (result == null) continue
            val start = result.start.coerceIn(0, newLength)
            val end = result.end.coerceIn(start, newLength)
            if (a.end > a.start && end <= start) continue
            moved += result.copy(start = start, end = end)
        }
        return ErrorAnchors(moved, newLength)
    }

    /** Applies the difference between [old] and [new] as one edit. */
    fun applyDiff(
        old: CharSequence,
        new: CharSequence,
    ): ErrorAnchors {
        val edit = TextEdit.between(old, new) ?: return this
        return applyEdit(edit, new.length)
    }

    /** The per-line view for the gutter. Costs O(n) for the line index: call it when text or anchors change. */
    fun lineMarks(text: CharSequence): LineMarks {
        if (anchors.isEmpty()) return LineMarks(emptyMap(), emptySet(), emptyMap())
        val index = LineIndex(text)
        val severities = HashMap<Int, Severity>()
        val untouched = HashSet<Int>()
        val seen = HashSet<Int>()
        val byLine = LinkedHashMap<Int, MutableList<DesktopError>>()
        for (a in anchors) {
            val line = index.lineOf(a.start)
            seen += line
            if (!a.touched) untouched += line
            val old = severities[line]
            if (old == null || (old == Severity.WARNING && a.error.severity == Severity.ERROR)) {
                severities[line] = a.error.severity
            }
            byLine.getOrPut(line) { ArrayList() } += a.error
        }
        return LineMarks(
            severities,
            seen - untouched,
            byLine.mapValues { ErrorBarText.tooltip(it.value) },
        )
    }

    companion object {
        const val MAX_TEXT_CHARS = 1_000_000

        val EMPTY: ErrorAnchors = ErrorAnchors(emptyList(), 0)

        /**
         * Pins every error with a line to the text the run saw ([ranText], the saved file). A line past the last line
         * gets no anchor. With a column the span is the token or identifier there (at least one character); without a
         * column it is the line from its first non-blank character to the end without trailing blanks.
         */
        fun build(
            errors: List<DesktopError>,
            ranText: CharSequence,
            tokens: List<Token>,
        ): ErrorAnchors {
            if (ranText.length > MAX_TEXT_CHARS || errors.isEmpty()) return EMPTY
            val index = LineIndex(ranText)
            val out = ArrayList<Anchor>(errors.size)
            for (error in errors) {
                val line = error.line ?: continue
                if (line < 1 || line > index.lineCount) continue
                val start = CaretMapper.toOffset(ranText, line, error.column) ?: continue
                val lineEnd = index.lineEnd(line)
                val end =
                    if (error.column == null) {
                        var e = lineEnd
                        while (e > start && (ranText[e - 1] == ' ' || ranText[e - 1] == '\t')) e--
                        minOf(maxOf(e, start + 1), lineEnd)
                    } else {
                        minOf(maxOf(wordEnd(ranText, tokens, start), start + 1), lineEnd)
                    }
                out += Anchor(error, start, maxOf(end, start), touched = false)
            }
            return ErrorAnchors(out, ranText.length)
        }

        private fun wordEnd(
            text: CharSequence,
            tokens: List<Token>,
            start: Int,
        ): Int {
            val token = tokens.firstOrNull { start >= it.start && start < it.end }
            if (token != null) return token.end
            if (start >= text.length) return start
            var e = start
            while (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_')) e++
            return e
        }
    }
}
