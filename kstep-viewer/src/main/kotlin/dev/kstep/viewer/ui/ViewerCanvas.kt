package dev.kstep.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.kstep.geometry.TriangleMesh
import dev.kstep.viewer.mesh.IsometricProjection

/**
 * Renders [mesh] as a static isometric projection, painter's-algorithm-ordered and flat-shaded.
 * Uses the exact same [IsometricProjection.project] call as `ViewerRasterTest`'s headless
 * rasterizer -- no second, Compose-only projection path to drift out of sync with it.
 */
@Composable
fun ShapeCanvas(
    mesh: TriangleMesh,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        // IsometricProjection.project() requires a positive canvas size (see its own `require`).
        // A layout pass can legitimately hand this scope a zero-sized `size` -- e.g. a modifier
        // that (temporarily) collapses to 0 width/height before the surrounding layout settles --
        // and that must draw nothing for this frame, not throw out of the Compose draw phase and
        // take the whole window down with it. See docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc.
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val triangles = IsometricProjection.project(mesh, size.width.toDouble(), size.height.toDouble())
        for (t in triangles) {
            val path =
                Path().apply {
                    moveTo(t.ax.toFloat(), t.ay.toFloat())
                    lineTo(t.bx.toFloat(), t.by.toFloat())
                    lineTo(t.cx.toFloat(), t.cy.toFloat())
                    close()
                }
            val shade = t.shade.toFloat()
            val color = Color(shade, shade, shade)
            drawPath(path, color, style = Fill)
            // Same color, 1px stroke -- closes the antialiasing seams between coplanar
            // neighboring triangles (Atkinson's "haarriss" fix, see ADR-0010).
            drawPath(path, color, style = Stroke(width = 1f))
        }
    }
}
