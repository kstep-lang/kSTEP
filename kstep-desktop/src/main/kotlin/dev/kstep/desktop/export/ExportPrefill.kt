package dev.kstep.desktop.export

import java.nio.file.Path

/** What the export dialog opens with: format, the marked declared target (if any) and the remembered folder. */
data class Prefill(
    val format: ExportFormat,
    val selectedScriptTarget: Int?,
    val dir: Path?,
)

/**
 * Decides what the dialog is pre-filled with (ADR-0070). Pure. With an `export { }` block in the script the declared
 * target wins: the remembered target is marked if the block still declares it, else the first one, and the format is
 * that target's. Without a block the script's format, else the last format overall, else the default. The block never
 * decides a folder; that is the script's remembered folder, else the last one overall, else `null` (the caller's default).
 */
object ExportPrefill {
    fun resolve(
        scriptTargets: List<ScriptExportTarget>,
        script: ScriptExportPreference?,
        global: ExportPreferences,
    ): Prefill {
        val dir = script?.dir ?: global.lastDir
        if (scriptTargets.isNotEmpty()) {
            val remembered = script?.target
            val index =
                remembered?.let { name -> scriptTargets.indexOfFirst { it.fileName == name } }?.takeIf { it >= 0 } ?: 0
            return Prefill(scriptTargets[index].format, index, dir)
        }
        return Prefill(script?.format ?: global.lastFormat ?: ExportFormat.DEFAULT, null, dir)
    }
}
