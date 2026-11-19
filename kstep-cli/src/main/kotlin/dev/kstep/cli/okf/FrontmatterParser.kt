package dev.kstep.cli.okf

/**
 * What the knowledge workspace reads of a note's YAML header (ADR-0058, D12). [ignored] is set when a header opens but
 * cannot be read safely: the whole text is then treated as Markdown ([bodyStartLine] = 1).
 */
data class Frontmatter(
    val title: String?,
    val tags: List<String>,
    val ignored: Boolean,
    val bodyStartLine: Int,
)

/**
 * A hand-written reader for the two keys that matter (`title`, `tags`) -- no YAML framework, so there is no anchor,
 * alias, tag or merge-key resolution at all (the "billion laughs" class of attacks cannot happen). Everything outside
 * the small safe subset makes the header "ignored" instead of being interpreted.
 */
object FrontmatterParser {
    private class Bail : RuntimeException(null, null, false, false)

    private val none = Frontmatter(null, emptyList(), ignored = false, bodyStartLine = 1)
    private val ignoredResult = Frontmatter(null, emptyList(), ignored = true, bodyStartLine = 1)

    fun parse(text: String): Frontmatter {
        val n = text.length
        var pos = if (n > 0 && text[0] == '\uFEFF') 1 else 0
        val limit = minOf(n, pos + OkfLimits.FM_MAX_BYTES)
        val first = lineEnd(text, pos, limit)
        if (!isMarker(text, pos, first.end, "---")) return none
        pos = first.next
        var lineNo = 1
        val body = ArrayList<String>()
        while (pos < n) {
            val line = lineEnd(text, pos, limit)
            lineNo++
            if (lineNo > OkfLimits.FM_MAX_LINES) return ignoredResult
            // a line that runs into the size limit without a terminator is cut: the header is too large
            if (!line.terminated && limit < n) return ignoredResult
            if (isMarker(text, pos, line.end, "---") || isMarker(text, pos, line.end, "...")) {
                return read(body, lineNo + 1)
            }
            body += text.substring(pos, line.end)
            pos = line.next
        }
        return ignoredResult
    }

    private class LineEnd(
        val end: Int,
        val next: Int,
        val terminated: Boolean,
    )

    private fun lineEnd(
        text: String,
        from: Int,
        limit: Int,
    ): LineEnd {
        var i = from
        while (i < limit && text[i] != '\n' && text[i] != '\r') i++
        if (i >= limit) return LineEnd(i, i, false)
        val next = if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
        return LineEnd(i, next, true)
    }

    private fun isMarker(
        text: String,
        from: Int,
        end: Int,
        marker: String,
    ): Boolean {
        if (!text.startsWith(marker, from)) return false
        for (k in from + marker.length until end) if (text[k] != ' ' && text[k] != '\t') return false
        return from + marker.length <= end
    }

    private fun read(
        lines: List<String>,
        bodyStartLine: Int,
    ): Frontmatter =
        try {
            doRead(lines, bodyStartLine)
        } catch (_: Bail) {
            ignoredResult
        }

