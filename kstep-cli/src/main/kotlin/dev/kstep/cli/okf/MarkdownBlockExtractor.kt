package dev.kstep.cli.okf

import java.util.Locale

enum class BlockState { OK, EMPTY, UNCLOSED, TOO_LARGE }

enum class BlockNameIssueKind { INVALID, DUPLICATE, MALFORMED }

/** Why the `{name=...}` of a block is not usable (ADR-0071); [value] is the raw value, at most 64 characters. */
data class BlockNameIssue(
    val kind: BlockNameIssueKind,
    val line: Int,
    val firstLine: Int? = null,
    val value: String? = null,
)

/**
 * One fenced `kstep` block. Lines are 1-based; [contentEndLine] is inclusive (empty content: end < start). Offsets are
 * `Char` offsets in the text, ends exclusive. The content excludes the line break before the closing fence.
 */
data class FenceBlock(
    val index: Int,
    val openLine: Int,
    val contentStartLine: Int,
    val contentEndLine: Int,
    val closeLine: Int?,
    val contentStart: Int,
    val contentEnd: Int,
    val openLineRange: IntRange,
    val closeLineRange: IntRange?,
    val state: BlockState,
    val name: String? = null,
    val nameIssue: BlockNameIssue? = null,
    val unknownKeys: List<String> = emptyList(),
    val attrUnterminated: Boolean = false,
)

/** A fenced block of another language: only kept so the highlighter can dim it. */
data class OtherFence(
    val openLine: Int,
    val closeLine: Int?,
    val start: Int,
    val end: Int,
)

data class Extraction(
    val blocks: List<FenceBlock>,
    val others: List<OtherFence>,
    val tooManyBlocks: Boolean,
    val bodyStartLine: Int,
)

/**
 * Finds the fenced ```` ```kstep ```` blocks of a Markdown note (ADR-0058, D11) in ONE linear pass: no `split`, no
 * regular expression over a line of any length, every count capped. Top-level fences only (CommonMark: 0-3 spaces of
 * indent, backtick or tilde, a closing fence at least as long). Not a Markdown parser: fences inside list items or block
 * quotes are not recognised.
 */
object MarkdownBlockExtractor {
    private class Fence(
        val char: Char,
        val length: Int,
        val afterRun: Int,
    )

    private class Open(
        val fence: Fence,
        val kstep: Boolean,
        val line: Int,
        val lineStart: Int,
        val lineEnd: Int,
        val contentStart: Int,
        val attrs: BlockAttributes.Parsed,
    )

    fun extract(
        text: String,
        bodyStartLine: Int = 1,
    ): Extraction {
        val n = text.length
        val blocks = ArrayList<FenceBlock>()
        val others = ArrayList<OtherFence>()
        var tooMany = false
        val namesSeen = HashMap<String, Int>()
        var open: Open? = null
        var lineStart = 0
        var lineNo = 1
        var prevLineEnd = 0
        var lastLineNo = 0
        val bom = n > 0 && text[0] == '\uFEFF'

        fun finish(
            o: Open,
            closeLine: Int?,
            closeStart: Int,
            closeEnd: Int,
        ) {
            val firstContentLine = o.line + 1
            val lastContentLine = if (closeLine != null) closeLine - 1 else lastLineNo
            val hasContent = lastContentLine >= firstContentLine
            val contentEnd = if (hasContent) prevLineEnd else o.contentStart
            if (!o.kstep) {
                if (others.size < OkfLimits.MAX_OTHER_FENCES) {
                    others += OtherFence(o.line, closeLine, o.lineStart, if (closeLine != null) closeEnd else n)
                }
                return
            }
            if (blocks.size >= OkfLimits.MAX_BLOCKS) {
                tooMany = true
                return
            }
            val state =
                when {
                    isTooLarge(text, o.contentStart, contentEnd) -> BlockState.TOO_LARGE
                    closeLine == null -> BlockState.UNCLOSED
                    isBlank(text, o.contentStart, contentEnd) -> BlockState.EMPTY
                    else -> BlockState.OK
                }
            val attrs = o.attrs
            var name = attrs.name
            var issue: BlockNameIssue? =
                when {
                    attrs.malformed -> BlockNameIssue(BlockNameIssueKind.MALFORMED, o.line)
                    attrs.invalidValue != null ->
                        BlockNameIssue(BlockNameIssueKind.INVALID, o.line, value = attrs.invalidValue)
                    else -> null
                }
            if (name != null) {
                val first = namesSeen.putIfAbsent(name.lowercase(Locale.ROOT), o.line)
                if (first != null) {
                    issue = BlockNameIssue(BlockNameIssueKind.DUPLICATE, o.line, firstLine = first, value = name)
                    name = null
                }
            }
            blocks +=
                FenceBlock(
                    index = blocks.size,
                    openLine = o.line,
                    contentStartLine = firstContentLine,
                    contentEndLine = if (hasContent) lastContentLine else firstContentLine - 1,
                    closeLine = closeLine,
                    contentStart = o.contentStart,
                    contentEnd = contentEnd,
                    openLineRange = o.lineStart until o.lineEnd,
                    closeLineRange = if (closeLine != null) closeStart until closeEnd else null,
                    state = state,
                    name = name,
                    nameIssue = issue,
                    unknownKeys = attrs.unknownKeys,
                    attrUnterminated = attrs.unterminated && issue == null,
                )
        }

        while (lineStart < n) {
            var i = lineStart
            while (i < n && text[i] != '\n' && text[i] != '\r') i++
            val lineEnd = i
            val next =
                when {
                    i >= n -> n
                    text[i] == '\r' && i + 1 < n && text[i + 1] == '\n' -> i + 2
                    else -> i + 1
                }
            val from = if (lineNo == 1 && bom) 1 else lineStart
            if (lineNo >= bodyStartLine) {
                val current = open
                if (current == null) {
                    val fence = parseFence(text, from, lineEnd)
                    if (fence != null && isOpening(text, fence, lineEnd)) {
                        val kstep = isKstepInfo(text, fence.afterRun, lineEnd)
                        open =
                            Open(
                                fence,
                                kstep,
                                lineNo,
                                lineStart,
                                lineEnd,
                                next,
                                if (kstep) {
                                    BlockAttributes.parse(text, kstepEnd(text, fence.afterRun), lineEnd)
                                } else {
                                    BlockAttributes.NONE
                                },
                            )
                    }
                } else {
                    val fence = parseFence(text, from, lineEnd)
                    if (fence != null &&
                        fence.char == current.fence.char &&
                        fence.length >= current.fence.length &&
                        isBlank(text, fence.afterRun, lineEnd)
                    ) {
                        lastLineNo = lineNo - 1
                        finish(current, lineNo, lineStart, lineEnd)
                        open = null
                    }
                }
            }
            lastLineNo = lineNo
            prevLineEnd = lineEnd
            lineStart = next
            lineNo++
        }
        open?.let { finish(it, null, n, n) }
        return Extraction(blocks, others, tooMany, bodyStartLine)
    }

