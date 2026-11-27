package dev.kstep.desktop.edit

/** Text search of the editor (ADR-0067, ADR-0090): literal or the safe regex subset, linear, bounded in every dimension. */
object TextSearch {
    const val MAX_QUERY_CHARS = 256
    const val MAX_MATCHES = 10_000
    private const val CHECK_INTERVAL = 8192

    enum class Limit { NONE, MATCHES, STEP_BUDGET, TEXT_TOO_LARGE }

    class Result(
        val starts: IntArray,
        val ends: IntArray,
        val truncated: Boolean,
        val pattern: SearchPattern?,
        val limit: Limit = Limit.NONE,
        internal val steps: Long = 0,
        /** `count x slotCount` capture slots of a regex search that asked for them. */
        internal val groups: IntArray? = null,
    ) {
        val count: Int get() = starts.size

        fun lengthOf(i: Int): Int = ends[i] - starts[i]

        /** Index of the first match starting at or after [offset]; wraps to 0; -1 when there is no match. */
        fun firstAtOrAfter(offset: Int): Int {
            if (starts.isEmpty()) return -1
            var lo = 0
            var hi = starts.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] < offset) lo = mid + 1 else hi = mid
            }
            return if (lo == starts.size) 0 else lo
        }

        fun next(current: Int): Int = if (starts.isEmpty()) -1 else (current + 1).mod(starts.size)

        fun previous(current: Int): Int = if (starts.isEmpty()) -1 else (current - 1).mod(starts.size)

        /**
         * Indexes of the matches that touch [fromOffset, toOffset), at most [cap]. The matches are sorted and disjoint,
         * so both `starts` and `ends` grow; the binary search runs over `ends`. An empty match counts when it lies in
         * the window.
         */
        fun inRange(
            fromOffset: Int,
            toOffset: Int,
            cap: Int = 500,
        ): IntRange {
            if (starts.isEmpty() || toOffset <= fromOffset) return IntRange.EMPTY
            var lo = 0
            var hi = starts.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                val touches = ends[mid] > fromOffset || starts[mid] >= fromOffset
                if (touches) hi = mid else lo = mid + 1
            }
            var end = lo
            while (end < starts.size && starts[end] < toOffset && end - lo < cap) end++
            return if (end > lo) lo until end else IntRange.EMPTY
        }
    }

    private val EMPTY_INTS = IntArray(0)

    private fun empty(
        pattern: SearchPattern?,
        limit: Limit = Limit.NONE,
    ) = Result(EMPTY_INTS, EMPTY_INTS, false, pattern, limit)

    /** The old literal entry point, kept for the callers that only know a query (the part locator, the tests). */
    fun find(
        text: String,
        query: String,
        matchCase: Boolean,
        maxMatches: Int = MAX_MATCHES,
        shouldContinue: () -> Boolean = { true },
    ): Result = find(text, SearchPattern.Literal(query, matchCase, false), maxMatches, shouldContinue)

    fun find(
        text: String,
        pattern: SearchPattern,
        maxMatches: Int = MAX_MATCHES,
        shouldContinue: () -> Boolean = { true },
        captureGroups: Boolean = false,
    ): Result =
        when (pattern) {
            is SearchPattern.Literal -> findLiteral(text, pattern, maxMatches, shouldContinue)
            is SearchPattern.Regex -> findRegex(text, pattern, maxMatches, shouldContinue, captureGroups)
        }

    private fun findRegex(
        text: String,
        pattern: SearchPattern.Regex,
        maxMatches: Int,
        shouldContinue: () -> Boolean,
        captureGroups: Boolean,
    ): Result {
        if (text.length > SafeRegex.MAX_TEXT_CHARS) return empty(pattern, Limit.TEXT_TOO_LARGE)
        val run =
            SafeRegex.findAll(
                text,
                pattern.compiled.program,
                maxMatches,
                captures = captureGroups,
                shouldContinue = shouldContinue,
            ) ?: return empty(pattern)
        val limit =
            when {
                run.budgetExhausted -> Limit.STEP_BUDGET
                run.truncated -> Limit.MATCHES
                else -> Limit.NONE
            }
        return Result(
            run.starts,
            run.ends,
            limit != Limit.NONE,
            pattern,
            limit,
            run.steps,
            run.groups,
        )
    }

    private fun isAsciiWord(cp: Int): Boolean =
        cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code || cp == '_'.code

    /** `\b` semantics at [start] and [end] of a match (the same as the regex path wraps its pattern in). */
    private fun wordBounded(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        val before = if (start > 0) text.codePointBefore(start) else -1
        val first = text.codePointAt(start)
        val last = text.codePointBefore(end)
        val after = if (end < text.length) text.codePointAt(end) else -1
        return isAsciiWord(before) != isAsciiWord(first) && isAsciiWord(last) != isAsciiWord(after)
    }

    private fun findLiteral(
        text: String,
        pattern: SearchPattern.Literal,
        maxMatches: Int,
        shouldContinue: () -> Boolean,
    ): Result {
        val query = pattern.query
        val matchCase = pattern.matchCase
        if (query.isEmpty() ||
            query.length > text.length
        ) {
            return empty(pattern)
        }
        val len = query.length
        val found = ArrayList<Int>()
        var truncated = false
        var i = 0
        var nextCheck = CHECK_INTERVAL
        val last = text.length - len
        val first = query[0]
        val firstLower = first.lowercaseChar()
        val firstUpper = first.uppercaseChar()
        while (i <= last) {
            if (i >= nextCheck) {
                if (!shouldContinue()) return empty(pattern)
                nextCheck = i + CHECK_INTERVAL
            }
            val at =
                if (matchCase) {
                    text.indexOf(query, i)
                } else {
                    var p = i
                    var hit = -1
                    while (p <= last) {
                        val c = text[p]
                        if ((c == first || c == firstLower || c == firstUpper || c.lowercaseChar() == firstLower) &&
                            text.regionMatches(p, query, 0, len, ignoreCase = true)
                        ) {
                            hit = p
                            break
                        }
                        p++
                        if (p >= nextCheck) {
                            if (!shouldContinue()) return empty(pattern)
                            nextCheck = p + CHECK_INTERVAL
                        }
                    }
                    hit
                }
            if (at < 0 || at > last) break
            if (splitsPair(text, at, at + len) || (pattern.wholeWord && !wordBounded(text, at, at + len))) {
                i = at + 1
                continue
            }
            if (found.size >= maxMatches) {
                truncated = true
                break
            }
            found.add(at)
            i = at + len
        }
        val starts = found.toIntArray()
        return Result(
            starts,
            IntArray(starts.size) { starts[it] + len },
            truncated,
            pattern,
            if (truncated) Limit.MATCHES else Limit.NONE,
        )
    }

    private fun splitsPair(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        if (text[start].isLowSurrogate() && start > 0 && text[start - 1].isHighSurrogate()) return true
        return end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()
    }

    /** Removes line breaks and cuts the query to [MAX_QUERY_CHARS] without splitting a surrogate pair. */
    fun sanitizeQuery(raw: String): String = clip(raw.filter { it != '\n' && it != '\r' }, MAX_QUERY_CHARS)

    internal fun clip(
        s: String,
        max: Int,
    ): String {
        if (s.length <= max) return s
        val cut = if (s[max - 1].isHighSurrogate() && s[max].isLowSurrogate()) max - 1 else max
        return s.substring(0, cut)
    }
}
