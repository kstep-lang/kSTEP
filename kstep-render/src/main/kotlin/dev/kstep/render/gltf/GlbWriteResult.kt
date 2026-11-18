package dev.kstep.render.gltf

/**
 * Result of a [GlbWriter] call: the finished GLB bytes plus the counters `kstep render --output
 * json` reports and that an external glTF validator can cross-check (see
 * docs/adr/ADR-0016-gltf-glb-export.adoc's Stolperfallen section).
 *
 * Deliberately NOT a `data class` -- for exactly the reason
 * [dev.kstep.geometry.TriangleMesh] is not one: [bytes] is a [ByteArray], which has reference
 * `equals()`/`hashCode()`, so a generated data-class pair here would be subtly broken (two
 * byte-identical GLB documents would compare unequal).
 */
class GlbWriteResult(
    val bytes: ByteArray,
    /** Triangles actually emitted into the document -- the input mesh's triangle count minus
     *  [droppedTriangleCount]. Zero for a geometry-free document (see [GlbWriter.writeEmptyScene]). */
    val triangleCount: Int,
    /** Always `triangleCount * 3` -- this writer is non-indexed and shares no vertices between
     *  triangles (flat shading needs none), so vertex count is triangle count times three. */
    val vertexCount: Int,
    /** Triangles dropped from the input mesh because they were degenerate (zero-length normal)
     *  or carried a non-finite or float32-unrepresentable coordinate -- see
     *  [GlbWriter]'s KDoc. */
    val droppedTriangleCount: Int,
    /**
     * Ascending source-mesh triangle indices of the triangles actually written (GLB triangle `i`
     * came from source triangle `keptSourceTriangles[i]`). Empty for the empty scene. Not a data
     * class: [IntArray]/[ByteArray] equality is by identity.
     */
    val keptSourceTriangles: IntArray = IntArray(0),
) {
    /** The size of [bytes], in bytes -- what `kstep render --output json` reports as
     *  `glb.byteLength`. */
    val byteLength: Int get() = bytes.size
}
