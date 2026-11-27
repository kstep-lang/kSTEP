package dev.kstep.desktop.edit

import java.util.Locale

/** The texts of the find and go-to bars (ADR-0067). Pure and locale independent. */
object SearchBarText {
    /** The counter: empty for an empty query, `k of n`, `No matches`, or `10,000+ matches` style when truncated. */
    fun counter(
        query: String,
        result: TextSearch.Result?,
        current: Int,
    ): String {
        if (query.isEmpty()) return ""
        if (result == null) return "…"
        if (result.limit == TextSearch.Limit.TEXT_TOO_LARGE) return "File too large for regex search"
        if (result.count == 0) return "No matches"
        val n = String.format(Locale.ROOT, "%,d", result.count) + if (result.truncated) "+" else ""
        return if (current in 0 until result.count) "${current + 1} of $n" else "$n matches"
    }

    const val TEXT_CHANGED = "Text changed, try again"

    fun goToHint(lineCount: Int): String = "Line (1-${lineCount.coerceAtLeast(1)})"

    /** The message under an invalid go-to input, or `null` when the input is valid or empty. */
    fun goToMessage(result: LineJump.Result): String? =
        when (result) {
            is LineJump.Result.NotANumber -> "Please enter a whole number"
            is LineJump.Result.OutOfRange -> "Line ${result.min} to ${result.max}"
            is LineJump.Result.Valid, LineJump.Result.Empty -> null
        }

    fun replaceError(outcome: TextReplace.Outcome): String? =
        when (outcome) {
            TextReplace.Outcome.TooLarge -> "Result would be too large (1 MiB or a line over 20,000 characters)"
            TextReplace.Outcome.TooManyMatches -> "Too many matches (over 10,000)"
            TextReplace.Outcome.NoMatches -> "No matches"
            TextReplace.Outcome.Incomplete -> "Search stopped early; Replace all is not possible"
            TextReplace.Outcome.TextTooLarge -> "File too large for regex search"
            is TextReplace.Outcome.BadTemplate -> "No group ${outcome.group} in the pattern"
            is TextReplace.Outcome.Ok -> null
        }

    /**
     * A short message for a rejected pattern, or `null` for an empty one. It never repeats the pattern; an unknown
     * escape names its one character only when that is a plain printable one.
     */
    fun patternError(
        reason: SafeRegex.RejectReason,
        escapeChar: Int?,
    ): String? =
        when (reason) {
            SafeRegex.RejectReason.EMPTY -> null
            SafeRegex.RejectReason.BACKREFERENCE -> "Back-references are not supported"
            SafeRegex.RejectReason.LOOKAROUND -> "Look-around is not supported"
            SafeRegex.RejectReason.LAZY_OR_POSSESSIVE -> "Lazy and possessive quantifiers are not supported"
            SafeRegex.RejectReason.INLINE_FLAGS_OR_NAMED -> "Inline flags and named groups are not supported"
            SafeRegex.RejectReason.UNBALANCED -> "Unbalanced brackets or parentheses"
            SafeRegex.RejectReason.BAD_QUANTIFIER -> "Invalid quantifier"
            SafeRegex.RejectReason.BAD_CLASS_RANGE -> "Invalid character range"
            SafeRegex.RejectReason.TOO_COMPLEX -> "Pattern too complex"
            SafeRegex.RejectReason.BAD_ESCAPE ->
                if (escapeChar != null && escapeChar in 0x21..0x7E) {
                    "Unknown escape \\${escapeChar.toChar()}"
                } else {
                    "Unknown escape"
                }
        }

    fun findPlaceholder(regex: Boolean): String = if (regex) "Find (regex)" else "Find"

    fun replacePlaceholder(regex: Boolean): String = if (regex) "Replace with (\$0, \$1 …)" else "Replace with"

    fun replaced(count: Int): String = "$count replaced"
}
