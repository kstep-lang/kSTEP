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
        if (result.count == 0) return "No matches"
        val n = String.format(Locale.ROOT, "%,d", result.count) + if (result.truncated) "+" else ""
        return if (current in 0 until result.count) "${current + 1} of $n" else "$n matches"
    }

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
            is TextReplace.Outcome.Ok -> null
        }

    fun replaced(count: Int): String = "$count replaced"
}
