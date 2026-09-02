package dev.kstep.render.svg

/**
 * Escapes plain text for safe embedding inside an SVG `<text>` element (or any other XML
 * character-data context this module writes into). `internal`, not private, so this module's own
 * test source set can exercise it directly (default main/test associated compilation) without
 * going through a full [TriangleSvgWriter]/`TextCardRenderer` render.
 *
 * Every string this module interpolates into generated SVG -- file names, entity/violation
 * text, exception messages -- MUST go through this function first. None of that text is ever the
 * `*.kstep.kts` script's own source code (see
 * docs/adr/ADR-0011-headless-preview-rendering.adoc's Security section), but it can still contain
 * attacker-controlled substrings (a script's own `name`/`description` string values), so this is
 * a real injection boundary, not just XML hygiene.
 */
internal object SvgEscaping {
    /**
     * Escapes `& < > " '`, strips ASCII control characters (except tab/newline/carriage-return,
     * which callers of this module never emit raw anyway -- see [TextCardRenderer]/
     * [TriangleSvgWriter]), and replaces any remaining non-ASCII character with `?` -- kSTEP's
     * generated previews are deliberately ASCII-only throughout (mirrors
     * `Part21EncodingException`'s own encoding stance), so a stray non-ASCII value degrades
     * predictably instead of producing an encoding surprise downstream.
     */
    fun escape(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\'' -> sb.append("&apos;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
                ch.code < 0x20 || ch.code == 0x7F -> {
                    // other ASCII control characters -- drop silently
                }
                ch.code > 0x7E -> sb.append('?')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
