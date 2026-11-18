package dev.kstep.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.pick.PartMode
import dev.kstep.desktop.pick.VisibilityArrays

private const val VISIBLE_ALPHA = 0.35f

/**
 * The visibility glyph of a tree row (ADR-0055), drawn on a 14 dp grid, no icon font. [state] is a node state:
 * [PartMode] code `0..2` or [VisibilityArrays.MIXED]. Open eye for visible (quiet, the normal case), dashed outline with
 * a half pupil for transparent, crossed out for hidden, a bar instead of the pupil for mixed.
 */
@Composable
internal fun EyeGlyph(
    state: Byte,
    color: Color,
) {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val c = size.height / 2f
        val stroke = 1.2.dp.toPx()
        val tint = if (state == PartMode.VISIBLE) color.copy(alpha = VISIBLE_ALPHA) else color
        val almond =
            Path().apply {
                moveTo(0.5f, c)
                quadraticTo(w / 2f, c - w * 0.57f, w - 0.5f, c)
                quadraticTo(w / 2f, c + w * 0.57f, 0.5f, c)
                close()
            }
        val dashed = if (state == PartMode.TRANSPARENT) PathEffect.dashPathEffect(floatArrayOf(2.5f, 2f)) else null
        drawPath(almond, tint, style = Stroke(width = stroke, pathEffect = dashed))
        val pupil = w * 0.17f
        when (state) {
            PartMode.VISIBLE -> drawCircle(tint, radius = pupil, center = Offset(w / 2f, c))
            PartMode.TRANSPARENT ->
                // a half-filled pupil: the ring, and the lower half as a disc
                drawArc(
                    tint,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(w / 2f - pupil, c - pupil),
                    size = Size(pupil * 2f, pupil * 2f),
                )
            PartMode.HIDDEN ->
                drawLine(
                    tint,
                    Offset(w * 0.12f, c * 2f - 1f),
                    Offset(w * 0.88f, 1f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            else ->
                drawLine(tint, Offset(w * 0.3f, c), Offset(w * 0.7f, c), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
