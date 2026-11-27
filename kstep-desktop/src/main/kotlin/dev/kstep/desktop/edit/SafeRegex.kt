package dev.kstep.desktop.edit

/**
 * A non-backtracking regular-expression subset (ADR-0090): a Pike VM, so the work is O(text x program) no matter how
 * hostile the pattern is. Every limit is a count, never a clock, so a slow machine cannot change a result.
 *
 * Supported: literals, `.`, classes (`[a-c]`, `[^x]`, `\d \w \s` and their negations), `^ $ \b \B`, groups
 * (capturing and `(?:)`), `|`, and the greedy quantifiers `* + ? {m} {m,} {m,n}`. Everything else is rejected with a
 * [RejectReason] (back-references, look-around, lazy and possessive quantifiers, inline flags, named groups).
 * `\w`, `\d`, `\s` and `\b` are ASCII only; case folding works per code point.
 */
object SafeRegex {
    const val MAX_PATTERN_CHARS = TextSearch.MAX_QUERY_CHARS
    const val MAX_REPEAT = 100
    const val MAX_INSTRUCTIONS = 2_000
    const val MAX_GROUPS = 9
    const val MAX_NESTING = 32
    const val MAX_TEXT_CHARS = 1_048_576
    const val STEP_BUDGET = 50_000_000L
    private const val CHECK_INTERVAL = 8192
    private const val MAX_CODE_POINT = 0x10FFFF

    enum class RejectReason {
        BACKREFERENCE,
        LOOKAROUND,
        LAZY_OR_POSSESSIVE,
        INLINE_FLAGS_OR_NAMED,
        UNBALANCED,
        BAD_QUANTIFIER,
        BAD_ESCAPE,
        BAD_CLASS_RANGE,
        TOO_COMPLEX,
        EMPTY,
    }

    sealed interface Compiled {
        class Ok internal constructor(
            internal val program: Program,
            val groupCount: Int,
        ) : Compiled

        data class Rejected(
            val reason: RejectReason,
            val escapeChar: Int? = null,
        ) : Compiled
    }

    internal class Inst(
        val op: Int,
        val x: Int = 0,
        val y: Int = 0,
        val ranges: IntArray? = null,
        val negated: Boolean = false,
        val fold: Boolean = false,
    )

    internal class Program(
        val insts: Array<Inst>,
        val slotCount: Int,
    ) {
        val size: Int get() = insts.size
    }

    internal class MatchRun(
        val starts: IntArray,
        val ends: IntArray,
        /** `count x slotCount` slots when captures were requested, else `null`. */
        val groups: IntArray?,
        val truncated: Boolean,
        val budgetExhausted: Boolean,
        val steps: Long,
    )

    private const val CHAR = 0
    private const val ANY = 1
    private const val CLASS = 2
    private const val SPLIT = 3
    private const val JMP = 4
    private const val SAVE = 5
    private const val BOL = 6
    private const val EOL = 7
    private const val WORDB = 8
    private const val NWORDB = 9
    private const val MATCH = 10

    // ---------------------------------------------------------------------------------------------------- AST

    private sealed interface Node

    private class Lit(
        val cp: Int,
    ) : Node

    private data object AnyChar : Node

    private class Cls(
        val ranges: IntArray,
        val negated: Boolean,
    ) : Node

    private class Cat(
        val items: List<Node>,
    ) : Node

    private class Alt(
        val items: List<Node>,
    ) : Node

    private class Rep(
        val node: Node,
        val min: Int,
        val max: Int, // -1 = unbounded
    ) : Node

    private class Group(
        val node: Node,
        val index: Int, // 0 = non-capturing
    ) : Node

    private class Assertion(
        val op: Int,
    ) : Node

    private class Reject(
        val reason: RejectReason,
        val escapeChar: Int? = null,
    ) : Exception(null, null, false, false)

    // ------------------------------------------------------------------------------------------------- parser

    private val DIGIT = intArrayOf('0'.code, '9'.code)
    private val WORD = intArrayOf('0'.code, '9'.code, 'A'.code, 'Z'.code, '_'.code, '_'.code, 'a'.code, 'z'.code)
    private val SPACE = intArrayOf(9, 13, ' '.code, ' '.code)

