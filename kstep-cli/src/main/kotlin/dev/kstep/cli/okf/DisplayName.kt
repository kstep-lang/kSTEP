package dev.kstep.cli.okf

/** A piece of a displayed name; [escaped] spans are shown dimmed as `‹U+202E›`. */
data class DisplaySpan(
    val text: String,
    val escaped: Boolean,
)

/**
 * Makes untrusted names (file names, folder paths, front-matter titles) safe to show (ADR-0058): control characters and
 * bidirectional controls are never drawn, they are replaced by a visible code point marker. Works per code point, so
 * surrogate pairs stay intact.
 */
object DisplayName {
    fun isSuspicious(cp: Int): Boolean =
        cp <= 0x1F ||
            cp == 0x7F ||
            cp in 0x80..0x9F ||
            cp == 0x061C ||
            cp == 0x200E ||
            cp == 0x200F ||
            cp == 0x2028 ||
            cp == 0x2029 ||
            cp in 0x202A..0x202E ||
            cp in 0x2066..0x2069

    fun spans(
        raw: String,
        maxChars: Int = 256,
    ): List<DisplaySpan> {
        val result = ArrayList<DisplaySpan>()
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                result += DisplaySpan(plain.toString(), escaped = false)
                plain.setLength(0)
            }
        }
        var i = 0
        while (i < raw.length) {
            if (i >= maxChars) {
                plain.append('…')
                break
            }
            val cp = raw.codePointAt(i)
            if (isSuspicious(cp)) {
                flush()
                result += DisplaySpan("‹U+${cp.toString(16).uppercase().padStart(4, '0')}›", escaped = true)
            } else {
                plain.appendCodePoint(cp)
            }
            i += Character.charCount(cp)
        }
        flush()
        return result
    }

    /** The spans joined to one plain string (for messages and window titles). */
    fun escaped(
        raw: String,
        maxChars: Int = 256,
    ): String = spans(raw, maxChars).joinToString("") { it.text }
}
