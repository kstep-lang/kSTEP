package dev.kstep.render.svg

import dev.kstep.render.RenderLimits
import dev.kstep.render.mesh.ProjectedTriangle
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Renders a projected triangle list as a self-contained, deterministic SVG document -- the
 * vector companion to [dev.kstep.render.image.TriangleRasterizer]'s raster output, both
 * consuming the exact same [ProjectedTriangle] list from
 * [dev.kstep.render.mesh.MeshProjection.project].
 *
 * Deterministic on purpose (no timestamp, no random IDs, coordinates rounded to two decimal
 * places): two renders of the same triangle list produce byte-identical output, which is what
 * makes a `kstep render`-produced `.svg` diffable in version control and reproducible in CI.
 */
object TriangleSvgWriter {
    private const val DECIMALS = 2

    /**
     * @param triangles ALREADY in the order they must be painted (far-to-near, as
     *   [dev.kstep.render.mesh.MeshProjection.project] returns them) -- this function does
     *   NOT re-sort, because doing so would reverse the painter's algorithm and show interior
     *   faces (see docs/adr/ADR-0011-headless-preview-rendering.adoc's Stolperfallen).
     */
    fun render(
        triangles: List<ProjectedTriangle>,
        width: Int,
        height: Int,
    ): String {
        // Not a hard cap -- dev.kstep.geometry.OcctKernel.MAX_TRIANGLES already bounds the
        // underlying triangle count natively -- just a logging signal that this particular render
        // is unusually large (e.g. an OcctKernel.MAX_TRIANGLES change or a script previewing an
        // unusually dense mesh), since a `<polygon>`-per-triangle SVG at this size is both a large
        // file and slow for a viewer to render.
        if (triangles.size > RenderLimits.SVG_TRIANGLE_WARN_THRESHOLD) {
            logger.warn {
                "TriangleSvgWriter: rendering ${triangles.size} triangles, above the " +
                    "SVG_TRIANGLE_WARN_THRESHOLD of ${RenderLimits.SVG_TRIANGLE_WARN_THRESHOLD} -- " +
                    "output SVG will be large"
            }
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" " +
                "viewBox=\"0 0 $width $height\">\n",
        )
        sb.append("<rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" fill=\"#ffffff\"/>\n")
        for (t in triangles) {
            // See TriangleRasterizer's identical comment: litR/litG/litB combine shade with the
            // triangle's MeshColor multiplicatively, and are byte-identical to plain shade at
            // MeshColor.NEUTRAL (this file's pre-wave-and-still-default case) -- see
            // docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc.
            val r = (t.litR.coerceIn(0.0, 1.0) * 255.0).toInt()
            val g = (t.litG.coerceIn(0.0, 1.0) * 255.0).toInt()
            val b = (t.litB.coerceIn(0.0, 1.0) * 255.0).toInt()
            val color = String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b)
            val points =
                listOf(t.ax to t.ay, t.bx to t.by, t.cx to t.cy)
                    .joinToString(" ") { (x, y) -> "${round(x)},${round(y)}" }
            // fill + a stroke of the SAME color, one pixel wide: closes the antialiasing seam
            // between adjacent flat-shaded triangles that a fill-only path leaves as a hairline
            // white gap -- see docs/adr/ADR-0011-headless-preview-rendering.adoc's Stolperfallen
            // (mirrors TriangleRasterizer's identical fill-then-stroke choice).
            sb.append("<polygon points=\"$points\" fill=\"$color\" stroke=\"$color\" stroke-width=\"1\"/>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    private fun round(value: Double): String {
        val scaled = Math.round(value * 100.0) / 100.0
        return String.format(Locale.ROOT, "%.${DECIMALS}f", scaled)
    }
}
