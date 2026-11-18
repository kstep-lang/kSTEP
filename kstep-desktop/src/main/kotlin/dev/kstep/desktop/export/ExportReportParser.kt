package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.ErrorParser
import dev.kstep.desktop.run.RenderReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What the one JSON line of the export worker says (ADR-0057). */
sealed interface ExportReport {
    /** A file with the requested content was written. */
    data object Produced : ExportReport

    /** The script has geometry but the native bridge is missing, so no geometry could be written. */
    data class Unavailable(
        val reason: String,
    ) : ExportReport

    data class Failed(
        val errors: List<DesktopError>,
    ) : ExportReport

    data class Unreadable(
        val detail: String,
    ) : ExportReport
}

/**
 * Judges the JSON line of `kstep export --output json` (STEP) or `kstep render --output json` (mesh formats). Pure and
 * never throws on malformed input: a script wrote part of the text, so it is hostile input.
 *
 * A STEP export without `--require-geometry` silently writes a structure-only file when OCCT is missing, so the JSON's
 * `geometry` object is mandatory evidence here: shapes registered but not merged is [ExportReport.Unavailable]; a script
 * without any shape is a valid structure-only export.
 */
object ExportReportParser {
    fun parse(
        lastLine: String,
        format: ExportFormat,
    ): ExportReport {
        val obj =
            try {
                Json.parseToJsonElement(lastLine) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return ExportReport.Unreadable("the export result is not a JSON object")
        return when (obj.str("status")) {
            "success" -> if (format == ExportFormat.STEP) stepSuccess(obj) else meshSuccess(lastLine)
            "error" -> failure(obj, lastLine)
            else -> ExportReport.Unreadable("the export result has no status")
        }
    }

    private fun stepSuccess(obj: JsonObject): ExportReport {
        val shapeCount = (obj["shapeCount"] as? JsonPrimitive)?.intOrNull ?: 0
        val geometry = obj["geometry"] as? JsonObject
        val merged = (geometry?.get("merged") as? JsonPrimitive)?.booleanOrNull
        if (shapeCount > 0 && merged != true) {
            val reason = geometry?.str("reason") ?: (geometry?.get("occt") as? JsonObject)?.str("reason")
            return ExportReport.Unavailable(reason ?: "the native geometry bridge is unavailable")
        }
        return ExportReport.Produced
    }

    private fun meshSuccess(line: String): ExportReport =
        when (val report = ErrorParser.parseRenderJson(line)) {
            is RenderReport.Geometry -> ExportReport.Produced
            RenderReport.NoGeometry -> failed("the script has no geometry")
            is RenderReport.Notice -> ExportReport.Unavailable(report.occtReason ?: report.reason)
            is RenderReport.Failed -> ExportReport.Failed(report.errors)
            is RenderReport.Unreadable -> ExportReport.Unreadable(report.detail)
        }

    private fun failure(
        obj: JsonObject,
        line: String,
    ): ExportReport {
        val occt = obj["occt"] as? JsonObject
        val occtAvailable = (occt?.get("available") as? JsonPrimitive)?.booleanOrNull
        return when (obj.str("errorKind")) {
            "geometry_unavailable" ->
                if (occtAvailable == false) {
                    ExportReport.Unavailable(occt.str("reason") ?: obj.str("message") ?: "OCCT is unavailable")
                } else {
                    failed("the script has no geometry to export")
                }
            "geometry_required" -> failed("the script has no geometry")
            "geometry_export_failed", "mesh_not_watertight", "mesh_export_invalid", "io_error" ->
                failed(obj.str("message") ?: "the export failed")
            else ->
                when (val report = ErrorParser.parseRenderJson(line)) {
                    is RenderReport.Failed -> ExportReport.Failed(report.errors)
                    is RenderReport.Unreadable -> ExportReport.Unreadable(report.detail)
                    else -> ExportReport.Unreadable("unexpected export result")
                }
        }
    }

    private fun failed(message: String) =
        ExportReport.Failed(
            listOf(DesktopError(DesktopErrorCodes.EXPORT_FAILED, null, null, ErrorParser.sanitize(message))),
        )

    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { ErrorParser.sanitize(it) }
}
