package dev.kstep.desktop.ui

import androidx.compose.ui.graphics.vector.PathBuilder
import dev.kstep.desktop.menu.MenuIconId

/**
 * The shapes of the Edit and View menu icons (ADR-0074): stroke 1.0 on the 16 x 16 grid, round ends, half pixels on
 * the axis-parallel segments, no fills. The dashed outline of Toggle Transparent is eight single segments.
 */
internal object KstepEditViewIcons {
    fun shapesOf(id: MenuIconId): List<PathBuilder.() -> Unit> =
        when (id) {
            MenuIconId.UNDO -> undoRedo(mirror = false)
            MenuIconId.REDO -> undoRedo(mirror = true)
            MenuIconId.CUT ->
                listOf(
                    {
                        moveTo(2.5f, 12.5f)
                        arcTo(2f, 2f, 0f, false, true, 6.5f, 12.5f)
                        arcTo(2f, 2f, 0f, false, true, 2.5f, 12.5f)
                        close()
                    },
                    {
                        moveTo(9.5f, 12.5f)
                        arcTo(2f, 2f, 0f, false, true, 13.5f, 12.5f)
                        arcTo(2f, 2f, 0f, false, true, 9.5f, 12.5f)
                        close()
                    },
                    {
                        moveTo(5.8f, 10.8f)
                        lineTo(11.5f, 2.5f)
                    },
                    {
                        moveTo(10.2f, 10.8f)
                        lineTo(4.5f, 2.5f)
                    },
                )
            MenuIconId.COPY ->
                listOf(
                    {
                        moveTo(2.5f, 10.5f)
                        verticalLineTo(2.5f)
                        horizontalLineTo(10.5f)
                    },
                    {
                        moveTo(5.5f, 5.5f)
                        horizontalLineTo(13.5f)
                        verticalLineTo(13.5f)
                        horizontalLineTo(5.5f)
                        close()
                    },
                )
            MenuIconId.PASTE ->
                listOf(
                    {
                        moveTo(6.5f, 3.5f)
                        horizontalLineTo(3.5f)
                        verticalLineTo(14.5f)
                        horizontalLineTo(12.5f)
                        verticalLineTo(3.5f)
                        horizontalLineTo(9.5f)
                    },
                    {
                        moveTo(6.5f, 2.5f)
                        horizontalLineTo(9.5f)
                        verticalLineTo(4.5f)
                        horizontalLineTo(6.5f)
                        close()
                    },
                )
            MenuIconId.SELECT_ALL ->
                listOf(
                    {
                        moveTo(2.5f, 5.5f)
                        verticalLineTo(2.5f)
                        horizontalLineTo(5.5f)
                    },
                    {
                        moveTo(10.5f, 2.5f)
                        horizontalLineTo(13.5f)
                        verticalLineTo(5.5f)
                    },
                    {
                        moveTo(13.5f, 10.5f)
                        verticalLineTo(13.5f)
                        horizontalLineTo(10.5f)
                    },
                    {
                        moveTo(5.5f, 13.5f)
                        horizontalLineTo(2.5f)
                        verticalLineTo(10.5f)
                    },
                    {
                        moveTo(5.5f, 6.5f)
                        horizontalLineTo(10.5f)
                        moveTo(5.5f, 9.5f)
                        horizontalLineTo(10.5f)
                    },
                )
            MenuIconId.FIND ->
                listOf(
                    {
                        moveTo(2f, 6.5f)
                        arcTo(4.5f, 4.5f, 0f, false, true, 11f, 6.5f)
                        arcTo(4.5f, 4.5f, 0f, false, true, 2f, 6.5f)
                        close()
                    },
                    {
                        moveTo(10f, 10f)
                        lineTo(14.5f, 14.5f)
                    },
                )
            // Two arrows, one to the right and one to the left.
            MenuIconId.REPLACE ->
                listOf(
                    {
                        moveTo(2.5f, 5.5f)
                        horizontalLineTo(13.5f)
                        moveTo(10.5f, 2.5f)
                        lineTo(13.5f, 5.5f)
                        lineTo(10.5f, 8.5f)
                    },
                    {
                        moveTo(13.5f, 10.5f)
                        horizontalLineTo(2.5f)
                        moveTo(5.5f, 7.5f)
                        lineTo(2.5f, 10.5f)
                        lineTo(5.5f, 13.5f)
                    },
                )
            // Two text lines with an arrow between them.
            MenuIconId.GO_TO_LINE ->
                listOf(
                    {
                        moveTo(2.5f, 3.5f)
                        horizontalLineTo(13.5f)
                        moveTo(2.5f, 12.5f)
                        horizontalLineTo(13.5f)
                    },
                    {
                        moveTo(2.5f, 8.5f)
                        horizontalLineTo(11.5f)
                        moveTo(8.5f, 5.5f)
                        lineTo(11.5f, 8.5f)
                        lineTo(8.5f, 11.5f)
                    },
                )
            MenuIconId.RUN ->
                listOf(
                    {
                        moveTo(4.5f, 2.5f)
                        lineTo(12.5f, 8.5f)
                        lineTo(4.5f, 14.5f)
                        close()
                    },
                )
            MenuIconId.HOME ->
                listOf(
                    {
                        moveTo(1.5f, 8.5f)
                        lineTo(8.5f, 2.5f)
                        lineTo(14.5f, 8.5f)
                    },
                    {
                        moveTo(3.5f, 7.5f)
                        verticalLineTo(13.5f)
                        horizontalLineTo(13.5f)
                        verticalLineTo(7.5f)
                    },
                    {
                        moveTo(6.5f, 13.5f)
                        verticalLineTo(9.5f)
                        horizontalLineTo(10.5f)
                        verticalLineTo(13.5f)
                    },
                )
            MenuIconId.PREVIOUS_BLOCK ->
                listOf(
                    {
                        moveTo(3.5f, 10.5f)
                        lineTo(8.5f, 5.5f)
                        lineTo(13.5f, 10.5f)
                    },
                    {
                        moveTo(3.5f, 3.5f)
                        horizontalLineTo(13.5f)
                    },
                )
            MenuIconId.NEXT_BLOCK ->
                listOf(
                    {
                        moveTo(3.5f, 5.5f)
                        lineTo(8.5f, 10.5f)
                        lineTo(13.5f, 5.5f)
                    },
                    {
                        moveTo(3.5f, 12.5f)
                        horizontalLineTo(13.5f)
                    },
                )
            // A dashed square: eight single segments of length 3 around the square from 2.5 to 13.5.
            MenuIconId.TRANSPARENT ->
                listOf(
                    {
                        moveTo(2.5f, 2.5f)
                        horizontalLineTo(5.5f)
                        moveTo(8.5f, 2.5f)
                        horizontalLineTo(11.5f)
                        moveTo(13.5f, 4.5f)
                        verticalLineTo(7.5f)
                        moveTo(13.5f, 10.5f)
                        verticalLineTo(13.5f)
                        moveTo(11.5f, 13.5f)
                        horizontalLineTo(8.5f)
                        moveTo(5.5f, 13.5f)
                        horizontalLineTo(2.5f)
                        moveTo(2.5f, 11.5f)
                        verticalLineTo(8.5f)
                        moveTo(2.5f, 5.5f)
                        verticalLineTo(4.5f)
                    },
                )
            // A rhombus eye with a slash.
            MenuIconId.HIDE ->
                listOf(
                    { eye() },
                    {
                        moveTo(3.5f, 13.5f)
                        lineTo(12.5f, 2.5f)
                    },
                )
            // Corner brackets around a small square.
            MenuIconId.ISOLATE ->
                listOf(
                    {
                        moveTo(2.5f, 5.5f)
                        verticalLineTo(2.5f)
                        horizontalLineTo(5.5f)
                        moveTo(10.5f, 2.5f)
                        horizontalLineTo(13.5f)
                        verticalLineTo(5.5f)
                        moveTo(13.5f, 10.5f)
                        verticalLineTo(13.5f)
                        horizontalLineTo(10.5f)
                        moveTo(5.5f, 13.5f)
                        horizontalLineTo(2.5f)
                        verticalLineTo(10.5f)
                    },
                    {
                        moveTo(6.5f, 6.5f)
                        horizontalLineTo(9.5f)
                        verticalLineTo(9.5f)
                        horizontalLineTo(6.5f)
                        close()
                    },
                )
            // A rhombus eye with a pupil.
            MenuIconId.SHOW_ALL ->
                listOf(
                    { eye() },
                    {
                        moveTo(6.5f, 8.5f)
                        arcTo(2f, 2f, 0f, false, true, 10.5f, 8.5f)
                        arcTo(2f, 2f, 0f, false, true, 6.5f, 8.5f)
                        close()
                    },
                )
            // A window with a side panel on the right and two text lines in it.
            MenuIconId.AI_PANEL ->
                listOf(
                    {
                        moveTo(2.5f, 2.5f)
                        horizontalLineTo(13.5f)
                        verticalLineTo(13.5f)
                        horizontalLineTo(2.5f)
                        close()
                    },
                    {
                        moveTo(8.5f, 2.5f)
                        verticalLineTo(13.5f)
                        moveTo(10.5f, 5.5f)
                        horizontalLineTo(11.5f)
                        moveTo(10.5f, 8.5f)
                        horizontalLineTo(11.5f)
                    },
                )
            else -> error("Not an Edit or View icon: $id")
        }

    private fun PathBuilder.eye() {
        moveTo(1.5f, 8.5f)
        lineTo(8.5f, 3.5f)
        lineTo(14.5f, 8.5f)
        lineTo(8.5f, 13.5f)
        close()
    }

    // A curved arrow: Undo points left, Redo is its mirror image.
    private fun undoRedo(mirror: Boolean): List<PathBuilder.() -> Unit> {
        fun x(v: Float) = if (mirror) 16f - v else v
        return listOf(
            {
                moveTo(x(5.5f), 3.5f)
                lineTo(x(2.5f), 6.5f)
                lineTo(x(5.5f), 9.5f)
            },
            {
                moveTo(x(2.5f), 6.5f)
                horizontalLineTo(x(10.5f))
                arcTo(3f, 3f, 0f, false, !mirror, x(10.5f), 12.5f)
                horizontalLineTo(x(6.5f))
            },
        )
    }
}
