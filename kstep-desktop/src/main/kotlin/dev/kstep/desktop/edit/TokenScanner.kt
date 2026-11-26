package dev.kstep.desktop.edit

/** What the scanner is inside of. A normal string never survives a line break, so it is never open at a line start. */
internal enum class ScanMode { CODE, STRING, RAW_STRING, BLOCK_COMMENT }

/**
 * The mutable state of the scanner (ADR-0088). It is complete: continuing from a snapshot taken at a line start gives
 * exactly the tokens of the full scan. Dead fields are kept normalised (`pendingStart` is -1 and `commentNesting` is 0
 * in code; `braces` is 0 outside a template), so two states can be compared field by field.
 */
internal class ScanState {
    var mode: ScanMode = ScanMode.CODE
    var commentNesting: Int = 0
    var pendingStart: Int = -1
    var depth: Int = 0
    var braces: Int = 0
    val savedRaw = BooleanArray(KotlinTokenizer.MAX_TEMPLATE_DEPTH)
    val savedBraces = IntArray(KotlinTokenizer.MAX_TEMPLATE_DEPTH)

    fun snapshot(): LineState {
        val stack =
            if (depth == 0) {
                LineState.EMPTY_STACK
            } else {
                IntArray(depth) { (savedBraces[it] shl 1) or (if (savedRaw[it]) 1 else 0) }
            }
        return LineState(mode, commentNesting, pendingStart, depth, braces, stack)
    }

    fun restore(s: LineState) {
        mode = s.mode
        commentNesting = s.commentNesting
        pendingStart = s.pendingStart
        depth = s.depth
        braces = s.braces
        for (k in 0 until depth) {
            savedRaw[k] = (s.stack[k] and 1) != 0
            savedBraces[k] = s.stack[k] ushr 1
        }
    }
}

/** An immutable, normalised snapshot of a [ScanState] at a line start. [stack] holds `(savedBraces shl 1) or raw`. */
internal class LineState(
    val mode: ScanMode,
    val commentNesting: Int,
    val pendingStart: Int,
    val depth: Int,
    val braces: Int,
    val stack: IntArray,
) {
    /** Equal to [live] in every field, `pendingStart` included. */
    fun matches(live: ScanState): Boolean = matchesWith(live, pendingStart)

    /**
     * Equal to [live] except for `pendingStart`, which is compared after mapping the old offset into the new text
     * with [mapOld] (-1 when it cannot be mapped, which never matches).
     */
    fun sameAs(
        live: ScanState,
        mapOld: (Int) -> Int,
    ): Boolean {
        if (pendingStart < 0) return matchesWith(live, -1)
        val mapped = mapOld(pendingStart)
        return mapped >= 0 && matchesWith(live, mapped)
    }

    fun withPending(newPending: Int): LineState =
        if (newPending == pendingStart) this else LineState(mode, commentNesting, newPending, depth, braces, stack)

    fun exactlyEquals(o: LineState): Boolean =
        mode == o.mode &&
            commentNesting == o.commentNesting &&
            pendingStart == o.pendingStart &&
            depth == o.depth &&
            braces == o.braces &&
            stack.contentEquals(o.stack)

    private fun matchesWith(
        live: ScanState,
        expectedPending: Int,
    ): Boolean {
        if (mode != live.mode ||
            commentNesting != live.commentNesting ||
            depth != live.depth ||
            braces != live.braces ||
            expectedPending != live.pendingStart
        ) {
            return false
        }
        for (k in 0 until depth) {
            if (stack[k] != ((live.savedBraces[k] shl 1) or (if (live.savedRaw[k]) 1 else 0))) return false
        }
        return true
    }

    companion object {
        val EMPTY_STACK = IntArray(0)
        val INITIAL = LineState(ScanMode.CODE, 0, -1, 0, 0, EMPTY_STACK)
    }
}

private const val MAX_DEPTH = KotlinTokenizer.MAX_TEMPLATE_DEPTH

/**
 * The scanner behind [KotlinTokenizer] as a resumable state machine (ADR-0088). No scan step consumes a line break as
 * part of a multi-character step, so the scan pointer lands exactly on every line start, where the state depends only
 * on the text before it.
 */
