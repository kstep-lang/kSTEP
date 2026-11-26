package dev.kstep.desktop.edit

/**
 * Frozen oracle: a verbatim copy of the tokenizer as it was before ADR-0088 (do not edit). A small, forgiving Kotlin tokenizer for syntax colouring (ADR-0052). One forward scan with an explicit stack for
 * string templates (no recursion, no regex), so it is O(n) and cannot overflow the stack on hostile input. It never
 * throws; the result is sorted by start, non-overlapping and within the text.
 *
 * Unterminated strings end at the line end (raw strings and block comments at the end of the text).
 */
object LegacyKotlinTokenizer {
    /** Template nesting beyond this depth is not recognised: the rest is plain string text. */
    const val MAX_DEPTH = 32

    private val keywords =
        setOf(
            "package",
            "import",
            "class",
            "interface",
            "object",
            "fun",
            "val",
            "var",
            "if",
            "else",
            "when",
            "for",
            "while",
            "do",
            "return",
            "break",
            "continue",
            "try",
            "catch",
            "finally",
            "throw",
            "is",
            "as",
            "in",
            "typealias",
            "this",
            "super",
            "null",
            "true",
            "false",
            "by",
            "init",
            "constructor",
            "override",
            "private",
            "public",
            "protected",
            "internal",
            "open",
            "abstract",
            "sealed",
            "data",
            "enum",
            "companion",
            "const",
            "lateinit",
            "inline",
            "suspend",
            "operator",
            "infix",
            "vararg",
            "where",
            "out",
            "get",
            "set",
        )

