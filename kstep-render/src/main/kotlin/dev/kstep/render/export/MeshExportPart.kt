package dev.kstep.render.export

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.RenderLimits

/**
 * One distinct (mesh, colour) pair of an export together with every placement it is instanced at.
 * The mesh stays in LOCAL coordinates; [placements] move it into the world. A plain class (holds arrays).
 */
class MeshExportPart(
    rawName: String,
    val mesh: TriangleMesh,
    val color: MeshColor,
    val placements: List<Placement>,
) {
    /** Sanitised (ADR-0049 D6); may be empty, [MeshExportScene.nameOf] supplies the fallback. */
    val name: String = ExportNames.sanitize(rawName, "")
}

/** The unit a mesh writer exports. Construction enforces the part/build-item caps. */
class MeshExportScene(
    title: String,
    val parts: List<MeshExportPart>,
) {
    val title: String = ExportNames.sanitize(title, "kSTEP model")

    init {
        require(parts.isNotEmpty()) { "nothing to export" }
        require(parts.size <= RenderLimits.EXPORT_MAX_PARTS) {
            "mesh export refused: ${parts.size} parts exceed the limit of ${RenderLimits.EXPORT_MAX_PARTS}"
        }
        var items = 0L
        for (p in parts) {
            require(p.placements.isNotEmpty()) { "mesh export refused: part without a placement" }
            items += p.placements.size
        }
        require(items <= RenderLimits.EXPORT_MAX_BUILD_ITEMS) {
            "mesh export refused: $items build items exceed the limit of ${RenderLimits.EXPORT_MAX_BUILD_ITEMS}"
        }
    }

    fun nameOf(index: Int): String = parts[index].name.ifEmpty { "part-${index + 1}" }

    val buildItemCount: Int get() = parts.sumOf { it.placements.size }
}
