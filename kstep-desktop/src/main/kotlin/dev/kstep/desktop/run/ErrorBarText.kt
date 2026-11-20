package dev.kstep.desktop.run

import dev.kstep.render.RenderLimits
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The texts of the error bar and the status line (ADR-0051). Pure and locale independent. */
object ErrorBarText {
    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

    private fun position(e: DesktopError): String =
        when {
            e.line != null && e.column != null -> "${e.line}:${e.column}"
            e.line != null -> "${e.line}"
            else -> ""
        }

    private fun line(e: DesktopError): String =
        listOf(e.code, position(e), e.message)
            .filter {
                it.isNotEmpty()
            }.joinToString("  ")

    private const val TOOLTIP_ERRORS = 3
    private const val TOOLTIP_LINE_CHARS = 120

    /** `Z:S`, `Z`, or a dash when the error has no position; for display only. */
    fun displayPosition(e: DesktopError): String = position(e).ifEmpty { "—" }

    /** A line of the bar for display: the position is a dash when unknown; the text is cleaned. */
    fun displayLine(e: DesktopError): String =
        NameSanitizer.cleanText(
            listOf(e.code, displayPosition(e), e.message).filter { it.isNotEmpty() }.joinToString("  "),
        )

    fun displayHeadline(errors: List<DesktopError>): String = errors.firstOrNull()?.let(::displayLine) ?: ""

    private fun clipLine(s: String): String {
        if (s.length <= TOOLTIP_LINE_CHARS) return s
        var end = TOOLTIP_LINE_CHARS - 1
        if (s[end - 1].isHighSurrogate() && s[end].isLowSurrogate()) end--
        return s.substring(0, end) + "…"
    }

    /** The gutter tooltip: `CODE · Z:S · message` for at most three errors, then `+N more`. */
    fun tooltip(errors: List<DesktopError>): String {
        val lines =
            errors.take(TOOLTIP_ERRORS).map { e ->
                val code = NameSanitizer.cleanText(e.code)
                val msg = NameSanitizer.cleanText(e.message)
                clipLine("$code · ${displayPosition(e)} · $msg")
            }
        val more = errors.size - TOOLTIP_ERRORS
        return (if (more > 0) lines + "+$more more" else lines).joinToString("\n")
    }

    /** Tooltip text per 1-based line, in the order of [errors]; lines outside 1..[lineCount] are ignored. */
    fun tooltipsByLine(
        errors: List<DesktopError>,
        lineCount: Int,
    ): Map<Int, String> =
        errors
            .filter { (it.line ?: 0) in 1..lineCount }
            .groupBy { it.line!! }
            .mapValues { tooltip(it.value) }

    /** The first error as `KSTEP-S-001  12:5  message`. */
    fun headline(errors: List<DesktopError>): String = errors.firstOrNull()?.let(::line) ?: ""

    /** `+N more` when there are further errors, else `null`. */
    fun moreLabel(errors: List<DesktopError>): String? = if (errors.size > 1) "+${errors.size - 1} more" else null

    /** All errors, one per line, for the clipboard. */
    fun copyText(errors: List<DesktopError>): String = errors.joinToString("\n", transform = ::line)

    fun statusOk(
        durationMs: Long,
        triangles: Int,
    ): String = "● Ok · $durationMs ms · " + String.format(Locale.ROOT, "%,d", triangles) + " triangles"

    fun lastGoodBadge(
        at: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = "Showing last good result · " + clock.format(at.atZone(zone))

    /** A hint once the preview has [RenderLimits.GLB_TRIANGLE_WARN_THRESHOLD] triangles or more, else `null`. */
    fun triangleWarning(triangles: Int): String? =
        if (triangles >= RenderLimits.GLB_TRIANGLE_WARN_THRESHOLD) {
            "Large model (" + String.format(Locale.ROOT, "%,d", triangles) + " triangles): the preview may be slow"
        } else {
            null
        }
}
