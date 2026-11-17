package dev.kstep.render.export

/** What a mesh writer produced. A plain class (holds a ByteArray), like GlbWriteResult. */
class MeshExportResult(
    val bytes: ByteArray,
    /** `"stl"` or `"3mf"`. */
    val format: String,
    val triangleCount: Int,
    val vertexCount: Int,
    val droppedTriangleCount: Int,
    val partCount: Int,
    val buildItemCount: Int,
    val watertight: Boolean,
    val openEdgeCount: Int,
    val nonManifoldEdgeCount: Int,
    val colorsDropped: Boolean,
    val warnings: List<String>,
) {
    val byteLength: Int get() = bytes.size
}
