package dev.kstep.render.image

import dev.kstep.render.mesh.ProjectedTriangle
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * Headless, plain-JDK rasterizer for a projected triangle list. Draws each triangle in list
 * order (already fern-to-near sorted by
 * [dev.kstep.render.mesh.IsometricProjection.project]) onto a white background.
 *
 * Promoted from `kstep-viewer`'s test-only `internal object` to this module's public `main`
 * source set in kSTEP's headless-preview-rendering wave (see
 * docs/adr/ADR-0011-headless-preview-rendering.adoc): both `kstep-cli`'s `render --format png`
 * and `kstep-viewer`'s own former test suite now share this ONE rasterizer, rather than each
 * module carrying its own copy.
 */
object TriangleRasterizer {
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
