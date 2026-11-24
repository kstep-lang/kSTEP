package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertTarget
import java.nio.file.Path
import java.util.Locale

/** What the dialog starts with (ADR-0077): an open script wins over the workspace, which wins over nothing. */
object ConvertPrefill {
    private const val SCRIPT_SUFFIX = ".kstep.kts"

    fun resolve(
        openScript: Path?,
        workspaceRoot: Path?,
        remembered: ConvertPreferences,
    ): ConvertRequest {
        val outDir = remembered.lastOutDir
        val scriptName = openScript?.fileName?.toString()?.lowercase(Locale.ROOT)
        return when {
            openScript != null && scriptName != null && scriptName.endsWith(SCRIPT_SUFFIX) ->
                ConvertRequest(openScript, ConvertTarget.OKF, outDir, recursive = false)
            workspaceRoot != null -> ConvertRequest(workspaceRoot, ConvertTarget.KTS, outDir, recursive = true)
            else -> ConvertRequest(null, remembered.lastTarget ?: ConvertTarget.KTS, outDir, recursive = false)
        }
    }
}
