package dev.kstep.desktop.edit

/** Where a part name sits in the script text (ADR-0068). */
sealed interface SourceLocation {
    /** Exactly one literal; [offset] is the index right after the opening quote. */
    data class Found(
        val offset: Int,
    ) : SourceLocation

    /** More than one literal; [query] is `"name"` including the quotes, ready for the find bar. */
    data class Ambiguous(
        val query: String,
    ) : SourceLocation

    data object NotFound : SourceLocation

    data object TooLong : SourceLocation

    data object NotLiteral : SourceLocation

    data object TimedOut : SourceLocation
}

/**
 * Finds the string literal `"name"` of a part in the script text (ADR-0068). A heuristic: the worker reports no source
 * positions, so the only link is the name. Literal, case-sensitive, linear (through [TextSearch]), at most two matches,
 * bounded by a time budget. The name comes from an untrusted script or worker output and is never interpreted.
 */
object PartSourceLocator {
    const val MAX_NAME_CHARS = TextSearch.MAX_QUERY_CHARS - 2
    const val DEFAULT_BUDGET_NANOS = 500_000_000L

    fun locate(
        text: String,
        name: String,
        budgetNanos: Long = DEFAULT_BUDGET_NANOS,
        nanoTime: () -> Long = System::nanoTime,
    ): SourceLocation {
        if (name.isEmpty()) return SourceLocation.NotFound
        if (name.length > MAX_NAME_CHARS) return SourceLocation.TooLong
        if (name.any { it == '"' || it == '\\' || it == '$' || it < ' ' || it == '\u007F' }) {
            return SourceLocation.NotLiteral
        }
        val query = "\"" + name + "\""
        if (TextSearch.sanitizeQuery(query) != query) return SourceLocation.NotLiteral
        val deadline = nanoTime() + budgetNanos
        if (nanoTime() >= deadline) return SourceLocation.TimedOut
        var aborted = false
        val result =
            TextSearch.find(
                text,
                query,
                matchCase = true,
                maxMatches = 2,
                shouldContinue = { (nanoTime() < deadline).also { ok -> if (!ok) aborted = true } },
            )
        if (aborted) return SourceLocation.TimedOut
        return when {
            result.count == 0 -> SourceLocation.NotFound
            result.count == 1 && !result.truncated -> SourceLocation.Found(result.starts[0] + 1)
            else -> SourceLocation.Ambiguous(query)
        }
    }
}
