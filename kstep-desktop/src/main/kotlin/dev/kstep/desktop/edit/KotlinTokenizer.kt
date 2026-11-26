package dev.kstep.desktop.edit

/**
 * A small, forgiving Kotlin tokenizer for syntax colouring (ADR-0052). One forward scan with an explicit stack for
 * string templates (no recursion, no regex), so it is O(n) and cannot overflow the stack on hostile input. It never
 * throws; the result is sorted by start, non-overlapping and within the text.
 *
 * Unterminated strings end at the line end (raw strings and block comments at the end of the text). The scan itself is
 * a resumable state machine in [TokenScanner] so that [IncrementalTokenizer] can restart it at a line start (ADR-0088).
 */
object KotlinTokenizer {
    /** Template nesting beyond this depth is not recognised: the rest is plain string text. */
    const val MAX_TEMPLATE_DEPTH = 32

    internal val keywords =
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
        val out = ArrayList<Token>(maxOf(16, text.length / 16))
        TokenScanner(text).run(0, ScanState(), out, null)
        return out
    }

    internal fun isIdentStart(c: Char): Boolean = c == '_' || Character.isLetter(c)

    internal fun isIdentPart(c: Char): Boolean = c == '_' || Character.isLetterOrDigit(c)

    internal fun scanNumber(
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
