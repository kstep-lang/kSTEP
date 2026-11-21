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

    /** Below this size the write is too quick for a percentage to mean anything. */
    const val PROGRESS_PERCENT_MIN_BYTES: Long = 8L shl 20

    private const val PERCENT_CAP = 99L

    /** `Step 4 of 4 · Writing the file · 12 s of max. 120 s`, plus ` · 37 %` while writing a large file. */
    fun stepLine(
        phase: ExportPhase,
        elapsedMs: Long,
        limitMs: Long,
        writtenBytes: Long? = null,
        totalBytes: Long? = null,
    ): String {
        val (number, label) =
            when (phase) {
                ExportPhase.STARTING -> 1 to "Starting"
                ExportPhase.RUNNING_SCRIPT -> 2 to "Running script"
                ExportPhase.VALIDATING -> 3 to "Checking the result"
                ExportPhase.WRITING -> 4 to "Writing the file"
            }
        val base = "Step $number of 4 \u00b7 $label \u00b7 ${elapsedMs / 1_000} s of max. ${limitMs / 1_000} s"
        val percent = if (phase == ExportPhase.WRITING) percent(writtenBytes, totalBytes) else null
        return if (percent != null) "$base \u00b7 $percent %" else base
    }

    /** Whole percent, rounded down, never 100 (that only appears with the success state); `null` when not meaningful. */
    private fun percent(
        written: Long?,
        total: Long?,
    ): Long? {
        if (written == null || total == null || total < PROGRESS_PERCENT_MIN_BYTES) return null
        val fraction = written.coerceIn(0, total).toDouble() / total.toDouble()
        return (fraction * 100).toLong().coerceIn(0, PERCENT_CAP)
    }

    /** The bar's fill, 0..1; `null` means indeterminate. */
    fun progressFraction(
        written: Long?,
        total: Long?,
    ): Float? {
        if (written == null || total == null || total <= 0) return null
        return (written.coerceIn(0, total).toDouble() / total.toDouble()).toFloat()
    }

    const val STOPPED_NOTICE: String = "Export stopped. Nothing was written."

    /** One line of an error for the dialog: the code and a sanitized message. */
    fun errorLine(error: DesktopError): String = "${error.code}: ${ErrorParser.sanitize(error.message)}"
}
