package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.ErrorParser

/** The readable strings of the export dialog (ADR-0057). Pure. Names only, never full paths. */
object ExportText {
    private const val KB = 1_000L
    private const val MB = 1_000_000L

    /** Base 1000, one decimal from kilobytes up: `999 B`, `1.0 KB`, `1.4 MB`. */
    fun formatSize(bytes: Long): String =
        when {
            bytes < KB -> "$bytes B"
            bytes < MB -> "%.1f KB".format(java.util.Locale.ROOT, bytes / KB.toDouble())
            else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / MB.toDouble())
        }

    fun successLine(
        fileName: String,
        bytes: Long,
    ): String = "Exported ${ErrorParser.sanitize(fileName)} (${formatSize(bytes)})"

    fun overwriteLine(
        fileName: String,
        bytes: Long,
    ): String = "${ErrorParser.sanitize(fileName)} already exists (${formatSize(bytes)}). Exporting replaces it."

    fun phaseLine(
        phase: ExportPhase,
        elapsedMs: Long,
        limitMs: Long,
    ): String {
        val label =
            when (phase) {
                ExportPhase.STARTING -> "Starting"
                ExportPhase.RUNNING_SCRIPT -> "Running script"
                ExportPhase.VALIDATING -> "Checking the result"
                ExportPhase.WRITING -> "Writing the file"
            }
        return "$label... ${elapsedMs / 1_000} s of max. ${limitMs / 1_000} s"
    }

    /** [text] shortened to at most [max] characters with an ellipsis in the middle (a long folder path, name kept). */
    fun middleEllipsis(
        text: String,
        max: Int,
    ): String {
        if (text.length <= max || max < MIN_ELLIPSIS_CHARS) return text
        val keepEnd = (max - 1) * 2 / 3
        val keepStart = max - 1 - keepEnd
        return text.take(keepStart) + "…" + text.takeLast(keepEnd)
    }

    private const val MIN_ELLIPSIS_CHARS = 5

    const val STOPPED_NOTICE: String = "Export stopped. Nothing was written."

    /** One line of an error for the dialog: the code and a sanitized message. */
    fun errorLine(error: DesktopError): String = "${error.code}: ${ErrorParser.sanitize(error.message)}"
}