    private fun complement(ranges: IntArray): IntArray {
        val out = ArrayList<Int>()
        var next = 0
        var i = 0
        while (i < ranges.size) {
            if (ranges[i] > next) {
                out += next
                out += ranges[i] - 1
            }
            next = ranges[i + 1] + 1
            i += 2
        }
        if (next <= MAX_CODE_POINT) {
            out += next
            out += MAX_CODE_POINT
        }
        return out.toIntArray()
    }

    private class Parser(
        val cps: IntArray,
    ) {
        var pos = 0
        var groups = 0
        var depth = 0

        fun parse(): Node {
            val n = alternation()
            if (pos < cps.size) throw Reject(RejectReason.UNBALANCED) // a stray ')'
            return n
        }

        private fun alternation(): Node {
            val branches = ArrayList<Node>()
            branches += sequence()
            while (pos < cps.size && cps[pos] == '|'.code) {
                pos++
                branches += sequence()
            }
            return if (branches.size == 1) branches[0] else Alt(branches)
        }

        private fun sequence(): Node {
            val items = ArrayList<Node>()
            while (pos < cps.size && cps[pos] != '|'.code && cps[pos] != ')'.code) {
                val atom = atom()
                items += quantified(atom)
            }
            return if (items.size == 1) items[0] else Cat(items)
        }

        private fun quantified(atom: Node): Node {
            if (pos >= cps.size) return atom
            var min: Int
            var max: Int
            when (cps[pos]) {
                '*'.code -> {
                    min = 0
                    max = -1
                    pos++
                }
                '+'.code -> {
                    min = 1
                    max = -1
                    pos++
                }
                '?'.code -> {
                    min = 0
                    max = 1
                    pos++
                }
                '{'.code -> {
                    val (a, b) = braces()
                    min = a
                    max = b
                }
                else -> return atom
            }
            if (atom is Assertion) throw Reject(RejectReason.BAD_QUANTIFIER)
            if (pos < cps.size) {
                when (cps[pos]) {
                    '?'.code, '+'.code -> throw Reject(RejectReason.LAZY_OR_POSSESSIVE)
                    '*'.code, '{'.code -> throw Reject(RejectReason.BAD_QUANTIFIER)
                }
            }
            return Rep(atom, min, max)
        }

        private fun number(): Int {
            var n = 0L
            var digits = 0
            while (pos < cps.size && cps[pos] in '0'.code..'9'.code) {
                n = n * 10 + (cps[pos] - '0'.code)
                if (n > 1_000_000) n = 1_000_000
                pos++
                digits++
            }
            if (digits == 0) throw Reject(RejectReason.BAD_QUANTIFIER)
            return n.toInt()
        }

        private fun braces(): Pair<Int, Int> {
            pos++ // {
            val min = number()
            var max = min
            if (pos < cps.size && cps[pos] == ','.code) {
                pos++
                max = if (pos < cps.size && cps[pos] == '}'.code) -1 else number()
            }
            if (pos >= cps.size || cps[pos] != '}'.code) throw Reject(RejectReason.BAD_QUANTIFIER)
            pos++
            if (max != -1 && min > max) throw Reject(RejectReason.BAD_QUANTIFIER)
            if (min > MAX_REPEAT || max > MAX_REPEAT) throw Reject(RejectReason.TOO_COMPLEX)
            return min to max
        }

        private fun atom(): Node {
            val c = cps[pos]
            when (c) {
                '('.code -> return group()
                '['.code -> return charClass()
                '.'.code -> {
                    pos++
                    return AnyChar
                }
                '^'.code -> {
                    pos++
                    return Assertion(BOL)
                }
                '$'.code -> {
                    pos++
                    return Assertion(EOL)
                }
                '*'.code, '+'.code, '?'.code, '{'.code -> throw Reject(RejectReason.BAD_QUANTIFIER)
                '\\'.code -> return escape()
            }
            pos++
            return Lit(c)
        }

        private fun group(): Node {
            pos++ // (
            var index = 0
            if (pos < cps.size && cps[pos] == '?'.code) {
                val k = if (pos + 1 < cps.size) cps[pos + 1] else -1
                when (k) {
                    ':'.code -> pos += 2
                    '='.code, '!'.code -> throw Reject(RejectReason.LOOKAROUND)
                    '<'.code -> {
                        val k2 = if (pos + 2 < cps.size) cps[pos + 2] else -1
                        if (k2 == '='.code || k2 == '!'.code) throw Reject(RejectReason.LOOKAROUND)
                        throw Reject(RejectReason.INLINE_FLAGS_OR_NAMED)
                    }
                    else -> throw Reject(RejectReason.INLINE_FLAGS_OR_NAMED)
                }
            } else {
                if (groups >= MAX_GROUPS) throw Reject(RejectReason.TOO_COMPLEX)
                index = ++groups
            }
            if (++depth > MAX_NESTING) throw Reject(RejectReason.TOO_COMPLEX)
            val inner = alternation()
            if (pos >= cps.size || cps[pos] != ')'.code) throw Reject(RejectReason.UNBALANCED)
            pos++
            depth--
            return Group(inner, index)
        }

        private fun escape(): Node {
            pos++ // backslash
            if (pos >= cps.size) throw Reject(RejectReason.BAD_ESCAPE)
            val c = cps[pos++]
            return when (c) {
                'd'.code -> Cls(DIGIT, false)
                'D'.code -> Cls(DIGIT, true)
                'w'.code -> Cls(WORD, false)
                'W'.code -> Cls(WORD, true)
                's'.code -> Cls(SPACE, false)
                'S'.code -> Cls(SPACE, true)
                'b'.code -> Assertion(WORDB)
                'B'.code -> Assertion(NWORDB)
                else -> Lit(simpleEscape(c))
            }
        }

        /** The code point of an escape that denotes one character (shared by classes). */
        private fun simpleEscape(c: Int): Int =
            when {
                c == 'n'.code -> '\n'.code
                c == 't'.code -> '\t'.code
                c == 'r'.code -> '\r'.code
                c == 'f'.code -> 12
                c == 'k'.code -> throw Reject(RejectReason.BACKREFERENCE)
                c in '1'.code..'9'.code -> throw Reject(RejectReason.BACKREFERENCE)
                c < 128 && !Character.isLetterOrDigit(c) -> c
                else -> throw Reject(RejectReason.BAD_ESCAPE, c)
            }

        private fun charClass(): Node {
            pos++ // [
            var negated = false
            if (pos < cps.size && cps[pos] == '^'.code) {
                negated = true
                pos++
            }
            val out = ArrayList<Int>()
            var first = true
            while (true) {
                if (pos >= cps.size) throw Reject(RejectReason.UNBALANCED)
                var c = cps[pos]
                if (c == ']'.code && !first) {
                    pos++
                    break
                }
                first = false
                pos++
                var shorthand: IntArray? = null
                if (c == '\\'.code) {
                    if (pos >= cps.size) throw Reject(RejectReason.BAD_ESCAPE)
                    val e = cps[pos++]
                    shorthand =
                        when (e) {
                            'd'.code -> DIGIT
                            'D'.code -> complement(DIGIT)
                            'w'.code -> WORD
                            'W'.code -> complement(WORD)
                            's'.code -> SPACE
                            'S'.code -> complement(SPACE)
                            else -> null
                        }
                    if (shorthand == null) {
                        if (e == 'b'.code || e == 'B'.code) throw Reject(RejectReason.BAD_ESCAPE, e)
                        c = simpleEscape(e)
                    }
                } else if (c == '['.code) {
                    // POSIX classes and nested sets are not supported; a bare '[' would be ambiguous
                    throw Reject(RejectReason.BAD_ESCAPE, c)
                }
                if (shorthand != null) {
                    for (v in shorthand) out += v
                    continue
                }
                var hi = c
                if (pos + 1 < cps.size && cps[pos] == '-'.code && cps[pos + 1] != ']'.code) {
                    pos++ // -
                    var h = cps[pos++]
                    if (h == '\\'.code) {
                        if (pos >= cps.size) throw Reject(RejectReason.BAD_ESCAPE)
                        val e = cps[pos++]
                        if (e in "dDwWsSbB".map { it.code }) throw Reject(RejectReason.BAD_CLASS_RANGE)
                        h = simpleEscape(e)
                    } else if (h == '['.code) {
                        throw Reject(RejectReason.BAD_ESCAPE, h)
                    }
                    if (h < c) throw Reject(RejectReason.BAD_CLASS_RANGE)
                    hi = h
                }
                out += c
                out += hi
            }
            return Cls(out.toIntArray(), negated)
        }
    }

