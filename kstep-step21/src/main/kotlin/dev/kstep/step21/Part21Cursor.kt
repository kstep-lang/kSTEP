package dev.kstep.step21

/**
 * Minimal char-scanning cursor over a Part-21 source string, tracking `line`/`col` purely for
 * error messages. No backtracking is ever needed for this fixed grammar, so a single forward
 * position suffices — deliberately not a token stream/lexer-generator (see [Part21Tokenizer]'s
 * KDoc for why).
 */
internal class Part21Cursor(
    private val source: String,
) {
    var pos: Int = 0
        private set
    var line: Int = 1
        private set
    var col: Int = 1
        private set

    fun isAtEnd(): Boolean = pos >= source.length

    fun peek(): Char? = if (isAtEnd()) null else source[pos]

    fun advance(): Char {
        val c = source[pos]
        pos++
        if (c == '\n') {
            line++
            col = 1
        } else {
            col++
        }
        return c
    }

    fun skipWhitespace() {
        while (!isAtEnd() && peek()!!.isWhitespace()) {
            advance()
        }
    }

    /** Skips leading whitespace, then matches [literal] character-for-character (no internal whitespace tolerance). */
    fun expectLiteral(literal: String) {
        skipWhitespace()
        for (expected in literal) {
            val actual = peek()
            if (actual != expected) {
                throw Part21SyntaxException(
                    "expected '$literal' at ${currentPosition()}" +
                        if (actual == null) " but reached end of input" else " but found '$actual'",
                )
            }
            advance()
        }
    }

    fun currentPosition(): String = "line $line:$col"
}
