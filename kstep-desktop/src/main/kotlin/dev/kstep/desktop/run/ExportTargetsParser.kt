package dev.kstep.desktop.run

import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportNaming
import dev.kstep.desktop.export.ScriptExportTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Outcome of reading the `exportTargets` of the render worker's JSON (ADR-0063). */
sealed interface ExportTargetsResult {
    /** No `exportTargets` in the result: the dialog behaves as before. */
    data object Absent : ExportTargetsResult

    data class Valid(
        val targets: List<ScriptExportTarget>,
    ) : ExportTargetsResult

    /** Present but not trustworthy: the whole list is dropped and a notice is shown. */
    data object Invalid : ExportTargetsResult
}

/**
 * Reads the declared export names of a script. The worker JSON is hostile input (a script made it): fail closed, the
 * list is checked as a whole (one bad entry drops all), the size is capped before iterating, never throws, and a name
 * is only ever used as a suggested file name, never as a path.
 */
object ExportTargetsParser {
    const val MAX_TARGETS: Int = 4
    const val IGNORED_NOTICE: String = "Script export targets were ignored."

    fun parse(json: JsonElement?): ExportTargetsResult =
        try {
            parseChecked(json)
        } catch (_: RuntimeException) {
            ExportTargetsResult.Invalid
        }

    private fun parseChecked(json: JsonElement?): ExportTargetsResult {
        if (json == null) return ExportTargetsResult.Absent
        val array = json as? JsonArray ?: return ExportTargetsResult.Invalid
        if (array.isEmpty()) return ExportTargetsResult.Absent
        if (array.size > MAX_TARGETS) return ExportTargetsResult.Invalid
        val targets = ArrayList<ScriptExportTarget>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: return ExportTargetsResult.Invalid
            val id = stringOf(obj["format"]) ?: return ExportTargetsResult.Invalid
            val name = stringOf(obj["fileName"]) ?: return ExportTargetsResult.Invalid
            val format = ExportFormat.ALL.firstOrNull { it.workerId == id } ?: return ExportTargetsResult.Invalid
            if (targets.any { it.format == format }) return ExportTargetsResult.Invalid
            if (!ExportNaming.isValidDeclaredName(name, format)) return ExportTargetsResult.Invalid
            targets += ScriptExportTarget(format, name)
        }
        return ExportTargetsResult.Valid(targets)
    }

    private fun stringOf(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }
}