    // ----------------------------------------------------------------------------------------------- compiler

    private const val LIMIT = MAX_INSTRUCTIONS + 1L

    private fun sat(v: Long): Long = if (v > LIMIT) LIMIT else v

    /** The program size of [n], computed without expanding anything; saturates just above the limit. */
    private fun size(n: Node): Long =
        when (n) {
            is Lit, AnyChar, is Cls, is Assertion -> 1
            is Cat -> sat(n.items.sumOf { size(it) })
            is Alt -> sat(n.items.sumOf { size(it) } + 2L * (n.items.size - 1))
            is Group -> sat(size(n.node) + if (n.index > 0) 2 else 0)
            is Rep -> {
                val x = size(n.node)
                sat(
                    when {
                        n.max == -1 && n.min == 0 -> x + 2
                        n.max == -1 -> n.min * x + 1
                        else -> n.min * x + (n.max - n.min) * (x + 1)
                    },
                )
            }
        }

    private class Emitter(
        val matchCase: Boolean,
    ) {
        val out = ArrayList<Inst>()

        fun add(i: Inst): Int {
            out += i
            return out.size - 1
        }

        fun patch(
            at: Int,
            x: Int = out[at].x,
            y: Int = out[at].y,
        ) {
            val o = out[at]
            out[at] = Inst(o.op, x, y, o.ranges, o.negated, o.fold)
        }

        fun emit(n: Node) {
            when (n) {
                is Lit -> add(Inst(CHAR, if (matchCase) n.cp else fold(n.cp), fold = !matchCase))
                AnyChar -> add(Inst(ANY))
                is Cls -> add(Inst(CLASS, ranges = n.ranges, negated = n.negated, fold = !matchCase))
                is Assertion -> add(Inst(n.op))
                is Cat -> n.items.forEach { emit(it) }
                is Group -> {
                    if (n.index > 0) add(Inst(SAVE, 2 * n.index))
                    emit(n.node)
                    if (n.index > 0) add(Inst(SAVE, 2 * n.index + 1))
                }
                is Alt -> {
                    val jumps = ArrayList<Int>()
                    for ((k, branch) in n.items.withIndex()) {
                        if (k < n.items.size - 1) {
                            val split = add(Inst(SPLIT))
                            emit(branch)
                            jumps += add(Inst(JMP))
                            patch(split, split + 1, out.size)
                        } else {
                            emit(branch)
                        }
                    }
                    for (j in jumps) patch(j, out.size)
                }
                is Rep -> emitRep(n)
            }
        }

        private fun emitRep(n: Rep) {
            repeat(n.min - if (n.max == -1 && n.min > 0) 1 else 0) { emit(n.node) }
            if (n.max == -1) {
                if (n.min == 0) {
                    val split = add(Inst(SPLIT))
                    emit(n.node)
                    add(Inst(JMP, split))
                    patch(split, split + 1, out.size)
                } else {
                    val start = out.size
                    emit(n.node)
                    val split = add(Inst(SPLIT))
                    patch(split, start, out.size)
                }
            } else {
                val splits = ArrayList<Int>()
                repeat(n.max - n.min) {
                    val split = add(Inst(SPLIT))
                    splits += split
                    emit(n.node)
                }
                for (s in splits) patch(s, s + 1, out.size)
            }
        }
    }