    /** The content of [block] (a view of [text], `\n`-normalised text assumed by the callers). */
    fun content(
        text: String,
        block: FenceBlock,
    ): String = text.substring(block.contentStart, block.contentEnd)

    /** The block whose fence lines or content contain [line]; fence lines count as part of the block. */
    fun blockAtLine(
        ex: Extraction,
        line: Int,
    ): Int? {
        for (b in ex.blocks) {
            val last = b.closeLine ?: maxOf(b.contentEndLine, b.openLine)
            if (line in b.openLine..last) return b.index
        }
        return null
    }

    private fun parseFence(
        text: String,
        from: Int,
        lineEnd: Int,
    ): Fence? {
        var p = from
        var spaces = 0
        while (p < lineEnd && text[p] == ' ' && spaces <= 3) {
            p++
            spaces++
        }
        if (spaces > 3 || p >= lineEnd) return null
        val c = text[p]
        if (c != '`' && c != '~') return null
        var q = p
        while (q < lineEnd && text[q] == c) q++
        val run = q - p
        if (run < 3) return null
        return Fence(c, minOf(run, OkfLimits.MAX_FENCE_RUN), q)
    }

    /** A backtick fence must not have a backtick in its info string (CommonMark); only the first 64 KiB are looked at. */
    private fun isOpening(
        text: String,
        fence: Fence,
        lineEnd: Int,
    ): Boolean {
        if (fence.char != '`') return true
        val stop = minOf(lineEnd.toLong(), fence.afterRun.toLong() + OkfLimits.MAX_INFO_CHARS).toInt()
        for (k in fence.afterRun until stop) if (text[k] == '`') return false
        return true
    }

    private fun isKstepInfo(
        text: String,
        from: Int,
        lineEnd: Int,
    ): Boolean {
        var p = from
        while (p < lineEnd && (text[p] == ' ' || text[p] == '\t')) p++
        if (!text.startsWith("kstep", p)) return false
        val after = p + KSTEP.length
        return after >= lineEnd || text[after] == ' ' || text[after] == '\t' || text[after] == '{'
    }

    private const val KSTEP = "kstep"

    /** The position right behind the `kstep` word of an info string already known to be a kstep one. */
    private fun kstepEnd(
        text: String,
        from: Int,
    ): Int {
        var p = from
        while (text[p] == ' ' || text[p] == '\t') p++
        return p + KSTEP.length
    }

    private fun isBlank(
        text: String,
        from: Int,
        to: Int,
    ): Boolean {
        for (k in from until to) if (!text[k].isWhitespace()) return false
        return true
    }

    private fun isTooLarge(
        text: String,
        from: Int,
        to: Int,
    ): Boolean {
        val chars = to - from
        if (chars > OkfLimits.MAX_BLOCK_BYTES) return true
        if (chars.toLong() * 3 <= OkfLimits.MAX_BLOCK_BYTES) return false
        // close to the limit: the UTF-8 size is what is written to the worker's file
        var bytes = 0L
        var k = from
        while (k < to) {
            val cp = text.codePointAt(k)
            bytes +=
                when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
            k += Character.charCount(cp)
            if (bytes > OkfLimits.MAX_BLOCK_BYTES) return true
        }
        return false
    }
}
