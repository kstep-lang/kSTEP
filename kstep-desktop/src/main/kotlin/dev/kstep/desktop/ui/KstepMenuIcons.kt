package dev.kstep.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.menu.MenuIconId

/**
 * The File menu icons (ADR-0073): 16 x 16 grid, stroke 1.0 with round caps and joins, the usable area is 1.5 to 14.5 and
 * axis-parallel segments lie on a half pixel so that they are sharp. Drawn here from scratch; stroke only, no fills.
 * The colour is a placeholder: the painter tints it ([menuIcon]).
 */
internal object KstepMenuIcons {
    const val GRID = 16f

    private val cache: Map<MenuIconId, ImageVector> by lazy { MenuIconId.entries.associateWith { build(it) } }

    fun vectorFor(id: MenuIconId): ImageVector = cache.getValue(id)

    private fun build(id: MenuIconId): ImageVector =
        ImageVector
            .Builder(
                name = "kstep.menu.${id.name.lowercase()}",
                defaultWidth = GRID.dp,
                defaultHeight = GRID.dp,
                viewportWidth = GRID,
                viewportHeight = GRID,
            ).apply {
                for (shape in shapesOf(id)) strokePath(shape)
            }.build()

    private fun ImageVector.Builder.strokePath(block: PathBuilder.() -> Unit) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun PathBuilder.folder() {
        moveTo(1.5f, 3.5f)
        horizontalLineTo(6.5f)
        lineTo(8.5f, 5.5f)
        horizontalLineTo(14.5f)
        verticalLineTo(13.5f)
        horizontalLineTo(1.5f)
        close()
    }

    private fun shapesOf(id: MenuIconId): List<PathBuilder.() -> Unit> =
        when (id) {
            // A page with a folded corner.
            MenuIconId.OPEN ->
                listOf(
                    {
                        moveTo(3.5f, 1.5f)
                        horizontalLineTo(9.5f)
                        lineTo(12.5f, 4.5f)
                        verticalLineTo(14.5f)
                        horizontalLineTo(3.5f)
                        close()
                    },
                    {
                        moveTo(9.5f, 1.5f)
                        verticalLineTo(4.5f)
                        horizontalLineTo(12.5f)
                    },
                )
            MenuIconId.OPEN_FOLDER -> listOf({ folder() })
            // A clock: a circle of radius 6 around (7.5, 7.5) and two hands.
            MenuIconId.OPEN_RECENT ->
                listOf(
                    {
                        moveTo(1.5f, 7.5f)
                        arcTo(6f, 6f, 0f, false, true, 13.5f, 7.5f)
                        arcTo(6f, 6f, 0f, false, true, 1.5f, 7.5f)
                        close()
                    },
                    {
                        moveTo(7.5f, 4.5f)
                        verticalLineTo(7.5f)
                        horizontalLineTo(10.5f)
                    },
                )
            // A list with a cross.
            MenuIconId.CLEAR_RECENT ->
                listOf(
                    {
                        moveTo(2.5f, 4.5f)
                        horizontalLineTo(9.5f)
                        moveTo(2.5f, 8.5f)
                        horizontalLineTo(9.5f)
                        moveTo(2.5f, 12.5f)
                        horizontalLineTo(9.5f)
                    },
                    {
                        moveTo(11.5f, 6.5f)
                        lineTo(14.5f, 9.5f)
                        moveTo(14.5f, 6.5f)
                        lineTo(11.5f, 9.5f)
                    },
                )
            // A folder with a cross.
            MenuIconId.CLOSE_WORKSPACE ->
                listOf(
                    { folder() },
                    {
                        moveTo(6.5f, 8.5f)
                        lineTo(9.5f, 11.5f)
                        moveTo(9.5f, 8.5f)
                        lineTo(6.5f, 11.5f)
                    },
                )
            // An arrow down into a tray.
            MenuIconId.SAVE ->
                listOf(
                    {
                        moveTo(8.5f, 2.5f)
                        verticalLineTo(10.5f)
                        moveTo(5.5f, 7.5f)
                        lineTo(8.5f, 10.5f)
                        lineTo(11.5f, 7.5f)
                    },
                    {
                        moveTo(2.5f, 10.5f)
                        verticalLineTo(13.5f)
                        horizontalLineTo(14.5f)
                        verticalLineTo(10.5f)
                    },
                )
            // An arrow leaving a box towards the upper right.
            MenuIconId.EXPORT ->
                listOf(
                    {
                        moveTo(6.5f, 3.5f)
                        horizontalLineTo(2.5f)
                        verticalLineTo(13.5f)
                        horizontalLineTo(12.5f)
                        verticalLineTo(9.5f)
                    },
                    {
                        moveTo(7.5f, 8.5f)
                        lineTo(14.5f, 1.5f)
                        moveTo(9.5f, 1.5f)
                        horizontalLineTo(14.5f)
                        verticalLineTo(6.5f)
                    },
                )
            // A door frame with an arrow to the right (not a power symbol).
            MenuIconId.QUIT ->
                listOf(
                    {
                        moveTo(6.5f, 2.5f)
                        horizontalLineTo(2.5f)
                        verticalLineTo(13.5f)
                        horizontalLineTo(6.5f)
                    },
                    {
                        moveTo(6.5f, 8.5f)
                        horizontalLineTo(14.5f)
                        moveTo(11.5f, 5.5f)
                        lineTo(14.5f, 8.5f)
                        lineTo(11.5f, 11.5f)
                    },
                )
            else -> KstepEditViewIcons.shapesOf(id)
        }
}
