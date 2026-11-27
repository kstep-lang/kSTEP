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

        /** The regex search stopped at its step budget, so the list of matches is not complete. */
        data object Incomplete : Outcome

        /** The text is over the regex size limit. */
        data object TextTooLarge : Outcome

        data class BadTemplate(
            val group: Int,
        ) : Outcome
    }

    /** The replacement text, parsed once: a plain text, or a text with `$n` group references (regex only). */
    sealed interface ReplacementTemplate {
        data class Literal(
            val text: String,
        ) : ReplacementTemplate

        class Groups internal constructor(
            internal val parts: List<Part>,
        ) : ReplacementTemplate

        sealed interface Part

        data class Text(
            val text: String,
        ) : Part

        data class Ref(
            val group: Int,
        ) : Part
    }

    sealed interface TemplateCompile {
        data class Ok(
            val template: ReplacementTemplate,
        ) : TemplateCompile

        data class MissingGroup(
            val group: Int,
        ) : TemplateCompile
    }

    /**
     * `$0`..`$9` are the whole match and the groups, `$$` is a dollar sign, any other `$` stays as it is (`$12` is
     * group 1 followed by "2"). A literal search never interprets `$`.
     */
    fun compileTemplate(
        raw: String,
        pattern: SearchPattern,
    ): TemplateCompile {
        if (pattern !is SearchPattern.Regex) return TemplateCompile.Ok(ReplacementTemplate.Literal(raw))
        val parts = ArrayList<ReplacementTemplate.Part>()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '$' && i + 1 < raw.length) {
                val d = raw[i + 1]
                if (d == '$') {
                    sb.append('$')
                    i += 2
                    continue
                }
                if (d in '0'..'9') {
                    val n = d - '0'
                    if (n > pattern.groupCount) return TemplateCompile.MissingGroup(n)
                    if (sb.isNotEmpty()) {
                        parts += ReplacementTemplate.Text(sb.toString())
                        sb.setLength(0)
                    }
                    parts += ReplacementTemplate.Ref(n)
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        if (parts.isEmpty()) return TemplateCompile.Ok(ReplacementTemplate.Literal(sb.toString()))
        if (sb.isNotEmpty()) parts += ReplacementTemplate.Text(sb.toString())
        return TemplateCompile.Ok(ReplacementTemplate.Groups(parts))
    }

    /** The replacement for one match [start, end); [groups] are its capture slots (`null` for a literal template). */
    private fun expand(
        t: ReplacementTemplate,
        text: String,
        start: Int,
        end: Int,
        groups: IntArray?,
        offset: Int,
        sb: StringBuilder,
    ) {
        when (t) {
            is ReplacementTemplate.Literal -> sb.append(t.text)
            is ReplacementTemplate.Groups ->
                for (part in t.parts) {
                    when (part) {
                        is ReplacementTemplate.Text -> sb.append(part.text)
                        is ReplacementTemplate.Ref ->
                            if (part.group == 0) {
                                sb.append(text, start, end)
                            } else if (groups != null) {
                                val s = groups[offset + 2 * part.group]
                                val e = groups[offset + 2 * part.group + 1]
                                if (s >= 0 && e >= s) sb.append(text, s, e)
                            }
                    }
                }
        }
    }

    /** Length of the expansion of one match, computed from the spans only (nothing is allocated). */
    private fun pieceLength(
        t: ReplacementTemplate,
        start: Int,
        end: Int,
        groups: IntArray?,
        offset: Int,
    ): Long =
        when (t) {
            is ReplacementTemplate.Literal -> t.text.length.toLong()
            is ReplacementTemplate.Groups ->
                t.parts.sumOf { part ->
                    when (part) {
                        is ReplacementTemplate.Text -> part.text.length.toLong()
                        is ReplacementTemplate.Ref ->
                            if (part.group == 0) {
                                (end - start).toLong()
                            } else if (groups != null) {
                                val s = groups[offset + 2 * part.group]
                                val e = groups[offset + 2 * part.group + 1]
                                if (s >= 0 && e >= s) (e - s).toLong() else 0L
                            } else {
                                0L
                            }
                    }
                }
        }

    fun expand(
        t: ReplacementTemplate,
        text: String,
        start: Int,
        end: Int,
        groups: IntArray?,
    ): String = StringBuilder().also { expand(t, text, start, end, groups, 0, it) }.toString()

    /** Builds the result from sorted, disjoint matches; [piece] gives the text for match `i`. */
    fun replaceAll(
        text: String,
        starts: IntArray,
        ends: IntArray,
        piece: (Int) -> String,
    ): String {
        val sb = StringBuilder(text.length)
        var pos = 0
        for (i in starts.indices) {
            sb.append(text, pos, starts[i]).append(piece(i))
            pos = ends[i]
        }
        return sb.append(text, pos, text.length).toString()
    }

    /** A fresh search plus the replacement; refuses (without allocating) a result that would break the limits. */
    fun replaceAllChecked(
        text: String,
        query: String,
        matchCase: Boolean,
        replacement: String,
    ): Outcome = replaceAllChecked(text, SearchPattern.Literal(query, matchCase, false), replacement)

    fun replaceAllChecked(
        text: String,
        pattern: SearchPattern,
        replacement: String,
        shouldContinue: () -> Boolean = { true },
    ): Outcome {
        val template =
            when (val t = compileTemplate(replacement, pattern)) {
                is TemplateCompile.Ok -> t.template
                is TemplateCompile.MissingGroup -> return Outcome.BadTemplate(t.group)
            }
        val found =
            TextSearch.find(
                text,
                pattern,
                shouldContinue = shouldContinue,
                captureGroups = template is ReplacementTemplate.Groups,
            )
        when (found.limit) {
            TextSearch.Limit.TEXT_TOO_LARGE -> return Outcome.TextTooLarge
            TextSearch.Limit.STEP_BUDGET -> return Outcome.Incomplete
            TextSearch.Limit.MATCHES -> return Outcome.TooManyMatches
            TextSearch.Limit.NONE -> Unit
        }
        if (found.count == 0) return Outcome.NoMatches
        val slots = (pattern as? SearchPattern.Regex)?.compiled?.program?.slotCount ?: 0
        // Expand every piece once (bounded by the limit), counting as we go, so a huge result is refused early.
        val pieces = arrayOfNulls<String>(found.count)
        var total = text.length.toLong()
        val sb = StringBuilder()
        for (i in 0 until found.count) {
            // Size the piece from the spans first, so nothing is built once the result would exceed the limit.
            total += pieceLength(template, found.starts[i], found.ends[i], found.groups, i * slots) - found.lengthOf(i)
            if (total > EditorText.MAX_BYTES) return Outcome.TooLarge
            sb.setLength(0)
            expand(template, text, found.starts[i], found.ends[i], found.groups, i * slots, sb)
            pieces[i] = sb.toString()
        }
        val result = replaceAll(text, found.starts, found.ends) { pieces[it]!! }
        if (!withinLimits(result)) return Outcome.TooLarge
        return Outcome.Ok(result, found.starts[0] + pieces[0]!!.length, found.count)
    }

    /** Replaces the first match at or after [fromOffset] (wrapping around); the caret lands behind the replacement. */
    fun replaceOne(
        text: String,
        pattern: SearchPattern,
        replacement: String,
        fromOffset: Int,
        shouldContinue: () -> Boolean = { true },
    ): Outcome {
        val template =
            when (val t = compileTemplate(replacement, pattern)) {
                is TemplateCompile.Ok -> t.template
                is TemplateCompile.MissingGroup -> return Outcome.BadTemplate(t.group)
            }
        val found =
            TextSearch.find(
                text,
                pattern,
                shouldContinue = shouldContinue,
                captureGroups = template is ReplacementTemplate.Groups,
            )
        when (found.limit) {
            TextSearch.Limit.TEXT_TOO_LARGE -> return Outcome.TextTooLarge
            TextSearch.Limit.STEP_BUDGET -> return Outcome.Incomplete
            TextSearch.Limit.MATCHES -> return Outcome.TooManyMatches
            TextSearch.Limit.NONE -> Unit
        }
        val idx = found.firstAtOrAfter(fromOffset)
        if (idx < 0) return Outcome.NoMatches
        val slots = (pattern as? SearchPattern.Regex)?.compiled?.program?.slotCount ?: 0
        val growth =
            pieceLength(template, found.starts[idx], found.ends[idx], found.groups, idx * slots) - found.lengthOf(idx)
        if (text.length + growth > EditorText.MAX_BYTES) return Outcome.TooLarge
        val piece = expand(template, text, found.starts[idx], found.ends[idx], found.groups, idx * slots)
        val (result, caret) = replaceAt(text, found.starts[idx], found.ends[idx], piece)
        if (!withinLimits(result)) return Outcome.TooLarge
        return Outcome.Ok(result, caret, 1)
    }

    private fun expand(
        t: ReplacementTemplate,
        text: String,
        start: Int,
        end: Int,
        groups: IntArray?,
        offset: Int,
    ): String = StringBuilder().also { expand(t, text, start, end, groups, offset, it) }.toString()

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

    /** The text and the caret behind the replacement of [start, end). */
    fun replaceAt(
        text: String,
        start: Int,
        end: Int,
        replacement: String,
    ): Pair<String, Int> =
        (text.substring(0, start) + replacement + text.substring(end)) to (start + replacement.length)

    fun sanitizeReplacement(raw: String): String =
        TextSearch.clip(raw.filter { it != '\n' && it != '\r' }, MAX_REPLACEMENT_CHARS)
}
