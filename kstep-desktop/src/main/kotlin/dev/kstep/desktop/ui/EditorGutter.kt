package dev.kstep.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private const val MAX_TOOLTIP_AREAS = 200

/**
 * Hover tooltips for the marked gutter lines (ADR-0067): one transparent area per marked, visible line, so the empty
 * gutter has no tooltip. Only the lines in view are laid out, at most [MAX_TOOLTIP_AREAS].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GutterTooltips(
    tooltips: Map<Int, String>,
    scroll: ScrollState,
    lineHeightPx: Float,
    width: Dp,
) {
    if (tooltips.isEmpty()) return
    val density = LocalDensity.current
    val lineHeight = with(density) { lineHeightPx.toDp() }
    BoxWithConstraints(Modifier.width(width).fillMaxHeight().clipToBounds()) {
        val heightPx = with(density) { maxHeight.toPx() }
        val firstLine = (scroll.value / lineHeightPx).toInt() + 1
        val lastLine = ((scroll.value + heightPx) / lineHeightPx).toInt() + 1
        var shown = 0
        for ((line, text) in tooltips) {
            if (line < firstLine || line > lastLine) continue
            if (++shown > MAX_TOOLTIP_AREAS) break
            TooltipArea(
                tooltip = {
                    Surface(shape = RoundedCornerShape(4.dp), shadowElevation = 4.dp) {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(6.dp),
                        )
                    }
                },
                delayMillis = 500,
                modifier = Modifier.offset { IntOffset(0, ((line - 1) * lineHeightPx - scroll.value).toInt()) },
            ) {
                Box(Modifier.width(width).height(lineHeight))
            }
        }
    }
}