    private fun doRead(
        lines: List<String>,
        bodyStartLine: Int,
    ): Frontmatter {
        var title: String? = null
        val tags = ArrayList<String>()
        var keys = 0
        var key: String? = null
        val indents = HashSet<Int>()
        for (raw in lines) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart(' ', '\t').length
            if (indent > 0) {
                val k = key ?: throw Bail()
                indents += indent
                if (indents.size > 1) throw Bail() // nesting deeper than two levels
                val item = raw.trim()
                if (item == "-" || item.startsWith("- ")) {
                    val value = item.removePrefix("-").trim()
                    checkValue(value)
                    if (value.startsWith("-") || value.startsWith("[")) throw Bail() // nested list
                    if (k == "tags") tags += scalar(value)
                } else if (k == "tags") {
                    throw Bail()
                } else {
                    checkValue(item.substringAfter(':', ""))
                }
                continue
            }
            indents.clear()
            val colon = raw.indexOf(':')
            if (colon <= 0) throw Bail()
            val name = raw.substring(0, colon).trim()
            if (name.isEmpty() ||
                name == "<<" ||
                name.startsWith("&") ||
                name.startsWith("*") ||
                name.startsWith("!")
            ) {
                throw Bail()
            }
            if (++keys > OkfLimits.FM_MAX_KEYS) throw Bail()
            key = name
            val value = raw.substring(colon + 1).trim()
            checkValue(value)
            when (name) {
                "title" -> if (value.isNotEmpty()) title = scalar(value).ifBlank { null }
                "tags" ->
                    if (value.startsWith("[")) {
                        tags += flowList(value)
                    } else if (value.isNotEmpty()) {
                        tags +=
                            scalar(value)
                    }
            }
            if (tags.size > OkfLimits.FM_MAX_TAGS) throw Bail()
        }
        if (tags.size > OkfLimits.FM_MAX_TAGS) throw Bail()
        return Frontmatter(title, tags, ignored = false, bodyStartLine = bodyStartLine)
    }

    /** Values that would need anchors, aliases, tags, merge keys, block scalars or maps: not supported, never resolved. */
    private fun checkValue(value: String) {
        if (value.length > OkfLimits.FM_MAX_SCALAR * 4) throw Bail()
        if (value.isEmpty()) return
        val c = value[0]
        if (c == '&' || c == '*' || c == '!' || c == '|' || c == '>' || c == '{') throw Bail()
        if (value.startsWith("<<")) throw Bail()
    }

    private fun flowList(value: String): List<String> {
        if (!value.endsWith("]")) throw Bail()
        val inner = value.substring(1, value.length - 1)
        val items = ArrayList<String>()
        var i = 0
        val sb = StringBuilder()
        var quote = 0.toChar()
        while (i < inner.length) {
            val c = inner[i]
            if (quote != 0.toChar()) {
                sb.append(c)
                if (c == '\\' && quote == '"' && i + 1 < inner.length) {
                    sb.append(inner[++i])
                } else if (c == quote) {
                    quote = 0.toChar()
                }
            } else if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
            } else if (c == '[' || c == '{') {
                throw Bail()
            } else if (c == ',') {
                items += flowItem(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        if (quote != 0.toChar()) throw Bail()
        if (sb.isNotBlank()) items += flowItem(sb.toString())
        return items
    }

    private fun flowItem(raw: String): String {
        val item = raw.trim()
        checkValue(item)
        return scalar(item)
    }

    /** Plain, `"double"` (escapes `\"` and `\\`) or `'single'` (escape `''`); a trailing ` #comment` of a plain scalar is dropped. */
    private fun scalar(value: String): String {
        val v = value.trim()
        val result =
            when {
                v.startsWith("\"") -> quoted(v, '"')
                v.startsWith("'") -> quoted(v, '\'')
                else -> {
                    val hash = v.indexOf(" #")
                    (if (hash >= 0) v.substring(0, hash) else v).trim()
                }
            }
        if (result.length > OkfLimits.FM_MAX_SCALAR) throw Bail()
        return result
    }

    private fun quoted(
        v: String,
        q: Char,
    ): String {
        val sb = StringBuilder()
        var i = 1
        while (i < v.length) {
            val c = v[i]
            if (q == '"' && c == '\\' && i + 1 < v.length && (v[i + 1] == '"' || v[i + 1] == '\\')) {
                sb.append(v[i + 1])
                i += 2
                continue
            }
            if (q == '\'' && c == '\'' && i + 1 < v.length && v[i + 1] == '\'') {
                sb.append('\'')
                i += 2
                continue
            }
            if (c == q) return sb.toString()
            sb.append(c)
            i++
        }
        throw Bail() // unterminated quote
    }
}