    private fun fold(cp: Int): Int = Character.toLowerCase(Character.toUpperCase(cp))

    /** Compiles [pattern]; never throws and never runs the pattern. */
    fun compile(
        pattern: String,
        matchCase: Boolean,
        wholeWord: Boolean,
    ): Compiled {
        if (pattern.isEmpty()) return Compiled.Rejected(RejectReason.EMPTY)
        if (pattern.length > MAX_PATTERN_CHARS) return Compiled.Rejected(RejectReason.TOO_COMPLEX)
        return try {
            val parser = Parser(pattern.codePoints().toArray())
            var root = parser.parse()
            if (wholeWord) root = Cat(listOf(Assertion(WORDB), Group(root, 0), Assertion(WORDB)))
            // Save(0) + Save(1) + Match
            if (size(root) + 3 > MAX_INSTRUCTIONS) return Compiled.Rejected(RejectReason.TOO_COMPLEX)
            val e = Emitter(matchCase)
            e.add(Inst(SAVE, 0))
            e.emit(root)
            e.add(Inst(SAVE, 1))
            e.add(Inst(MATCH))
            Compiled.Ok(Program(e.out.toTypedArray(), 2 * (parser.groups + 1)), parser.groups)
        } catch (r: Reject) {
            Compiled.Rejected(r.reason, r.escapeChar)
        }
    }

