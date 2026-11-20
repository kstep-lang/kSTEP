package dev.kstep.desktop.edit

/** Replace operations of the editor (ADR-0067). Every result is checked against the limits of [EditorText]. */
object TextReplace {
    const val MAX_REPLACEMENT_CHARS = 4096

    sealed interface Outcome {
        data class Ok(
            val text: String,
            val caret: Int,
            val count: Int,
        ) : Outcome

        data object NoMatches : Outcome

        data object TooManyMatches : Outcome

        data object TooLarge : Outcome
    }

    fun replaceAll(
        text: String,
        matches: IntArray,
        matchLength: Int,
        replacement: String,
    ): String {
        val sb = StringBuilder(text.length + matches.size * (replacement.length - matchLength).coerceAtLeast(0))
        var pos = 0
        for (m in matches) {
            sb.append(text, pos, m).append(replacement)
            pos = m + matchLength
        }
        return sb.append(text, pos, text.length).toString()
    }

    /** A fresh search plus the replacement; refuses (without allocating) a result that would break the limits. */
    fun replaceAllChecked(
        text: String,
        query: String,
        matchCase: Boolean,
        replacement: String,
    ): Outcome {
        val found = TextSearch.find(text, query, matchCase)
        if (found.truncated) return Outcome.TooManyMatches
        if (found.count == 0) return Outcome.NoMatches
        val predicted = text.length.toLong() + found.count.toLong() * (replacement.length - found.length)
        if (predicted > EditorText.MAX_BYTES) return Outcome.TooLarge
        val result = replaceAll(text, found.starts, found.length, replacement)
        if (!withinLimits(result)) return Outcome.TooLarge
        return Outcome.Ok(result, found.starts[0] + replacement.length, found.count)
    }

    /** UTF-8 size and longest line of [text] against [EditorText.MAX_BYTES] and [EditorText.MAX_LINE_CHARS]. */
    fun withinLimits(text: String): Boolean {
        var bytes = 0L
        var line = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes +=
                when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                        i++
                        line++
                        4
                    }
                    else -> 3
                }
            if (bytes > EditorText.MAX_BYTES) return false
            if (c == '\n') {
                line = 0
            } else if (++line > EditorText.MAX_LINE_CHARS) {
                return false
            }
            i++
        }
        return true
    }

    /** The text and the caret behind the replacement. */
    fun replaceAt(
        text: String,
        start: Int,
        length: Int,
        replacement: String,
    ): Pair<String, Int> =
        (text.substring(0, start) + replacement + text.substring(start + length)) to (start + replacement.length)

    fun sanitizeReplacement(raw: String): String =
        TextSearch.clip(raw.filter { it != '\n' && it != '\r' }, MAX_REPLACEMENT_CHARS)
}
