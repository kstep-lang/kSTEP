package dev.kstep.geometry

/** One [TriangleMesh], paired with the [Placement] it should be shown at in a merged world mesh
 *  -- see [MeshComposition.merge]. [placement] defaults to [Placement.IDENTITY], so a part that
 *  sits at the world origin (e.g. the first/base part of a small assembly) needs no explicit
 *  placement at the call site. */
data class PlacedMesh(
    val mesh: TriangleMesh,
    val placement: Placement = Placement.IDENTITY,
)

/** Returns a new [TriangleMesh] with every vertex of this mesh transformed by [placement]. Always
 *  allocates a fresh coordinate array (even for [Placement.IDENTITY]) -- this is a plain data
 *  transformation, not an identity-preserving cache. */
fun TriangleMesh.transformedBy(placement: Placement): TriangleMesh {
    val source = coordinates
    val out = DoubleArray(source.size)
    var i = 0
    while (i < source.size) {
        val transformed = placement.apply(source[i], source[i + 1], source[i + 2])
        out[i] = transformed[0]
        out[i + 1] = transformed[1]
        out[i + 2] = transformed[2]
        i += 3
    }
    return TriangleMesh(out)
}

/**
 * Merges several placed meshes into ONE world-space [TriangleMesh] -- kSTEP's answer to
 * "multi-shape scenes" for this wave (see `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`):
 * a merged triangle soup, not a `Scene`/scene-graph type. [MeshProjection] (in `kstep-render`)
 * already operates on a single [TriangleMesh] with no notion of "which part a triangle came
 * from" -- merging BEFORE projection means [dev.kstep.render.mesh.MeshProjection] itself needs no
 * change at all to draw a multi-part assembly; it just sees a bigger mesh.
 *
 * Deliberately returns a plain [TriangleMesh], with no per-part offset/provenance table --
 * reconstructing "which merged triangles came from which input part" (needed for a future
 * selection/picking feature, V-6) is explicitly a Folge-Welle (V-5b in that ADR), introduced only
 * once a real caller needs it.
 */
object MeshComposition {
    /** @throws IllegalArgumentException if the combined triangle count of [parts] would exceed
     *   [OcctKernel.MAX_TRIANGLES] -- checked BEFORE allocating the merged coordinate array, so a
     *   maliciously long [parts] list cannot force a large allocation before this guard runs. */
    fun merge(parts: List<PlacedMesh>): TriangleMesh {
        if (parts.isEmpty()) return TriangleMesh(DoubleArray(0))

        val totalTriangles = parts.sumOf { it.mesh.triangleCount.toLong() }
        require(totalTriangles <= OcctKernel.MAX_TRIANGLES) {
            "merged triangle count must be at most ${OcctKernel.MAX_TRIANGLES}, got $totalTriangles"
        }

        val out = DoubleArray((totalTriangles * 9L).toInt())
        var offset = 0
        for (part in parts) {
            val transformed = part.mesh.transformedBy(part.placement)
            System.arraycopy(transformed.coordinates, 0, out, offset, transformed.coordinates.size)
            offset += transformed.coordinates.size
        }
        return TriangleMesh(out)
    }
}
