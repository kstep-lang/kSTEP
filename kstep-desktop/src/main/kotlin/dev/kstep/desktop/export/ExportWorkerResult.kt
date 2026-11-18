package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopError
import java.nio.file.Path

/** What one export worker run produced (ADR-0057). The caller owns [Produced.file] and deletes it. */
sealed interface ExportWorkerResult {
    data class Produced(
        val file: Path,
    ) : ExportWorkerResult

    data class Failure(
        val error: DesktopError,
    ) : ExportWorkerResult
}
