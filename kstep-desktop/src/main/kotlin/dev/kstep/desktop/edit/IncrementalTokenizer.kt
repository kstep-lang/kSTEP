package dev.kstep.desktop.edit

/**
 * Keeps the tokens of the last text and re-tokenizes only from the line of a change until the scanner state at a line
 * start agrees with the stored one again (ADR-0088). The result always equals [KotlinTokenizer.tokenize]; whenever the
 * incremental path cannot prove that, it falls back to a full scan. Not thread-safe: the editor calls it on the UI
 * thread only. Pure logic, no Compose.
 */
class IncrementalTokenizer(
    private val maxChars: Int = EditorText.MAX_BYTES,
    private val names: () -> Set<String> = { KStepDslNames.names },
) {
    /** What the last [update] did: how many characters were scanned, whether it was a full scan, fallbacks so far. */
    data class Stats(
        val scannedChars: Int,
        val full: Boolean,
        val fullFallbacks: Long,
    )

    var lastStats: Stats = Stats(0, true, 0)
        private set

    private var fullFallbacks = 0L
    private var oldText: String? = null
    private var activeNames: Set<String> = emptySet()
    private var namesKnown = false
    private var tokens: List<Token> = emptyList()
    private var lineStarts = IntArray(0)
    private var lineStates: Array<LineState> = emptyArray()

    /** The tokens of [text]; a NEW list on every change (a list returned earlier is never mutated). */
    fun update(text: String): List<Token> {
        val current = names()
        if (namesKnown && activeNames !== current && activeNames != current) reset()
        activeNames = current
        namesKnown = true
        val old = oldText
        if (old != null && (text === old || text == old)) {
            lastStats = Stats(0, false, fullFallbacks)
            return tokens
        }
        if (text.length > maxChars) {
            reset()
            lastStats = Stats(text.length, true, fullFallbacks)
            return KotlinTokenizer.tokenize(text, current)
        }
        if (old == null) return fullScan(text)
        return incremental(old, text) ?: run {
            fullFallbacks++
            fullScan(text)
        }
    }

    /** Forgets the stored text; [update] calls it itself when the DSL names change (ADR-0089). */
    fun reset() {
        oldText = null
        tokens = emptyList()
        lineStarts = IntArray(0)
        lineStates = emptyArray()
    }

    /** Test hook: the stored line starts, line states and tokens equal those of a fresh full scan. */
    internal fun verifyAgainstFullScan(): Boolean {
        val text = oldText ?: return tokens.isEmpty() && lineStarts.isEmpty()
        val fresh = scanAll(text)
        if (fresh.tokens != tokens) return false
        if (fresh.starts.size != lineStarts.size || fresh.states.size != lineStates.size) return false
        for (k in lineStarts.indices) {
            if (fresh.starts[k] != lineStarts[k]) return false
            if (!fresh.states[k].exactlyEquals(lineStates[k])) return false
        }
        return true
    }

    private class Scanned(
        val tokens: List<Token>,
        val starts: IntArray,
        val states: Array<LineState>,
    )

    /** A growable log of line starts and their states; consecutive equal states share one instance. */
    private class LineLog(
        first: LineState,
    ) {
        var starts = IntArray(64)
        var states = ArrayList<LineState>()
        var size = 0
        var last: LineState = first

        fun add(
            start: Int,
            live: ScanState,
        ) {
            if (!last.matches(live)) last = live.snapshot()
            put(start, last)
        }

        fun put(
            start: Int,
            state: LineState,
        ) {
            if (size == starts.size) starts = starts.copyOf(size * 2)
            starts[size++] = start
            states.add(state)
        }
    }

    private fun scanAll(text: String): Scanned {
        val out = ArrayList<Token>(maxOf(16, text.length / 16))
        val log = LineLog(LineState.INITIAL)
        log.put(0, LineState.INITIAL)
        TokenScanner(text, activeNames).run(0, ScanState(), out) { q, live ->
            log.add(q, live)
            false
        }
        return Scanned(out, log.starts.copyOf(log.size), log.states.toTypedArray())
    }

    private fun fullScan(text: String): List<Token> {
        val scanned = scanAll(text)
        oldText = text
        tokens = scanned.tokens
        lineStarts = scanned.starts
        lineStates = scanned.states
        lastStats = Stats(text.length, true, fullFallbacks)
        return tokens
    }

    /** The incremental path; `null` means "could not prove it, do a full scan". */
    private fun incremental(
        old: String,
        text: String,
    ): List<Token>? {
        val edit = TextEdit.between(old, text) ?: return tokens
        val p = edit.start
        val delta = edit.delta
        // one line of safety margin before the line of the change (ADR-0088 D3)
        val l0 = maxOf(0, lastIndexAtOrBefore(p) - 1)
        val resume = lineStarts[l0]
        val startState = lineStates[l0]
        val cut = if (startState.pendingStart >= 0) startState.pendingStart else resume
        val prefixCount = firstIndexWithEndAfter(cut)

        val out = ArrayList<Token>(maxOf(16, prefixCount + (text.length - resume) / 16))
        for (k in 0 until prefixCount) out.add(tokens[k])
        val log = LineLog(startState)
        var convergedLine = -1
        var newPending = -1
        val mapOld = { x: Int ->
            when {
                x < p -> x
                x >= edit.oldEnd -> x + delta
                else -> -1
            }
        }
        val state = ScanState()
        state.restore(startState)
        val stop =
            TokenScanner(text, activeNames).run(resume, state, out) { q, live ->
                log.add(q, live)
                if (q - 1 >= edit.newEnd) {
                    val m = indexOfLineStart(q - delta)
                    if (m >= 0 && lineStates[m].sameAs(live, mapOld)) {
                        convergedLine = m
                        newPending = live.pendingStart
                        return@run true
                    }
                }
                false
            }

        val oldCount = lineStarts.size
        val newStarts: IntArray
        val newStates: Array<LineState>
        if (convergedLine < 0) {
            newStarts = IntArray(l0 + 1 + log.size)
            System.arraycopy(lineStarts, 0, newStarts, 0, l0 + 1)
            System.arraycopy(log.starts, 0, newStarts, l0 + 1, log.size)
            val head = lineStates
            val tail = log.states
            newStates = Array(newStarts.size) { if (it <= l0) head[it] else tail[it - l0 - 1] }
        } else {
            val m = convergedLine
            val lsM = lineStarts[m]
            val oldPending = lineStates[m].pendingStart
            val first = firstIndexWithStartAtLeast(lsM)
            val suffixFrom =
                if (oldPending >= 0) {
                    val pt = first - 1
                    if (pt < 0 || tokens[pt].start != oldPending || tokens[pt].end <= lsM) return null
                    pt
                } else {
                    first
                }
            for (k in suffixFrom until tokens.size) {
                val t = tokens[k]
                val isPending = oldPending >= 0 && k == suffixFrom
                val s = if (isPending) newPending else t.start + delta
                val e = t.end + delta
                out.add(if (s == t.start && e == t.end) t else Token(s, e, t.kind))
            }
            val after = oldCount - (m + 1)
            newStarts = IntArray(l0 + 1 + log.size + after)
            System.arraycopy(lineStarts, 0, newStarts, 0, l0 + 1)
            System.arraycopy(log.starts, 0, newStarts, l0 + 1, log.size)
            val head = lineStates
            val tail = log.states
            val shifted = lineStates
            newStates = Array(newStarts.size) { LineState.INITIAL }
            for (k in 0..l0) newStates[k] = head[k]
            for (k in 0 until log.size) newStates[l0 + 1 + k] = tail[k]
            var memoOld: LineState? = null
            var memoNew: LineState = LineState.INITIAL
            val base = l0 + 1 + log.size
            for (k in 0 until after) {
                val os = shifted[m + 1 + k]
                newStarts[base + k] = lineStarts[m + 1 + k] + delta
                if (os.pendingStart < 0) {
                    newStates[base + k] = os
                } else {
                    if (os !== memoOld) {
                        memoOld = os
                        val mapped = if (os.pendingStart < lsM) newPending else os.pendingStart + delta
                        memoNew = os.withPending(mapped)
                    }
                    newStates[base + k] = memoNew
                }
            }
        }
        oldText = text
        tokens = out
        lineStarts = newStarts
        lineStates = newStates
        lastStats = Stats(stop - resume, false, fullFallbacks)
        return out
    }

    /** The largest line index whose start is at or before [pos]. */
    private fun lastIndexAtOrBefore(pos: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= pos) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun indexOfLineStart(pos: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = lineStarts[mid]
            if (v == pos) return mid
            if (v < pos) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    /** The number of stored tokens with `end <= pos` (tokens are sorted and non-overlapping). */
    private fun firstIndexWithEndAfter(pos: Int): Int {
        var lo = 0
        var hi = tokens.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tokens[mid].end <= pos) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun firstIndexWithStartAtLeast(pos: Int): Int {
        var lo = 0
        var hi = tokens.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tokens[mid].start < pos) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