    /** [literal] with every metacharacter escaped, so it matches itself as a pattern. */
    fun quote(literal: String): String {
        val sb = StringBuilder(literal.length + 8)
        for (ch in literal) {
            if (ch in "\\^$.|?*+()[]{}") sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------------------- VM

    private fun isWord(cp: Int): Boolean =
        cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code || cp in '0'.code..'9'.code || cp == '_'.code

    private fun inRanges(
        ranges: IntArray,
        cp: Int,
    ): Boolean {
        var i = 0
        while (i < ranges.size) {
            if (cp >= ranges[i] && cp <= ranges[i + 1]) return true
            i += 2
        }
        return false
    }

    private fun classMatches(
        inst: Inst,
        cp: Int,
    ): Boolean {
        val r = inst.ranges!!
        var hit = inRanges(r, cp)
        if (!hit && inst.fold) {
            hit = inRanges(r, Character.toLowerCase(cp)) || inRanges(r, Character.toUpperCase(cp))
        }
        return hit != inst.negated
    }

    private class Counter(
        val budget: Long,
    ) {
        var steps = 0L
        val exhausted: Boolean get() = steps > budget
    }

    /** One run of the VM at a time; the buffers are reused for every search of one [findAll]. */
    private class Vm(
        val text: String,
        val p: Program,
        val tracked: Int,
        val counter: Counter,
    ) {
        val n = p.size
        val emptyCaps = IntArray(tracked) { -1 } // never mutated: SAVE copies before it writes
        val visited = IntArray(n)
        var gen = 0
        val stackPc = IntArray(n * 2 + 4)
        val stackCaps = arrayOfNulls<IntArray>(n * 2 + 4)
        var curPc = IntArray(n)
        var curCaps = arrayOfNulls<IntArray>(n)
        var curSize = 0
        var nextPc = IntArray(n)
        var nextCaps = arrayOfNulls<IntArray>(n)
        var nextSize = 0

        fun cpAt(pos: Int): Int = if (pos < text.length) text.codePointAt(pos) else -1

        fun cpBefore(pos: Int): Int = if (pos > 0) text.codePointBefore(pos) else -1

        fun assertion(
            op: Int,
            pos: Int,
        ): Boolean =
            when (op) {
                BOL -> pos == 0 || text[pos - 1] == '\n'
                EOL ->
                    pos == text.length ||
                        text[pos] == '\n' ||
                        (text[pos] == '\r' && pos + 1 < text.length && text[pos + 1] == '\n')
                WORDB -> isWord(cpBefore(pos)) != isWord(cpAt(pos))
                else -> isWord(cpBefore(pos)) == isWord(cpAt(pos))
            }

        /** Follows the empty transitions from [pc0]; consuming instructions and MATCH land in the next list. */
        fun addThread(
            toNext: Boolean,
            pc0: Int,
            pos: Int,
            caps0: IntArray,
        ) {
            var sp = 0
            stackPc[sp] = pc0
            stackCaps[sp] = caps0
            sp++
            while (sp > 0) {
                sp--
                var pc = stackPc[sp]
                var caps = stackCaps[sp]!!
                stackCaps[sp] = null
                while (true) {
                    if (visited[pc] == gen) break
                    visited[pc] = gen
                    counter.steps++
                    val inst = p.insts[pc]
                    when (inst.op) {
                        JMP -> pc = inst.x
                        SPLIT -> {
                            stackPc[sp] = inst.y
                            stackCaps[sp] = caps
                            sp++
                            pc = inst.x
                        }
                        SAVE -> {
                            if (inst.x < tracked) {
                                caps = caps.copyOf()
                                caps[inst.x] = pos
                            }
                            pc++
                        }
                        BOL, EOL, WORDB, NWORDB -> if (assertion(inst.op, pos)) pc++ else break
                        else -> {
                            if (toNext) {
                                nextPc[nextSize] = pc
                                nextCaps[nextSize++] = caps
                            } else {
                                curPc[curSize] = pc
                                curCaps[curSize++] = caps
                            }
                            break
                        }
                    }
                }
            }
        }

        fun consumes(
            inst: Inst,
            cp: Int,
        ): Boolean =
            when (inst.op) {
                CHAR -> if (inst.fold) fold(cp) == inst.x else cp == inst.x
                ANY -> cp != '\n'.code && cp != '\r'.code
                else -> classMatches(inst, cp)
            }

        /**
         * The leftmost-first match at or after [from] ([anchored]: only at [from]); the capture slots, `null` when there
         * is none. Stops early (returning `null`) when the budget is gone or [cancel] says so.
         */
        fun search(
            from: Int,
            anchored: Boolean,
            cancel: () -> Boolean,
            cancelled: BooleanArray,
        ): IntArray? {
            var matched: IntArray? = null
            curSize = 0
            gen++
            var pos = from
            var nextCheck = from + CHECK_INTERVAL
            while (true) {
                if (matched == null && (!anchored || pos == from)) {
                    addThread(false, 0, pos, emptyCaps)
                }
                if (curSize == 0 && (matched != null || anchored)) break
                if (counter.exhausted) return null
                if (pos >= nextCheck) {
                    if (!cancel()) {
                        cancelled[0] = true
                        return null
                    }
                    nextCheck = pos + CHECK_INTERVAL
                }
                val cp = cpAt(pos)
                val width = if (cp < 0) 0 else Character.charCount(cp)
                gen++
                nextSize = 0
                var i = 0
                while (i < curSize) {
                    val inst = p.insts[curPc[i]]
                    counter.steps++
                    if (inst.op == MATCH) {
                        matched = curCaps[i]
                        break // lower-priority threads lose
                    }
                    if (cp >= 0 && consumes(inst, cp)) {
                        addThread(true, curPc[i] + 1, pos + width, curCaps[i]!!)
                    }
                    i++
                }
                for (k in 0 until curSize) curCaps[k] = null
                // swap
                val tp = curPc
                curPc = nextPc
                nextPc = tp
                val tc = curCaps
                curCaps = nextCaps
                nextCaps = tc
                curSize = nextSize
                if (cp < 0) {
                    // end of text: only a MATCH thread can still be waiting
                    for (k in 0 until curSize) {
                        if (p.insts[curPc[k]].op == MATCH) {
                            matched = curCaps[k]
                            break
                        }
                    }
                    break
                }
                pos += width
            }
            return matched
        }
    }

    /**
     * All matches of [p] in [text], leftmost-first, non-overlapping, at most [maxMatches]; `null` when [shouldContinue]
     * cancelled. Empty matches advance by one code point, and an empty match right after a non-empty one is dropped.
     */
    internal fun findAll(
        text: String,
        p: Program,
        maxMatches: Int,
        stepBudget: Long = STEP_BUDGET,
        captures: Boolean = false,
        shouldContinue: () -> Boolean = { true },
    ): MatchRun? {
        val counter = Counter(stepBudget)
        val vm = Vm(text, p, if (captures) p.slotCount else 2, counter)
        val starts = IntList()
        val ends = IntList()
        val groups = if (captures) IntList() else null
        var truncated = false
        var pos = 0
        var prevEnd = -1
        val cancelled = BooleanArray(1)
        var nextCheck = CHECK_INTERVAL
        while (pos <= text.length) {
            if (pos >= nextCheck) {
                if (!shouldContinue()) return null
                nextCheck = pos + CHECK_INTERVAL
            }
            val caps = vm.search(pos, false, shouldContinue, cancelled)
            if (cancelled[0]) return null
            if (caps == null) break
            val s = caps[0]
            val e = caps[1]
            if (e == s && s == prevEnd) {
                pos = s + (if (s < text.length) Character.charCount(text.codePointAt(s)) else 1)
                continue
            }
            if (starts.size >= maxMatches) {
                truncated = true
                break
            }
            starts.add(s)
            ends.add(e)
            if (groups != null) for (k in 0 until p.slotCount) groups.add(caps[k])
            if (e > s) {
                prevEnd = e
                pos = e
            } else {
                pos = s + (if (s < text.length) Character.charCount(text.codePointAt(s)) else 1)
            }
        }
        return MatchRun(
            starts.toArray(),
            ends.toArray(),
            groups?.toArray(),
            truncated,
            counter.exhausted,
            counter.steps,
        )
    }

    /** The capture slots of the match starting exactly at [start], or `null` (also when the step budget runs out). */
    internal fun groupsAt(
        text: String,
        p: Program,
        start: Int,
        stepBudget: Long = STEP_BUDGET,
    ): IntArray? {
        val vm = Vm(text, p, p.slotCount, Counter(stepBudget))
        return vm.search(start, true, { true }, BooleanArray(1))
    }

    private class IntList {
        var a = IntArray(16)
        var size = 0

        fun add(v: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun toArray(): IntArray = a.copyOf(size)
    }
}
