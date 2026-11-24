package dev.kstep.desktop.run

import dev.kstep.script.ScriptInputLimits
import dev.kstep.script.input.InputPathRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/** What the worker said about the input files of a script (ADR-0080). */
sealed interface DependencyReport {
    /** No `dependencies` in the result (an older worker): no report. */
    data object Absent : DependencyReport

    /** [paths] are absolute, below the real script folder, never the script itself. */
    data class Valid(
        val paths: List<Path>,
    ) : DependencyReport

    /** Present but not trustworthy: the whole list is dropped. */
    data object Invalid : DependencyReport
}

/**
 * Reads the `dependencies` of the render worker's JSON. The worker JSON is hostile input (a script made it): fail
 * closed, the list is judged as a whole, the size is checked before iterating, every entry is resolved again with the
 * very same [InputPathRules] the script helper uses (against the real script folder), and nothing is ever opened here.
 * Never throws.
 */
object DependencyListParser {
    const val IGNORED_NOTICE: String =
        "Dependency list ignored; changes of input files will not refresh the preview."

    fun parse(
        json: JsonElement?,
        script: Path,
    ): DependencyReport =
        try {
            parseChecked(json, script)
        } catch (_: Exception) {
            DependencyReport.Invalid
        }

    private fun parseChecked(
        json: JsonElement?,
        script: Path,
    ): DependencyReport {
        if (json == null) return DependencyReport.Absent
        val array = json as? JsonArray ?: return DependencyReport.Invalid
        if (array.size > ScriptInputLimits.MAX_INPUT_FILES) return DependencyReport.Invalid
        val baseReal =
            script
                .toAbsolutePath()
                .normalize()
                .parent
                .toRealPath()
        val scriptReal = baseReal.resolve(script.fileName.toString())
        val seen = HashSet<String>()
        val paths = ArrayList<Path>(array.size)
        for (element in array) {
            val primitive = element as? JsonPrimitive ?: return DependencyReport.Invalid
            if (!primitive.isString) return DependencyReport.Invalid
            val relative = primitive.content
            if (!seen.add(relative)) return DependencyReport.Invalid
            val resolution = InputPathRules.resolve(baseReal, relative)
            val ok = resolution as? InputPathRules.Resolution.Ok ?: return DependencyReport.Invalid
            if (ok.absolute != scriptReal) paths.add(ok.absolute)
        }
        return DependencyReport.Valid(paths)
    }
}
