package dev.kstep.desktop.mcp

import dev.kstep.desktop.edit.EditorText
import java.security.MessageDigest

/**
 * Pure rules for text an agent sends (ADR-0097). Agent text is never trusted: it is checked here before it can reach
 * the editor, and it is never sent back to the agent, only its hash and counts.
 */
internal object McpAgentText {
    /** The result of [validate]. */
    sealed interface Validation {
        /** [text] has `\n` line endings only. */
        class Ok(
            val text: String,
        ) : Validation

        data object TooLarge : Validation

        data object Invalid : Validation
    }

    /** How often a literal occurs: at most [maxCount] are counted, [first] is the offset of the first one or -1. */
    class LiteralHits(
        val count: Int,
        val capped: Boolean,
        val first: Int,
    )

    private const val PREFIX = "sha256:"
    private const val HEX = "0123456789abcdef"
    private const val DIGEST_HEX_CHARS = 64

    /** `sha256:` and the lower-case hex of the SHA-256 over the UTF-8 bytes; the value `get_script` reports. */
    fun revisionOf(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(PREFIX.length + DIGEST_HEX_CHARS)
        sb.append(PREFIX)
        for (b in digest) {
            sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    /** Exactly `sha256:` and 64 lower-case hex digits; checked without a pattern. */
    fun isRevision(s: String): Boolean {
        if (s.length != PREFIX.length + DIGEST_HEX_CHARS || !s.startsWith(PREFIX)) return false
        for (i in PREFIX.length until s.length) if (HEX.indexOf(s[i]) < 0) return false
        return true
    }

    /**
     * Checks [text]: at most 1 MiB of UTF-8, no lone surrogate, no control character except tab, line feed and carriage
     * return, no DEL, C1, byte order mark, noncharacter, line or paragraph separator and no bidirectional control
     * (a "Trojan Source" guard); lines of at most 20,000 characters. `\r\n` and `\r` become `\n`.
     */
    fun validate(
        text: String,
        checkChars: Boolean = true,
    ): Validation {
        var bytes = 0L
        val sb = StringBuilder(text.length)
        var lineLength = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val cp: Int
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return Validation.Invalid
                cp = Character.toCodePoint(c, text[i + 1])
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return Validation.Invalid
            } else {
                cp = c.code
                i++
            }
            bytes +=
                when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
            if (bytes > EditorText.MAX_BYTES) return Validation.TooLarge
            if (cp == '\r'.code) {
                if (i < text.length && text[i] == '\n') i++
                sb.append('\n')
                lineLength = 0
                continue
            }
            if (cp == '\n'.code) {
                sb.append('\n')
                lineLength = 0
                continue
            }
            if (checkChars && forbidden(cp)) return Validation.Invalid
            sb.appendCodePoint(cp)
            lineLength += Character.charCount(cp)
            if (lineLength > EditorText.MAX_LINE_CHARS) return Validation.Invalid
        }
        return Validation.Ok(sb.toString())
    }

    private fun forbidden(cp: Int): Boolean =
        when {
            cp == '\t'.code -> false
            cp < 0x20 || cp == 0x7F || cp in 0x80..0x9F -> true
            cp == 0xFEFF || cp == 0xFFFE || cp == 0xFFFF -> true
            cp == 0x2028 || cp == 0x2029 -> true
            cp in 0x202A..0x202E || cp in 0x2066..0x2069 -> true
            cp == 0x200E || cp == 0x200F || cp == 0x061C -> true
            else -> false
        }

    /**
     * Counts the occurrences of [needle] in [text] (overlapping ones included) with the Knuth-Morris-Pratt method in
     * O(n + m): a hostile needle cannot make the search slow. Stops after [maxCount] hits.
     */
    fun findLiteral(
        text: String,
        needle: String,
        maxCount: Int = DEFAULT_MAX_HITS,
    ): LiteralHits {
        val m = needle.length
        if (m == 0 || m > text.length) return LiteralHits(0, false, -1)
        val fail = IntArray(m)
        var k = 0
        for (q in 1 until m) {
            while (k > 0 && needle[q] != needle[k]) k = fail[k - 1]
            if (needle[q] == needle[k]) k++
            fail[q] = k
        }
        var count = 0
        var first = -1
        k = 0
        for (i in text.indices) {
            while (k > 0 && text[i] != needle[k]) k = fail[k - 1]
            if (text[i] == needle[k]) k++
            if (k == m) {
                if (first < 0) first = i - m + 1
                count++
                if (count >= maxCount) return LiteralHits(count, true, first)
                k = fail[k - 1]
            }
        }
        return LiteralHits(count, false, first)
    }

    const val DEFAULT_MAX_HITS = 1000
}
