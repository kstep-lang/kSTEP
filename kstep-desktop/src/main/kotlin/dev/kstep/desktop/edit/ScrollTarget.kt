package dev.kstep.desktop.edit

/** Where to scroll so a line is comfortably visible (ADR-0067). Pure arithmetic, no Compose. */
object ScrollTarget {
    const val MARGIN_LINES = 3

    /**
     * `null` when [line] already lies comfortably inside the viewport (do not scroll); otherwise the new scroll value
     * in pixels, clamped to the scrollable range. A comfortable line keeps [marginLines] lines free above and below;
     * in a very small viewport the margin shrinks so a line can always qualify.
     */
    fun scrollFor(
        line: Int,
        lineCount: Int,
        scrollPx: Int,
        viewportPx: Int,
        lineHeightPx: Float,
        marginLines: Int = MARGIN_LINES,
    ): Int? {
        if (lineHeightPx <= 0f || viewportPx <= 0) return null
        val count = lineCount.coerceAtLeast(1)
        val l = line.coerceIn(1, count)
        val viewportLines = (viewportPx / lineHeightPx).toInt()
        val margin = minOf(marginLines.coerceAtLeast(0), ((viewportLines - 1) / 2).coerceAtLeast(0))
        val marginPx = margin * lineHeightPx
        val top = (l - 1).toDouble() * lineHeightPx
        val bottom = top + lineHeightPx
        if (top >= scrollPx + marginPx && bottom <= scrollPx + viewportPx - marginPx) return null
        val max = (count.toDouble() * lineHeightPx - viewportPx).coerceAtLeast(0.0)
        val target = (top - marginPx).coerceIn(0.0, max).toInt()
        return if (target == scrollPx) null else target
    }
}
