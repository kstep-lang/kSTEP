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
