package dev.kstep.desktop.ai.diff

/**
 * A line diff of two texts (Myers O(ND)) with 3 lines of context (ADR-0056). Time is O(N·D); memory is O(D²/2) ints for
 * the backtracking trace (only the live diagonals of each step are kept), so it is bounded by [compute]'s `maxEdits`
 * and not by the size of the texts.
 */
object LineDiff {
    private const val CONTEXT = 3

    fun compute(
        old: String,
        new: String,
        maxLines: Int = 50_000,
        maxEdits: Int = 5_000,
        cancelled: () -> Boolean = { false },
    ): DiffResult {
        val a = lines(old)
        val b = lines(new)
        if (a.size > maxLines || b.size > maxLines) return DiffResult.TooLarge(a.size, b.size)
        val ops = myers(a, b, maxEdits, cancelled) ?: return DiffResult.TooLarge(a.size, b.size)
        return hunks(ops, a, b)
    }

    internal fun lines(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val parts = s.split('\n')
        return if (s.endsWith('\n')) parts.dropLast(1) else parts
    }

    private enum class Op { KEEP, ADD, DEL }

    private fun myers(
        a: List<String>,
        b: List<String>,
        maxEdits: Int,
        cancelled: () -> Boolean,
    ): List<Op>? {
        val n = a.size
        val m = b.size
        val max = n + m
        if (max == 0) return emptyList()
        val offset = max
        val v = IntArray(2 * max + 2)
        // trace[d] holds v before step d for the diagonals -(d-1)..(d-1) (step 2: only those have the parity of d-1 and
        // were written); everything outside reads as 0, like the untouched full array did.
        val trace = ArrayList<IntArray>()
        var found = -1
        val limit = minOf(max, maxEdits)
        loop@ for (d in 0..limit) {
            if (cancelled()) throw java.util.concurrent.CancellationException("line diff cancelled")
            trace.add(
                if (d == 0) {
                    IntArray(0)
                } else {
                    IntArray(d) { v[offset - (d - 1) + 2 * it] }
                },
            )
            for (k in -d..d step 2) {
                var x =
                    if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                        v[offset + k + 1]
                    } else {
                        v[offset + k - 1] + 1
                    }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    found = d
                    break@loop
                }
            }
        }
        if (found < 0) return null
        val ops = ArrayList<Op>()
        var x = n
        var y = m
        for (d in found downTo 1) {
            val vv = trace[d]

            fun at(k: Int): Int = if (k < -(d - 1) || k > d - 1) 0 else vv[(k + d - 1) / 2]
            val k = x - y
            val prevK =
                if (k == -d || (k != d && at(k - 1) < at(k + 1))) k + 1 else k - 1
            val prevX = at(prevK)
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) {
                ops.add(Op.KEEP)
                x--
                y--
            }
            if (x == prevX) ops.add(Op.ADD) else ops.add(Op.DEL)
            x = prevX
            y = prevY
        }
        while (x > 0 && y > 0) {
            ops.add(Op.KEEP)
            x--
            y--
        }
        return ops.asReversed()
    }

    private fun hunks(
        ops: List<Op>,
        a: List<String>,
        b: List<String>,
    ): DiffResult {
        // flat line list first
        val flat = ArrayList<DiffLine>()
        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        for (op in ops) {
            when (op) {
                Op.KEEP -> {
                    flat.add(DiffLine.Context(i + 1, j + 1, a[i]))
                    i++
                    j++
                }
                Op.ADD -> {
                    flat.add(DiffLine.Added(j + 1, b[j]))
                    j++
                    added++
                }
                Op.DEL -> {
                    flat.add(DiffLine.Removed(i + 1, a[i]))
                    i++
                    removed++
                }
            }
        }
        if (added == 0 && removed == 0) return DiffResult.Hunks(emptyList(), 0, 0)
        val keep = BooleanArray(flat.size)
        for ((idx, line) in flat.withIndex()) {
            if (line !is DiffLine.Context) {
                for (t in maxOf(0, idx - CONTEXT)..minOf(flat.size - 1, idx + CONTEXT)) keep[t] = true
            }
        }
        val hunks = ArrayList<Hunk>()
        var cur = ArrayList<DiffLine>()
        var skipped = 0
        for ((idx, line) in flat.withIndex()) {
            if (keep[idx]) {
                if (skipped > 0) {
                    if (cur.isNotEmpty()) cur.add(DiffLine.Collapsed(skipped))
                    skipped = 0
                }
                cur.add(line)
            } else {
                skipped++
            }
        }
        if (skipped > 0 && cur.isNotEmpty()) cur.add(DiffLine.Collapsed(skipped))
        if (cur.isNotEmpty()) hunks.add(Hunk(cur))
        return DiffResult.Hunks(hunks, added, removed)
    }

    /** Test helper: rebuilds the new text from the old one and the hunks (collapsed gaps are copied from [old]). */
    internal fun apply(
        old: String,
        result: DiffResult.Hunks,
    ): List<String> {
        val a = lines(old)
        val out = ArrayList<String>()
        var oldPos = 0
        for (h in result.hunks) {
            for (l in h.lines) {
                when (l) {
                    is DiffLine.Collapsed -> Unit
                    is DiffLine.Context -> {
                        while (oldPos < l.oldNo - 1) out.add(a[oldPos++])
                        out.add(l.text)
                        oldPos++
                    }
                    is DiffLine.Removed -> {
                        while (oldPos < l.oldNo - 1) out.add(a[oldPos++])
                        oldPos++
                    }
                    is DiffLine.Added -> out.add(l.text)
                }
            }
        }
        while (oldPos < a.size) out.add(a[oldPos++])
        return out
    }
}

sealed interface DiffResult {
    data class Hunks(
        val hunks: List<Hunk>,
        val added: Int,
        val removed: Int,
    ) : DiffResult

    data class TooLarge(
        val oldLines: Int,
        val newLines: Int,
    ) : DiffResult
}

data class Hunk(
    val lines: List<DiffLine>,
)

sealed interface DiffLine {
    data class Context(
        val oldNo: Int,
        val newNo: Int,
        val text: String,
    ) : DiffLine

    data class Added(
        val newNo: Int,
        val text: String,
    ) : DiffLine

    data class Removed(
        val oldNo: Int,
        val text: String,
    ) : DiffLine

    data class Collapsed(
        val count: Int,
    ) : DiffLine
}
