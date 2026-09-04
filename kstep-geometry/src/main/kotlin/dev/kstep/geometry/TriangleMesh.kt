package dev.kstep.geometry

/**
 * A triangle "soup": a flat, index-free coordinate array, 9 [Double]s per triangle (`x0, y0,
 * z0, x1, y1, z1, x2, y2, z2`), vertices already in world coordinates (each face's
 * `TopLoc_Location` is applied natively) and with corrected winding (a `REVERSED` face's
 * triangle order is swapped natively) -- the normal `n = (v1 - v0) x (v2 - v0)` therefore always
 * points OUTWARD. See [dev.kstep.geometry.occt.OcctBridge.nativeShapeTriangles] and
 * [OcctShape.triangulate].
 *
 * Index-free, because flat shading needs no shared vertices, and the native triangle-count
 * guard ([OcctKernel.MAX_TRIANGLES]) then bounds exactly one quantity instead of two.
 * `DoubleArray`, not `FloatArray`: the rest of this bridge consistently uses `Double`/
 * `DoubleArray` at the native boundary (`nativeShapeVolume`, `nativeExtrudeProfile`), and a
 * signed-volume regression check over this data would lose precision it does not need to lose.
 *
 * A plain class (not `data class`, deliberately): `DoubleArray` has reference `equals()`/
 * `hashCode()`, so a generated `data class` `equals()`/`hashCode()` pair would be subtly
 * broken (two meshes with identical coordinates would compare unequal). [init] instead enforces
 * this type's real invariants.
 *
 * @property vertexNormals `null` (the default) for a mesh with no per-vertex normal information
 *   -- every consumer (rasterizer, SVG writer, viewer, GLB writer) falls back to its existing
 *   flat-shading-from-face-normal path in that case, byte-identical to this type's pre-Folge-Welle
 *   behavior. When non-null, parallel to [coordinates] -- exactly as long, laid out the same way
 *   (`n0x, n0y, n0z, n1x, n1y, n1z, n2x, n2y, n2z` per triangle, one normal per *corner*, not one
 *   per triangle), so vertex `k` of triangle `t` in [coordinates] and vertex `k` of triangle `t`
 *   in [vertexNormals] always refer to the same corner. Produced by [OcctShape.triangulate] via
 *   [dev.kstep.geometry.occt.OcctBridge.nativeShapeTriangles]'s header-prefixed return layout --
 *   see that function's KDoc and docs/adr/ADR-0018-smooth-vertex-normals.adoc. All-or-nothing: a
 *   mesh either carries a full set of per-vertex normals or none at all -- there is no
 *   partially-filled state (see [dev.kstep.geometry.MeshComposition.merge]'s identical
 *   all-or-nothing rule for a merged multi-part mesh).
 */
class TriangleMesh(
    val coordinates: DoubleArray,
    val vertexNormals: DoubleArray? = null,
) {
    init {
        require(coordinates.size % 9 == 0) {
            "coordinates.size must be a multiple of 9, got ${coordinates.size}"
        }
        require(vertexNormals == null || vertexNormals.size == coordinates.size) {
            "vertexNormals must be null or exactly as long as coordinates (${coordinates.size}), " +
                "got ${vertexNormals?.size}"
        }
    }

    /** Number of triangles represented by [coordinates]. */
    val triangleCount: Int get() = coordinates.size / 9

    /** `true` if this mesh carries per-vertex normals (see [vertexNormals]'s KDoc). */
    val hasVertexNormals: Boolean get() = vertexNormals != null
}
