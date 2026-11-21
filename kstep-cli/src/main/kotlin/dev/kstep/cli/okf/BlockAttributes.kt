package dev.kstep.cli.okf

/**
 * The optional attribute list of a ```` ```kstep {name=bracket} ```` info string (ADR-0071). Hand-written single pass,
 * no regular expression, never looks further than [OkfLimits.MAX_ATTR_CHARS] characters behind the opening brace.
 * Unknown keys (and bare tokens such as `.numberLines`) are collected, not rejected: old notes keep working.
 */
internal object BlockAttributes {
    class Parsed(
        val name: String?,
        val malformed: Boolean,
        val invalidValue: String?,
        val unknownKeys: List<String>,
        val unterminated: Boolean,
    )

    val NONE = Parsed(null, false, null, emptyList(), false)

    private const val MAX_SHOWN_VALUE = 64

    fun isValidName(s: String): Boolean {
        if (s.isEmpty() || s.length > OkfLimits.MAX_NAME_CHARS) return false
        if (!isAlnum(s[0])) return false
        for (c in s) if (!isAlnum(c) && c != '.' && c != '_' && c != '-') return false
        return !s.contains("..") && !s.endsWith(".")
    }

    private fun isAlnum(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun isBlankChar(c: Char): Boolean = c == ' ' || c == '\t'

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun parse(
        text: String,
        from: Int,
        lineEnd: Int,
    ): Parsed {
        var p = from
        while (p < lineEnd && isBlankChar(text[p])) p++
        if (p >= lineEnd || text[p] != '{') return NONE
        val limit = minOf(lineEnd.toLong(), p.toLong() + 1 + OkfLimits.MAX_ATTR_CHARS).toInt()
        var i = p + 1
        var name: String? = null
        var nameSeen = false
        var malformed = false
        var invalid: String? = null
        var closed = false
        val unknown = ArrayList<String>()

        fun addUnknown(k: String) {
            if (unknown.size < OkfLimits.MAX_UNKNOWN_KEYS) unknown += k.take(OkfLimits.MAX_UNKNOWN_KEY_CHARS)
        }
        while (true) {
            while (i < limit && (isBlankChar(text[i]) || text[i] == ',')) i++
            if (i >= limit) break
            if (text[i] == '}') {
                closed = true
                break
            }
            val keyStart = i
            while (i < limit && !isBlankChar(text[i]) && text[i] != ',' && text[i] != '=' && text[i] != '}') i++
            val key = text.substring(keyStart, i)
            var j = i
            while (j < limit && isBlankChar(text[j])) j++
            var value: String? = null
            var quoteOpen = false
            if (j < limit && text[j] == '=') {
                j++
                while (j < limit && isBlankChar(text[j])) j++
                if (j < limit && text[j] == '"') {
                    val vs = j + 1
                    var k = vs
                    while (k < limit && text[k] != '"') k++
                    if (k >= limit) {
                        quoteOpen = true
                        i = limit
                    } else {
                        value = text.substring(vs, k)
                        i = k + 1
                    }
                } else {
                    val vs = j
                    var k = j
                    while (k < limit && !isBlankChar(text[k]) && text[k] != ',' && text[k] != '}') k++
                    value = text.substring(vs, k)
                    i = k
                }
            }
            if (key == "name") {
                when {
                    nameSeen -> malformed = true
                    quoteOpen || value == null || value.isEmpty() -> {
                        nameSeen = true
                        malformed = true
                    }
                    else -> {
                        nameSeen = true
                        if (isValidName(value)) name = value else invalid = value.take(MAX_SHOWN_VALUE)
                    }
                }
            } else if (!quoteOpen) {
                addUnknown(key)
            }
        }
        val unterminated = !closed
        if (unterminated && nameSeen) malformed = true
        if (malformed) {
            name = null
            invalid = null
        }
        if (invalid != null) name = null
        return Parsed(name, malformed, invalid, unknown, unterminated)
    }
}
