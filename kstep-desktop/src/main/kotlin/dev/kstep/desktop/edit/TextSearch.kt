package dev.kstep.desktop.edit

/** Literal text search of the editor (ADR-0067): linear, no regular expressions, bounded in every dimension. */
object TextSearch {
    const val MAX_QUERY_CHARS = 256
    const val MAX_MATCHES = 10_000
    private const val CHECK_INTERVAL = 8192

    class Result(
        val starts: IntArray,
        val length: Int,
        val truncated: Boolean,
        val query: String,
        val matchCase: Boolean,
    ) {
        val count: Int get() = starts.size

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

        /** Indexes of the matches that touch [fromOffset, toOffset), at most [cap] (binary search for the start). */
        fun inRange(
            fromOffset: Int,
            toOffset: Int,
            cap: Int = 500,
        ): IntRange {
            if (starts.isEmpty() || toOffset <= fromOffset) return IntRange.EMPTY
            var lo = 0
            var hi = starts.size
            val from = fromOffset - length + 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] < from) lo = mid + 1 else hi = mid
            }
            var end = lo
            while (end < starts.size && starts[end] < toOffset && end - lo < cap) end++
            return if (end > lo) lo until end else IntRange.EMPTY
        }
    }

    private val EMPTY_INTS = IntArray(0)

    fun find(
        text: String,
        query: String,
        matchCase: Boolean,
        maxMatches: Int = MAX_MATCHES,
        shouldContinue: () -> Boolean = { true },
    ): Result {
        if (query.isEmpty() ||
            query.length > text.length
        ) {
            return Result(EMPTY_INTS, query.length, false, query, matchCase)
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
                if (!shouldContinue()) return Result(EMPTY_INTS, len, false, query, matchCase)
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
                            if (!shouldContinue()) return Result(EMPTY_INTS, len, false, query, matchCase)
                            nextCheck = p + CHECK_INTERVAL
                        }
                    }
                    hit
                }
            if (at < 0 || at > last) break
            if (splitsPair(text, at, at + len)) {
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
        return Result(found.toIntArray(), len, truncated, query, matchCase)
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
