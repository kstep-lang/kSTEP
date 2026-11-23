package dev.kstep.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.kstep.desktop.menu.MenuIconId
import dev.kstep.desktop.menu.MenuOs
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.UIManager
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

private val LIGHT_TINT = Color(0xFF2B2B2B)
private val DARK_TINT = Color(0xFFE0E0E0)
private const val DISABLED_ALPHA = 0.35f
private const val ICON_SIZE = 16

/**
 * The colour of a menu icon (ADR-0073). macOS: follows the system appearance (the screen menu bar is drawn by the system).
 * Elsewhere: the foreground colour of the Swing menu, light grey-black when there is none. A disabled entry gets 35 percent.
 */
internal fun menuIconTint(
    isMac: Boolean,
    systemDark: Boolean,
    swingColor: java.awt.Color?,
    enabled: Boolean,
): Color {
    val base =
        when {
            isMac -> if (systemDark) DARK_TINT else LIGHT_TINT
            swingColor != null -> Color(swingColor.red, swingColor.green, swingColor.blue, swingColor.alpha)
            else -> LIGHT_TINT
        }
    return if (enabled) base else base.copy(alpha = base.alpha * DISABLED_ALPHA)
}

/** Draws [delegate] in one colour: every drawn pixel takes the tint, the alpha of the drawing is kept (SrcIn). */
internal class TintedMenuIconPainter(
    private val delegate: Painter,
    private val tint: Color,
) : Painter() {
    override val intrinsicSize: Size = Size(ICON_SIZE.toFloat(), ICON_SIZE.toFloat())

    override fun DrawScope.onDraw() {
        with(delegate) { draw(size, 1f, ColorFilter.tint(tint, BlendMode.SrcIn)) }
    }
}

@Composable
private fun rememberMenuTint(enabled: Boolean): Color {
    val dark = isSystemInDarkTheme()
    val isMac = remember { MenuOs.current() == MenuOs.MAC }
    val swing = if (isMac) null else remember { UIManager.getColor("Menu.foreground") }
    return menuIconTint(isMac, dark, swing, enabled)
}

/** The painter of a menu item icon, or null for an entry without icon. [id] must be constant per call site. */
@Composable
internal fun menuIcon(
    id: MenuIconId?,
    enabled: Boolean,
): Painter? {
    if (id == null) return null
    val tint = rememberMenuTint(enabled)
    val delegate = rememberVectorPainter(KstepMenuIcons.vectorFor(id))
    return remember(delegate, tint) { TintedMenuIconPainter(delegate, tint) }
}

/** The icon at 16 logical pixels, painted from a bitmap of 16 x density pixels so that it stays sharp on Retina. */
private class BitmapMenuIcon(
    private val image: java.awt.Image,
) : Icon {
    override fun getIconWidth(): Int = ICON_SIZE

    override fun getIconHeight(): Int = ICON_SIZE

    override fun paintIcon(
        c: Component?,
        g: Graphics,
        x: Int,
        y: Int,
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.drawImage(image, x, y, ICON_SIZE, ICON_SIZE, null)
        } finally {
            g2.dispose()
        }
    }
}

internal fun renderMenuIcon(
    painter: Painter,
    density: Density,
): BufferedImage {
    val px = (ICON_SIZE * density.density).toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(px, px)
    val scope = CanvasDrawScope()
    scope.draw(density, LayoutDirection.Ltr, ComposeCanvas(bitmap), Size(px.toFloat(), px.toFloat())) {
        with(painter) { draw(size) }
    }
    return bitmap.toAwtImage()
}

/** The same icon as a Swing icon, for the submenu title (the Compose Menu has no icon parameter, ADR-0073). */
@Composable
internal fun menuIconToSwing(
    id: MenuIconId?,
    enabled: Boolean,
): Icon? {
    if (id == null) return null
    val painter = menuIcon(id, enabled) ?: return null
    val density = LocalDensity.current
    return remember(painter, density.density) { BitmapMenuIcon(renderMenuIcon(painter, density)) }
}
