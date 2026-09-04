package dev.kstep.geometry

/** One [TriangleMesh], paired with the [Placement] it should be shown at in a merged world mesh
 *  -- see [MeshComposition.merge] -- and, since kSTEP's viewer-pan-and-material-colors wave (see
 *  docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc), the [MeshColor] this part should be
 *  rendered in via [MeshComposition.mergeColored]. [placement] defaults to [Placement.IDENTITY]
 *  (a part at the world origin needs no explicit placement) and [color] defaults to
 *  [MeshColor.NEUTRAL] (colorless, bit-identical to pre-wave grayscale) -- both are trailing
 *  parameters with defaults, so every pre-wave `PlacedMesh(mesh)`/`PlacedMesh(mesh, placement)`
 *  call site keeps compiling unchanged. */
data class PlacedMesh(
    val mesh: TriangleMesh,
    val placement: Placement = Placement.IDENTITY,
    val color: MeshColor = MeshColor.NEUTRAL,
)

/** Returns a new [TriangleMesh] with every vertex of this mesh transformed by [placement]. Always
 *  allocates a fresh coordinate array (even for [Placement.IDENTITY]) -- this is a plain data
 *  transformation, not an identity-preserving cache. If this mesh carries [TriangleMesh.vertexNormals],
 *  they are transformed too, via [Placement.applyToDirection] -- NEVER [Placement.apply] --
 *  because a normal is a direction, not a point, and must rotate with the mesh without being
 *  translated by it (see [Placement]'s own KDoc). */
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
    val sourceNormals = vertexNormals
    val outNormals =
        if (sourceNormals == null) {
            null
        } else {
            val n = DoubleArray(sourceNormals.size)
            var j = 0
            while (j < sourceNormals.size) {
                val rotated = placement.applyToDirection(sourceNormals[j], sourceNormals[j + 1], sourceNormals[j + 2])
                n[j] = rotated[0]
                n[j + 1] = rotated[1]
                n[j + 2] = rotated[2]
                j += 3
            }
            n
        }
    return TriangleMesh(out, outNormals)
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
    /**
     * @throws IllegalArgumentException if the combined triangle count of [parts] would exceed
     *   [OcctKernel.MAX_TRIANGLES] -- checked BEFORE allocating the merged coordinate array, so a
     *   maliciously long [parts] list cannot force a large allocation before this guard runs.
     *
     * Vertex normals: the merged mesh carries [TriangleMesh.vertexNormals] ONLY if every single
     * part's mesh has them ([TriangleMesh.hasVertexNormals] `== true` for all of `parts`) --
     * mixing a part with normals and a part without collapses the whole merge to `null` rather
     * than producing a partially-filled normals array (same all-or-nothing rule
     * [TriangleMesh.vertexNormals]'s own KDoc documents for a single mesh).
     */
    fun merge(parts: List<PlacedMesh>): TriangleMesh {
        if (parts.isEmpty()) return TriangleMesh(DoubleArray(0))

        val totalTriangles = parts.sumOf { it.mesh.triangleCount.toLong() }
        require(totalTriangles <= OcctKernel.MAX_TRIANGLES) {
            "merged triangle count must be at most ${OcctKernel.MAX_TRIANGLES}, got $totalTriangles"
        }

        val allHaveNormals = parts.all { it.mesh.hasVertexNormals }
        val out = DoubleArray((totalTriangles * 9L).toInt())
        val outNormals = if (allHaveNormals) DoubleArray((totalTriangles * 9L).toInt()) else null
        var offset = 0
        for (part in parts) {
            val transformed = part.mesh.transformedBy(part.placement)
            System.arraycopy(transformed.coordinates, 0, out, offset, transformed.coordinates.size)
            if (outNormals != null) {
                System.arraycopy(transformed.vertexNormals!!, 0, outNormals, offset, transformed.vertexNormals.size)
            }
            offset += transformed.coordinates.size
        }
        return TriangleMesh(out, outNormals)
    }

    /**
     * Like [merge], but also tracks each part's [PlacedMesh.color] as a per-triangle palette
     * lookup on the returned [ColoredMesh] -- added in kSTEP's viewer-pan-and-material-colors
     * wave (see docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc). Reuses [merge] itself for the
     * actual coordinate merge (including its `MAX_TRIANGLES` guard) rather than duplicating that
     * logic, so [merge]'s own behavior/guarantees cannot silently drift out of sync between the
     * two functions.
     *
     * The palette is deduplicated BY VALUE (`LinkedHashMap<MeshColor, Int>`): two parts sharing
     * the exact same [MeshColor] collapse onto one palette entry, in first-seen order. This is
     * deliberately NOT a per-part index/provenance table -- [ColoredMesh.colorAt] cannot answer
     * "which input part did this triangle come from", only "what color should it be painted" --
     * see [ColoredMesh]'s own KDoc and this wave's ADR for why a provenance table remains a
     * Folge-Welle (V-5b, same deferral [merge]'s own KDoc already names).
     */
    fun mergeColored(parts: List<PlacedMesh>): ColoredMesh {
        val mesh = merge(parts)
        val paletteIndexOf = LinkedHashMap<MeshColor, Int>()
        val triangleColorIndex = IntArray(mesh.triangleCount)
        var cursor = 0
        for (part in parts) {
            val index = paletteIndexOf.getOrPut(part.color) { paletteIndexOf.size }
            repeat(part.mesh.triangleCount) { triangleColorIndex[cursor++] = index }
        }
        return ColoredMesh(mesh, paletteIndexOf.keys.toList(), triangleColorIndex)
    }
}

/**
 * The result of [MeshComposition.mergeColored]: a merged [mesh] plus a value-deduplicated
 * [MeshColor] palette and a palette index per triangle. Constructor is `internal` -- only
 * [MeshComposition.mergeColored] can build one, mirroring [Placement]'s own closed-construction
 * pattern -- so a caller in another module can never hand-assemble a [ColoredMesh] whose
 * `triangleColorIndex` size disagrees with `mesh.triangleCount`.
 *
 * Deliberately carries NO per-part index/provenance -- [colorAt] answers "what color is triangle
 * N", never "which input part did triangle N come from"; two identically-colored parts are
 * indistinguishable through this type by design (see [MeshComposition.mergeColored]'s KDoc and
 * docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc's Folge-Wellen, V-5b).
 */
class ColoredMesh internal constructor(
    val mesh: TriangleMesh,
    private val palette: List<MeshColor>,
    private val triangleColorIndex: IntArray,
) {
    init {
        require(triangleColorIndex.size == mesh.triangleCount) {
            "triangleColorIndex.size must equal mesh.triangleCount, got " +
                "${triangleColorIndex.size} vs ${mesh.triangleCount}"
        }
    }

    /** The [MeshColor] triangle [triangleIndex] (0-based, into [mesh]'s triangle soup) should be
     *  rendered in. */
    fun colorAt(triangleIndex: Int): MeshColor = palette[triangleColorIndex[triangleIndex]]
}
