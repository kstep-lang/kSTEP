package dev.kstep.render.text

import dev.kstep.render.RenderLimits
import dev.kstep.render.svg.SvgEscaping
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * Renders a plain list of text lines (a "card") as either an SVG document or a raster image --
 * the ONE renderer both `kstep render`'s "summary" content (a script with no geometry) and
 * "notice" content (geometry present but not renderable this run) share, so both fall back into
 * the exact same visual container regardless of which format was requested. See
 * docs/adr/ADR-0011-headless-preview-rendering.adoc's "Container-Regel".
 */
object TextCardRenderer {
    private const val LINE_HEIGHT_PX = 18
    private const val LEFT_MARGIN_PX = 12
    private const val TOP_MARGIN_PX = 20
    private const val FONT_SIZE_PX = 13

    /** Truncates [lines] to [RenderLimits.MAX_CARD_LINES]/[RenderLimits.MAX_CARD_LINE_CHARS] --
     *  and, additionally, to however many lines actually fit inside a canvas of [height] px at
     *  [TOP_MARGIN_PX]/[LINE_HEIGHT_PX] spacing -- before rendering. Without the height-based cap,
     *  lines (and even the omitted-count marker itself) beyond the visible area were still drawn
     *  at `y > height`, i.e. present in the SVG/PNG but invisible, so the card silently dropped
     *  content with no indication anything was cut off. Shared by both [toSvg] and [toImage] so
     *  the two containers never disagree about how much text a card actually holds. */
    private fun clip(
        lines: List<String>,
        height: Int,
    ): List<String> {
        val widthClipped =
            lines.map { line ->
                if (line.length > RenderLimits.MAX_CARD_LINE_CHARS) {
                    line.take(RenderLimits.MAX_CARD_LINE_CHARS - 3) + "..."
                } else {
                    line
                }
            }
        if (widthClipped.isEmpty()) return widthClipped
        // Number of lines whose y-coordinate (TOP_MARGIN_PX + index * LINE_HEIGHT_PX) still lands
        // at or below `height` -- 0, not 1, when `height` is below TOP_MARGIN_PX itself (e.g.
        // RenderLimits.MIN_DIMENSION_PX=16 is below TOP_MARGIN_PX=20). The naive
        // `maxOf(0, (height - TOP_MARGIN_PX) / LINE_HEIGHT_PX + 1)` got this wrong: Kotlin's `/`
        // truncates *toward* zero, so for a negative dividend like `16 - 20` it evaluated to 0,
        // +1 = 1 -- a nonzero capacity for a canvas that cannot actually fit a line at
        // TOP_MARGIN_PX at all. That let `maxContentLines` become 0 (all "capacity" reserved for
        // the omitted-count marker) while the marker itself was still drawn at
        // y = TOP_MARGIN_PX, off the bottom edge of the 16-19px-tall canvas -- a "successful"
        // render (exit 0, file written) with zero visible pixels despite real content existing.
        val canvasCapacity = if (height < TOP_MARGIN_PX) 0 else (height - TOP_MARGIN_PX) / LINE_HEIGHT_PX + 1
        if (canvasCapacity <= 0) {
            // Canvas too short to fit even one line at the standard TOP_MARGIN_PX baseline. Draw
            // the first content line anyway -- toSvg/toImage clamp its y-coordinate to stay inside
            // the canvas -- rather than silently writing a blank image with exit code 0.
            return listOf(widthClipped.first())
        }
        // Reserve one line of that capacity for the omitted-count marker itself, so when
        // truncation happens the marker is guaranteed to be the last VISIBLE line rather than
        // falling off the bottom edge along with the content it's reporting on.
        val maxContentLines = minOf(RenderLimits.MAX_CARD_LINES, maxOf(0, canvasCapacity - 1))
        return if (widthClipped.size > maxContentLines) {
            val omitted = widthClipped.size - maxContentLines
            widthClipped.take(maxContentLines) + "... ($omitted more lines omitted)"
        } else {
            widthClipped
        }
    }

    /** The baseline y-coordinate for content line [index], clamped so it never falls outside a
     *  canvas of [height] px -- normally `TOP_MARGIN_PX + index * LINE_HEIGHT_PX`, but [clip]'s
     *  single-line fallback for a canvas shorter than TOP_MARGIN_PX (see its KDoc) needs that one
     *  line pulled up to stay visible instead of being drawn below the canvas. */
    private fun lineY(
        index: Int,
        height: Int,
    ): Int = minOf(TOP_MARGIN_PX + index * LINE_HEIGHT_PX, maxOf(height - 1, 0))

    fun toSvg(
        lines: List<String>,
        width: Int,
        height: Int,
    ): String {
        val clipped = clip(lines, height)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" " +
                "viewBox=\"0 0 $width $height\">\n",
        )
        sb.append("<rect x=\"0\" y=\"0\" width=\"$width\" height=\"$height\" fill=\"#ffffff\"/>\n")
        clipped.forEachIndexed { index, line ->
            val y = lineY(index, height)
            sb.append(
                "<text x=\"$LEFT_MARGIN_PX\" y=\"$y\" font-family=\"monospace\" " +
                    "font-size=\"$FONT_SIZE_PX\" fill=\"#000000\" xml:space=\"preserve\">" +
                    "${SvgEscaping.escape(line)}</text>\n",
            )
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    fun toImage(
        lines: List<String>,
        width: Int,
        height: Int,
    ): BufferedImage {
        val clipped = clip(lines, height)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.color = Color.BLACK
            g.font = Font(Font.MONOSPACED, Font.PLAIN, FONT_SIZE_PX)
            clipped.forEachIndexed { index, line ->
                val y = lineY(index, height)
                g.drawString(line, LEFT_MARGIN_PX, y)
            }
        } finally {
            g.dispose()
        }
        return image
    }
}
