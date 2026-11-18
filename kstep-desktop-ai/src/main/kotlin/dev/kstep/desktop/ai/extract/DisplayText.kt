package dev.kstep.desktop.ai.extract

/** Character rules shared by the extractor, the error summary and everything shown in the UI (ADR-0056). */
object DisplayText {
    private fun isBidi(c: Char): Boolean =
        c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F'

    private fun isForbiddenControl(c: Char): Boolean =
        (c < ' ' && c != '\n' && c != '\t') || c == '\u007F' || c in '\u0080'..'\u009F'

    /** True if [s] has a control character (other than `\n`, `\t`) or a bidirectional control (Trojan Source). */
    fun hasForbidden(s: String): Boolean = s.any { isForbiddenControl(it) || isBidi(it) }

    /** [s] without control and bidi characters (`\n` and `\t` stay); applied before anything is shown. */
    fun sanitize(s: String): String = buildString { for (c in s) if (!isForbiddenControl(c) && !isBidi(c)) append(c) }
}
