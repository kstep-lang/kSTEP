package dev.kstep.viewer

import dev.kstep.viewer.mesh.ProjectedTriangle
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * Headless, plain-JDK rasterizer for a projected triangle list -- test infrastructure only, this
 * wave (see `build.gradle.kts`'s `java.awt.headless=true` test property). Draws each triangle in
 * list order (already fern-to-near sorted by [dev.kstep.viewer.mesh.IsometricProjection.project])
 * onto a white background, mirroring [dev.kstep.viewer.ui.ShapeCanvas]'s Fill-then-Stroke
 * pattern so the two rendering paths stay visually consistent.
 */
internal object TriangleRasterizer {
    fun render(
        triangles: List<ProjectedTriangle>,
        width: Int,
        height: Int,
    ): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            for (t in triangles) {
                val path = Path2D.Double()
                path.moveTo(t.ax, t.ay)
                path.lineTo(t.bx, t.by)
                path.lineTo(t.cx, t.cy)
                path.closePath()
                val gray = (t.shade.coerceIn(0.0, 1.0) * 255.0).toInt()
                val color = Color(gray, gray, gray)
                g.color = color
                g.fill(path)
                g.draw(path)
            }
        } finally {
            g.dispose()
        }
        return image
    }
}
