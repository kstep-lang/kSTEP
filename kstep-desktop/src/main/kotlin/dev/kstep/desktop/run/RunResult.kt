package dev.kstep.desktop.run

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
)

/** The end of one run of the render worker. */
sealed interface RunResult {
    data class Success(
        val model: DesktopModel,
    ) : RunResult

    data class Failure(
        val errors: List<DesktopError>,
    ) : RunResult

    /** The native OCCT bridge is not available in the worker: no geometry can be shown. */
    data class NativeUnavailable(
        val reason: String,
    ) : RunResult
}
