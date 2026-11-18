package dev.kstep.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.run.Severity

private val BLOCK_BAR = Color(0xFF3B82F6)

/**
 * The line-number column (ADR-0052). Only the visible lines are drawn, positioned from the fixed line height shared
 * with the text field, so the cost does not depend on the file size. A 3 dp bar marks a line with an error or warning;
 * it is hollow while the buffer has unsaved changes (the position refers to the saved file).
 */
@Composable
internal fun EditorGutter(
    lineCount: Int,
    marks: Map<Int, Severity>,
    stale: Boolean,
    scroll: ScrollState,
    lineHeightPx: Float,
    style: TextStyle,
    measurer: TextMeasurer,
    width: Dp,
    modifier: Modifier = Modifier,
    /** Knowledge workspace (ADR-0058): the line range of every `kstep` block; `true` marks the shown one. */
    blockBars: List<Pair<IntRange, Boolean>> = emptyList(),
) {
    Canvas(modifier.width(width).fillMaxHeight().clipToBounds()) {
        val offset = scroll.value.toFloat()
        val first = (offset / lineHeightPx).toInt().coerceAtLeast(0)
        val last = minOf(lineCount - 1, ((offset + size.height) / lineHeightPx).toInt())
        val barWidth = 3.dp.toPx()
        val blockBarWidth = 2.dp.toPx()
        for ((range, active) in blockBars) {
            val top = (range.first - 1) * lineHeightPx - offset
            val bottom = range.last * lineHeightPx - offset
            if (bottom < 0f || top > size.height) continue
            drawRect(
                BLOCK_BAR.copy(alpha = if (active) 1f else 0.4f),
                Offset(size.width - blockBarWidth, top),
                Size(blockBarWidth, bottom - top),
            )
        }
        for (index in first..last) {
            val line = index + 1
            val top = index * lineHeightPx - offset
            val layout = measurer.measure(line.toString(), style)
            drawText(layout, topLeft = Offset(size.width - layout.size.width - 6.dp.toPx(), top))
            val severity = marks[line] ?: continue
            val color = if (severity == Severity.ERROR) MARK_ERROR else MARK_WARNING
            if (stale) {
                drawRect(
                    color,
                    Offset(0.5f, top + 0.5f),
                    Size(barWidth - 1f, lineHeightPx - 1f),
                    style = Stroke(1.dp.toPx()),
                )
            } else {
                drawRect(color, Offset(0f, top), Size(barWidth, lineHeightPx))
            }
        }
    }
}
