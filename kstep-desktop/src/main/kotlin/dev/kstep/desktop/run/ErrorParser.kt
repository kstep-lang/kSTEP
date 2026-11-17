package dev.kstep.desktop.run

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** What the one JSON line of `kstep render --output json` says (ADR-0051, F3). */
sealed interface RenderReport {
    /** A geometry GLB was written; [glbTriangles] triangles. */
    data class Geometry(
        val glbTriangles: Int,
    ) : RenderReport

    /** The script ran but has no geometry (e.g. a product-structure-only script). */
    data object NoGeometry : RenderReport

    /** Geometry was requested but replaced by a notice ([reason], and whether OCCT was available). */
    data class Notice(
        val reason: String,
        val occtAvailable: Boolean,
        val occtReason: String?,
    ) : RenderReport

    data class Failed(
        val errors: List<DesktopError>,
    ) : RenderReport

    data class Unreadable(
        val detail: String,
    ) : RenderReport
}

/**
 * Turns the render worker's JSON document into [RenderReport]s. Pure; never throws on malformed input (a script, not
 * the desktop, wrote part of the text: it is hostile input, ADR-0051).
 */
object ErrorParser {
    const val MAX_MESSAGE_CHARS: Int = 300
    const val MAX_ERRORS: Int = 20

    private val lineColumnInMessage = Regex("""\.kstep\.kts:(\d+)(?::(\d+))?""")
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun parseRenderJson(lastLine: String): RenderReport {
        val obj =
            try {
                Json.parseToJsonElement(lastLine) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return RenderReport.Unreadable("the render result is not a JSON object")
        return when (obj.str("status")) {
            "success" -> parseSuccess(obj)
            "error" -> parseFailure(obj)
            else -> RenderReport.Unreadable("the render result has no status")
        }
    }

    private fun parseSuccess(obj: JsonObject): RenderReport {
        val occt = obj["occt"] as? JsonObject
        val occtAvailable = (occt?.get("available") as? JsonPrimitive)?.contentOrNull == "true"
        return when (obj.str("content")) {
            "geometry" -> {
                val glb = obj["glb"] as? JsonObject
                RenderReport.Geometry((glb?.get("triangleCount") as? JsonPrimitive)?.intOrNull ?: 0)
            }
            "summary" -> RenderReport.NoGeometry
            "notice" ->
                RenderReport.Notice(
                    reason = obj.str("fallbackReason") ?: "unknown",
                    occtAvailable = occtAvailable,
                    occtReason = occt?.str("reason"),
                )
            else -> RenderReport.Unreadable("the render result has an unknown content kind")
        }
    }

    private fun parseFailure(obj: JsonObject): RenderReport {
        val code = obj.str("code")
        return when (obj.str("errorKind")) {
            "compilation_error" -> {
                val diagnostics = obj["diagnostics"] as? JsonArray ?: JsonArray(emptyList())
                val errors =
                    diagnostics.mapNotNull { it as? JsonObject }.map { d ->
                        DesktopError(
                            code = code ?: "KSTEP-S-001",
                            line = d.int("line"),
                            column = d.int("column"),
                            message = sanitize(d.str("message") ?: "compilation error"),
                            severity = if (d.str("severity") == "WARNING") Severity.WARNING else Severity.ERROR,
                        )
                    }
                RenderReport.Failed(
                    normalize(errors.ifEmpty { listOf(error(code ?: "KSTEP-S-001", "compilation error")) }),
                )
            }
            "validation_failed" -> {
                val violations = obj["violations"] as? JsonArray ?: JsonArray(emptyList())
                val errors =
                    violations.mapNotNull { it as? JsonObject }.map { v ->
                        error(
                            v.str("code") ?: "KSTEP-V-000",
                            sanitize("${v.str("entityName") ?: "?"}: ${v.str("message") ?: "validation failed"}"),
                        )
                    }
                RenderReport.Failed(normalize(errors.ifEmpty { listOf(error("KSTEP-V-000", "validation failed")) }))
            }
            "runtime_error" -> {
                val message = obj.str("message") ?: "script failed"
                val match = lineColumnInMessage.find(message)
                RenderReport.Failed(
                    normalize(
                        listOf(
                            DesktopError(
                                code = code ?: "KSTEP-S-003",
                                line = match?.groupValues?.get(1)?.toIntOrNull(),
                                column = match?.groupValues?.get(2)?.toIntOrNull(),
                                message = sanitize(message),
                            ),
                        ),
                    ),
                )
            }
            "no_model_produced" ->
                RenderReport.Failed(
                    listOf(
                        error(
                            code ?: "KSTEP-S-002",
                            sanitize(
                                obj.str("message") ?: "no model produced",
                            ),
                        ),
                    ),
                )
            "mesh_export_invalid" -> {
                val message = obj.str("message").orEmpty()
                val errorCode =
                    if (message.contains("triangle count", ignoreCase = true) || message.contains("triangles")) {
                        DesktopErrorCodes.TOO_MANY_TRIANGLES
                    } else {
                        DesktopErrorCodes.RESULT_UNREADABLE
                    }
                RenderReport.Failed(listOf(error(errorCode, sanitize(message.ifBlank { "invalid mesh" }))))
            }
            "io_error" ->
                RenderReport.Failed(
                    listOf(
                        error(
                            DesktopErrorCodes.RESULT_UNREADABLE,
                            sanitize(
                                obj.str("message") ?: "I/O error",
                            ),
                        ),
                    ),
                )
            else ->
                RenderReport.Failed(listOf(error(DesktopErrorCodes.RESULT_UNREADABLE, "unknown render error")))
        }
    }

    /** Removes control characters (except tab) and ANSI escapes, then cuts to [MAX_MESSAGE_CHARS] with an ellipsis. */
    fun sanitize(s: String): String {
        val cleaned =
            ansi.replace(s, "").filter {
                it == '\t' ||
                    (it.code >= 0x20 && it.code != 0x7f && it.code !in 0x80..0x9f)
            }
        return if (cleaned.length > MAX_MESSAGE_CHARS) cleaned.take(MAX_MESSAGE_CHARS) + "…" else cleaned
    }

    /** Stable sort by (line, column), entries without a position last; at most [MAX_ERRORS]. */
    fun normalize(errors: List<DesktopError>): List<DesktopError> =
        errors.sortedWith(compareBy({ it.line ?: Int.MAX_VALUE }, { it.column ?: Int.MAX_VALUE })).take(MAX_ERRORS)

    private fun error(
        code: String,
        message: String,
    ) = DesktopError(code, null, null, message)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