    fun tokenize(text: CharSequence): List<Token> {
        val n = text.length
        val out = ArrayList<Token>(maxOf(16, n / 16))
        val savedRaw = BooleanArray(MAX_DEPTH)
        val savedBraces = IntArray(MAX_DEPTH)
        var depth = 0
        var braces = 0
        var inString = false
        var raw = false
        var segStart = 0
        var i = 0

        fun emit(
            start: Int,
            end: Int,
            kind: TokenKind,
        ) {
            if (end > start) out.add(Token(start, end, kind))
        }

        while (i < n) {
            val c = text[i]
            if (inString) {
                when {
                    !raw && c == '\\' -> i = if (i + 1 < n && text[i + 1] != '\n') i + 2 else i + 1
                    c == '"' && !raw -> {
                        i++
                        emit(segStart, i, TokenKind.STRING)
                        inString = false
                    }
                    c == '"' && raw -> {
                        if (i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                            i += 3
                            while (i < n && text[i] == '"') i++
                            emit(segStart, i, TokenKind.STRING)
                            inString = false
                        } else {
                            i++
                        }
                    }
                    !raw && c == '\n' -> {
                        emit(segStart, i, TokenKind.STRING)
                        inString = false
                    }
                    c == '$' && depth < MAX_DEPTH && i + 1 < n && text[i + 1] == '{' -> {
                        emit(segStart, i, TokenKind.STRING)
                        emit(i, i + 2, TokenKind.TEMPLATE)
                        savedRaw[depth] = raw
                        savedBraces[depth] = braces
                        depth++
                        braces = 0
                        inString = false
                        i += 2
                    }
                    c == '$' && i + 1 < n && isIdentStart(text[i + 1]) -> {
                        emit(segStart, i, TokenKind.STRING)
                        var j = i + 2
                        while (j < n && isIdentPart(text[j])) j++
                        emit(i, j, TokenKind.TEMPLATE)
                        i = j
                        segStart = j
                    }
                    else -> i++
                }
                continue
            }
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    var j = i + 2
                    while (j < n && text[j] != '\n') j++
                    emit(i, j, TokenKind.COMMENT)
                    i = j
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var j = i + 2
                    var nesting = 1
                    while (j < n && nesting > 0) {
                        if (text[j] == '/' && j + 1 < n && text[j + 1] == '*') {
                            nesting++
                            j += 2
                        } else if (text[j] == '*' && j + 1 < n && text[j + 1] == '/') {
                            nesting--
                            j += 2
                        } else {
                            j++
                        }
                    }
                    emit(i, j, TokenKind.COMMENT)
                    i = j
                }
                c == '"' -> {
                    segStart = i
                    raw = i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"'
                    i += if (raw) 3 else 1
                    inString = true
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != '\n') {
                        if (text[j] == '\\' && j + 1 < n && text[j + 1] != '\n') {
                            j += 2
                        } else if (text[j] == '\'') {
                            j++
                            break
                        } else {
                            j++
                        }
                    }
                    emit(i, j, TokenKind.STRING)
                    i = j
                }
                c == '`' -> {
                    var j = i + 1
                    while (j < n && text[j] != '`' && text[j] != '\n') j++
                    i = if (j < n && text[j] == '`') j + 1 else j
                }
                c == '@' -> {
                    var j = i + 1
                    while (j < n && isIdentPart(text[j])) j++
                    if (j < n && text[j] == ':' && j + 1 < n && isIdentStart(text[j + 1])) {
                        j++
                        while (j < n && isIdentPart(text[j])) j++
                    }
                    emit(i, j, TokenKind.ANNOTATION)
                    i = maxOf(j, i + 1)
                }
                c in '0'..'9' -> i = scanNumber(text, i, ::emit)
                isIdentStart(c) -> {
                    var j = i + 1
                    while (j < n && isIdentPart(text[j])) j++
                    val word = text.subSequence(i, j).toString()
                    when {
                        word in KStepDslNames.names -> emit(i, j, TokenKind.DSL)
                        word in keywords -> emit(i, j, TokenKind.KEYWORD)
                    }
                    i = j
                }
                c == '{' -> {
                    if (depth > 0) braces++
                    i++
                }
                c == '}' -> {
                    if (depth > 0 && braces == 0) {
                        emit(i, i + 1, TokenKind.TEMPLATE)
                        depth--
                        raw = savedRaw[depth]
                        braces = savedBraces[depth]
                        inString = true
                        i++
                        segStart = i
                    } else {
                        if (depth > 0) braces--
                        i++
                    }
                }
                else -> i++
            }
        }
        if (inString) emit(segStart, n, TokenKind.STRING)
        return out
    }

    private fun isIdentStart(c: Char): Boolean = c == '_' || Character.isLetter(c)

    private fun isIdentPart(c: Char): Boolean = c == '_' || Character.isLetterOrDigit(c)

    private fun scanNumber(
        text: CharSequence,
        start: Int,
        emit: (Int, Int, TokenKind) -> Unit,
    ): Int {
        val n = text.length
        var j = start
        val prefixed =
            text[j] == '0' &&
                j + 1 < n &&
                (text[j + 1] == 'x' || text[j + 1] == 'X' || text[j + 1] == 'b' || text[j + 1] == 'B')
        if (prefixed) {
            j += 2
            while (j < n && (text[j].isLetterOrDigit() && text[j].code < 128 || text[j] == '_')) j++
        } else {
            while (j < n && (text[j] in '0'..'9' || text[j] == '_')) j++
            if (j + 1 < n && text[j] == '.' && text[j + 1] in '0'..'9') {
                j++
                while (j < n && (text[j] in '0'..'9' || text[j] == '_')) j++
            }
            if (j < n && (text[j] == 'e' || text[j] == 'E')) {
                var k = j + 1
                if (k < n && (text[k] == '+' || text[k] == '-')) k++
                if (k < n && text[k] in '0'..'9') {
                    while (k < n && text[k] in '0'..'9') k++
                    j = k
                }
            }
            var suffixes = 0
            while (j < n && suffixes < 2 && text[j] in "uUlLfF") {
                j++
                suffixes++
            }
        }
        // A number glued to letters is not a number (`1abc`): swallow the rest of the identifier unhighlighted.
        var end = j
        while (end < n && (text[end] == '_' || Character.isLetterOrDigit(text[end]))) end++
        if (end == j) emit(start, j, TokenKind.NUMBER)
        return end
    }
}
