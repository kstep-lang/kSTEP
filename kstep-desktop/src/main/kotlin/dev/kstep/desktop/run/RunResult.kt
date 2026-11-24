package dev.kstep.desktop.run

import dev.kstep.desktop.export.ScriptExportTarget
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.geometry.ColoredMesh
import java.time.Instant

/** A successfully evaluated script. [mesh] is `null` for a script without geometry. */
data class DesktopModel(
    val mesh: ColoredMesh?,
    val triangles: Int,
    val generation: Long,
    val finishedAt: Instant,
    val durationMs: Long,
    val notes: List<String>,
    /** The validated part list, or `null` (no list, or an invalid one: see [warnings]); selection is off then. */
    val parts: PartTable? = null,
    /** Non-fatal problems of this model, e.g. an invalid part list (`KSTEP-D-012`). */
    val warnings: List<DesktopError> = emptyList(),
    /** The validated assembly hierarchy over [parts] (ADR-0054), or `null` (none, or invalid: see [warnings]). */
    val tree: AssemblyTree? = null,
    /** File names the script declared in its `export { }` block (ADR-0063), validated; empty without a block. */
    val scriptExportTargets: List<ScriptExportTarget> = emptyList(),
)

/** The end of one run of the render worker. */
sealed interface RunResult {
    /**
     * The input files the script read, as absolute paths below its folder (ADR-0080). `null` means the run gave no
     * report (timeout, crash, start failure, skipped); an empty list is a real report of "none".
     */
    val dependencies: List<java.nio.file.Path>? get() = null

    data class Success(
        val model: DesktopModel,
        override val dependencies: List<java.nio.file.Path>? = null,
    ) : RunResult

    data class Failure(
        val errors: List<DesktopError>,
        override val dependencies: List<java.nio.file.Path>? = null,
    ) : RunResult

    /**
     * Nothing to run yet (a knowledge-workspace note without a block to show, ADR-0058). Not an error: the state becomes
     * [DesktopState.Waiting] and the last good model stays on screen.
     */
    data class Skipped(
        val hint: String,
    ) : RunResult

    /** The native OCCT bridge is not available in the worker: no geometry can be shown. */
    data class NativeUnavailable(
        val reason: String,
        override val dependencies: List<java.nio.file.Path>? = null,
    ) : RunResult
}
