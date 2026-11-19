package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopState
import java.nio.file.Path

/**
 * The line after a successful export lives as long as it is true (ADR-0064): it is state, not a toast, and has no clock.
 * It goes away when another file is shown, when a newer run of the preview starts, when the knowledge workspace opens
 * and when the next export dialog opens. Pure.
 */
object ExportNotice {
    class Notice(
        val text: String,
        val dir: Path,
        val file: Path,
        val runGeneration: Long,
    )

    fun onExportSucceeded(
        text: String,
        dir: Path,
        file: Path,
        currentRunGeneration: Long,
    ): Notice = Notice(text, dir, file.toAbsolutePath().normalize(), currentRunGeneration)

    /** The [notice] to keep showing: the same instance, or `null` once one of the four conditions holds. */
    fun retain(
        notice: Notice?,
        currentFile: Path?,
        runState: DesktopState,
        knowledgeOpen: Boolean,
        exportDialogOpened: Boolean,
    ): Notice? =
        when {
            notice == null -> null
            exportDialogOpened || knowledgeOpen -> null
            currentFile?.toAbsolutePath()?.normalize() != notice.file -> null
            runState is DesktopState.Running && runState.generation > notice.runGeneration -> null
            else -> notice
        }
}