internal class TokenScanner(
    private val text: CharSequence,
    private val dslNames: Set<String> = KStepDslNames.names,
) {
    /**
     * Scans from [from] (0 or a line start) with [state] (restored by the caller) and appends the tokens to [out].
     * At every line start after [from] inside the text it calls [lineHook] with the position and the live state; a
     * `true` result stops the scan there without emitting the open token. Returns the stop position (or the text
     * length after the scan ran to the end and emitted the open token).
     */
    fun run(
        from: Int,
        state: ScanState,
        out: MutableList<Token>,
        lineHook: ((Int, ScanState) -> Boolean)?,
    ): Int {
        val n = text.length
        var i = from
        var lastHooked = -1

        fun emit(
            start: Int,
            end: Int,
            kind: TokenKind,
        ) {
            if (end > start) out.add(Token(start, end, kind))
        }

        while (i < n) {
            if (lineHook != null && i > from && text[i - 1] == '\n' && i != lastHooked) {
                lastHooked = i
                if (lineHook(i, state)) return i
            }
            val c = text[i]
            when (state.mode) {
                ScanMode.STRING, ScanMode.RAW_STRING -> {
                    val raw = state.mode == ScanMode.RAW_STRING
                    when {
                        !raw && c == '\\' -> i = if (i + 1 < n && text[i + 1] != '\n') i + 2 else i + 1
                        c == '"' && !raw -> {
                            i++
                            emit(state.pendingStart, i, TokenKind.STRING)
                            state.mode = ScanMode.CODE
                            state.pendingStart = -1
                        }
                        c == '"' && raw -> {
                            if (i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                                i += 3
                                while (i < n && text[i] == '"') i++
                                emit(state.pendingStart, i, TokenKind.STRING)
                                state.mode = ScanMode.CODE
                                state.pendingStart = -1
                            } else {
                                i++
                            }
                        }
                        !raw && c == '\n' -> {
                            emit(state.pendingStart, i, TokenKind.STRING)
                            state.mode = ScanMode.CODE
                            state.pendingStart = -1
                        }
                        c == '$' && state.depth < MAX_DEPTH && i + 1 < n && text[i + 1] == '{' -> {
                            emit(state.pendingStart, i, TokenKind.STRING)
                            emit(i, i + 2, TokenKind.TEMPLATE)
                            state.savedRaw[state.depth] = raw
                            state.savedBraces[state.depth] = state.braces
                            state.depth++
                            state.braces = 0
                            state.mode = ScanMode.CODE
                            state.pendingStart = -1
                            i += 2
                        }
                        c == '$' && i + 1 < n && KotlinTokenizer.isIdentStart(text[i + 1]) -> {
                            emit(state.pendingStart, i, TokenKind.STRING)
                            var j = i + 2
                            while (j < n && KotlinTokenizer.isIdentPart(text[j])) j++
                            emit(i, j, TokenKind.TEMPLATE)
                            i = j
                            state.pendingStart = j
                        }
                        else -> i++
                    }
                }
                ScanMode.BLOCK_COMMENT -> {
                    if (c == '/' && i + 1 < n && text[i + 1] == '*') {
                        state.commentNesting++
                        i += 2
                    } else if (c == '*' && i + 1 < n && text[i + 1] == '/') {
                        state.commentNesting--
                        i += 2
                        if (state.commentNesting == 0) {
                            emit(state.pendingStart, i, TokenKind.COMMENT)
                            state.mode = ScanMode.CODE
                            state.pendingStart = -1
                        }
                    } else {
                        i++
                    }
                }
                ScanMode.CODE -> i = scanCode(c, i, n, state, ::emit)
            }
        }
        when (state.mode) {
            ScanMode.STRING, ScanMode.RAW_STRING -> emit(state.pendingStart, n, TokenKind.STRING)
            ScanMode.BLOCK_COMMENT -> emit(state.pendingStart, n, TokenKind.COMMENT)
            ScanMode.CODE -> Unit
        }
        return n
    }

    private fun scanCode(
        c: Char,
        i: Int,
        n: Int,
        state: ScanState,
        emit: (Int, Int, TokenKind) -> Unit,
    ): Int {
        when {
            c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                var j = i + 2
                while (j < n && text[j] != '\n') j++
                emit(i, j, TokenKind.COMMENT)
                return j
            }
            c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                state.mode = ScanMode.BLOCK_COMMENT
                state.commentNesting = 1
                state.pendingStart = i
                return i + 2
            }
            c == '"' -> {
                val raw = i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"'
                state.pendingStart = i
                state.mode = if (raw) ScanMode.RAW_STRING else ScanMode.STRING
                return i + if (raw) 3 else 1
            }
            c == '\'' -> {
                var j = i + 1
                while (j < n && text[j] != '\n') {
                    if (text[j] == '\\' && j + 1 < n && text[j + 1] != '\n') {
                        j += 2
                    } else if (text[j] == '\'') {
                        j++
                        break
                    } else {
                        j++
                    }
                }
                emit(i, j, TokenKind.STRING)
                return j
            }
            c == '`' -> {
                var j = i + 1
                while (j < n && text[j] != '`' && text[j] != '\n') j++
                return if (j < n && text[j] == '`') j + 1 else j
            }
            c == '@' -> {
                var j = i + 1
                while (j < n && KotlinTokenizer.isIdentPart(text[j])) j++
                if (j < n && text[j] == ':' && j + 1 < n && KotlinTokenizer.isIdentStart(text[j + 1])) {
                    j++
                    while (j < n && KotlinTokenizer.isIdentPart(text[j])) j++
                }
                emit(i, j, TokenKind.ANNOTATION)
                return maxOf(j, i + 1)
            }
            c in '0'..'9' -> return KotlinTokenizer.scanNumber(text, i, emit)
            KotlinTokenizer.isIdentStart(c) -> {
                var j = i + 1
                while (j < n && KotlinTokenizer.isIdentPart(text[j])) j++
                val word = text.subSequence(i, j).toString()
                when {
                    word in dslNames -> emit(i, j, TokenKind.DSL)
                    word in KotlinTokenizer.keywords -> emit(i, j, TokenKind.KEYWORD)
                }
                return j
            }
            c == '{' -> {
                if (state.depth > 0) state.braces++
                return i + 1
            }
            c == '}' -> {
                if (state.depth > 0 && state.braces == 0) {
                    emit(i, i + 1, TokenKind.TEMPLATE)
                    state.depth--
                    state.mode = if (state.savedRaw[state.depth]) ScanMode.RAW_STRING else ScanMode.STRING
                    state.braces = state.savedBraces[state.depth]
                    state.pendingStart = i + 1
                    return i + 1
                }
                if (state.depth > 0) state.braces--
                return i + 1
            }
            else -> return i + 1
        }
    }
}
