package dev.kstep.desktop.edit

/** The result of a bracket search (ADR-0085). Offsets are UTF-16 offsets of the bracket characters. */
sealed interface BracketMatch {
    /** The bracket next to the caret and its partner. */
    data class Pair(
        val open: Int,
        val close: Int,
    ) : BracketMatch

    /** The bracket next to the caret has no partner, or the next bracket of the other kind comes first. */
    data class Unmatched(
        val at: Int,
    ) : BracketMatch
}

/**
 * Finds the partner of the bracket next to the caret. Brackets inside strings, templates and comments (as the
 * tokenizer sees them) are ignored; the `${`/`}` of a string template are template tokens and are never paired, code
 * inside `${ }` is normal code and counts. The search is bounded: more than [MAX_TEXT_CHARS] characters of text give no
 * answer, and so does a partner farther away than [MAX_SCAN_CHARS] in one direction (neither "matched" nor "unmatched"
 * is known then). Pure; the caller decides when to ask (only for an empty selection).
 */
object BracketMatcher {
    const val MAX_SCAN_CHARS = 100_000
    const val MAX_TEXT_CHARS = 1_000_000

    fun match(
        text: CharSequence,
        tokens: List<Token>,
        caret: Int,
    ): BracketMatch? {
        if (text.length > MAX_TEXT_CHARS) return null
        val candidate =
            listOf(caret - 1, caret).firstOrNull {
                it in 0 until text.length && isBracket(text[it]) && excludedToken(tokens, it) == null
            } ?: return null
        val ch = text[candidate]
        return if (isOpener(ch)) forward(text, tokens, candidate) else backward(text, tokens, candidate)
    }

    private fun forward(
        text: CharSequence,
        tokens: List<Token>,
        at: Int,
    ): BracketMatch? {
        val stack = StringBuilder().append(closerOf(text[at]))
        val limit = minOf(text.length.toLong(), at + 1L + MAX_SCAN_CHARS).toInt()
        var i = at + 1
        while (i < limit) {
            val c = text[i]
            if (isBracket(c)) {
                val end = excludedEnd(tokens, i)
                if (end >= 0) {
                    i = maxOf(end, i + 1)
                    continue
                }
                if (isOpener(c)) {
                    stack.append(closerOf(c))
                } else {
                    if (stack[stack.length - 1] != c) return BracketMatch.Unmatched(at)
                    stack.setLength(stack.length - 1)
                    if (stack.isEmpty()) return BracketMatch.Pair(at, i)
                }
            }
            i++
        }
        return if (limit == text.length) BracketMatch.Unmatched(at) else null
    }

    private fun backward(
        text: CharSequence,
        tokens: List<Token>,
        at: Int,
    ): BracketMatch? {
        val stack = StringBuilder().append(openerOf(text[at]))
        val limit = maxOf(0L, at - 1L - MAX_SCAN_CHARS + 1L).toInt()
        var i = at - 1
        while (i >= limit) {
            val c = text[i]
            if (isBracket(c)) {
                val start = excludedStart(tokens, i)
                if (start >= 0) {
                    i = minOf(start, i) - 1
                    continue
                }
                if (!isOpener(c)) {
                    stack.append(openerOf(c))
                } else {
                    if (stack[stack.length - 1] != c) return BracketMatch.Unmatched(at)
                    stack.setLength(stack.length - 1)
                    if (stack.isEmpty()) return BracketMatch.Pair(i, at)
                }
            }
            i--
        }
        return if (limit == 0) BracketMatch.Unmatched(at) else null
    }

    private fun isOpener(c: Char) = c == '(' || c == '[' || c == '{'

    private fun isBracket(c: Char) = isOpener(c) || c == ')' || c == ']' || c == '}'

    private fun closerOf(c: Char) =
        when (c) {
            '(' -> ')'
            '[' -> ']'
            else -> '}'
        }

    private fun openerOf(c: Char) =
        when (c) {
            ')' -> '('
            ']' -> '['
            else -> '{'
        }

    private fun excludedKind(kind: TokenKind) =
        kind == TokenKind.STRING || kind == TokenKind.TEMPLATE || kind == TokenKind.COMMENT

    /** The token that contains [pos] and hides brackets, or `null`. Tokens are sorted by start. */
    private fun excludedToken(
        tokens: List<Token>,
        pos: Int,
    ): Token? {
        var lo = 0
        var hi = tokens.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tokens[mid].start <= pos) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        // overlapping tokens would be a tokenizer bug; look a few back to stay correct for nested spans
        var k = found
        var steps = 0
        while (k >= 0 && steps < 4) {
            val t = tokens[k]
            if (pos < t.end && excludedKind(t.kind)) return t
            k--
            steps++
        }
        return null
    }

    /** End offset of the excluded span at [pos], or -1 when [pos] is ordinary code. */
    private fun excludedEnd(
        tokens: List<Token>,
        pos: Int,
    ): Int = excludedToken(tokens, pos)?.end ?: -1

    /** Start offset of the excluded span at [pos], or -1 when [pos] is ordinary code. */
    private fun excludedStart(
        tokens: List<Token>,
        pos: Int,
    ): Int = excludedToken(tokens, pos)?.start ?: -1
}
