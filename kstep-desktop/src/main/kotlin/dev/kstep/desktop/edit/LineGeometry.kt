package dev.kstep.desktop.edit

/**
 * Where the logical lines of the editor sit, in pixels from the top of the text (ADR-0085). With soft wrap a logical
 * line may take several visual lines, so positions can no longer be computed from one fixed line height; every
 * consumer (gutter, tooltips, error tint, scrolling) asks this one interface. Pure, no Compose.
 */
interface LineGeometry {
    /** Number of logical lines, at least 1. */
    val lineCount: Int

    /** Height of the whole text in pixels. */
    val totalHeight: Float

    /** Top of [line] (1-based, clamped). */
    fun top(line: Int): Float

    /** Full height of [line] including its wrapped continuation lines (1-based, clamped). */
    fun height(line: Int): Float

    /** The logical line at pixel [y] (1-based, clamped to `1..lineCount`). */
    fun lineAt(y: Float): Int
}

/** Every line has the same height: the editor without soft wrap. A height of 0 or less counts as 1 px. */
class FixedLineGeometry(
    lineCount: Int,
    lineHeightPx: Float,
) : LineGeometry {
    override val lineCount: Int = lineCount.coerceAtLeast(1)
    private val lineHeight: Float = if (lineHeightPx > 0f && lineHeightPx.isFinite()) lineHeightPx else 1f
    override val totalHeight: Float get() = lineCount * lineHeight

    override fun top(line: Int): Float = (line.coerceIn(1, lineCount) - 1) * lineHeight

    override fun height(line: Int): Float = lineHeight

    override fun lineAt(y: Float): Int =
        if (y.isNaN() ||
            y <= 0f
        ) {
            1
        } else {
            ((y / lineHeight).toInt() + 1).coerceIn(1, lineCount)
        }
}

/**
 * Measured positions: `tops[i]` is the top of logical line `i + 1`, ascending. The last line ends at [totalHeight].
 * [lineAt] is a binary search.
 */
class TableLineGeometry(
    private val tops: FloatArray,
    override val totalHeight: Float,
) : LineGeometry {
    init {
        require(tops.isNotEmpty()) { "a geometry needs at least one line" }
    }

    override val lineCount: Int get() = tops.size

    override fun top(line: Int): Float = tops[line.coerceIn(1, lineCount) - 1]

    override fun height(line: Int): Float {
        val l = line.coerceIn(1, lineCount)
        val bottom = if (l == lineCount) totalHeight else tops[l]
        return (bottom - tops[l - 1]).coerceAtLeast(0f)
    }

    override fun lineAt(y: Float): Int {
        if (y.isNaN() || y <= tops[0]) return 1
        var lo = 0
        var hi = tops.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    companion object {
        /** A table of [lineCount] lines; the caller guarantees `tops.size == lineCount` (checked). */
        fun of(
            lineCount: Int,
            tops: FloatArray,
            totalHeight: Float,
        ): TableLineGeometry {
            require(tops.size == lineCount) { "tops.size ${tops.size} != lineCount $lineCount" }
            return TableLineGeometry(tops, totalHeight)
        }
    }
}
