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
 * this type's one real invariant.
 */
class TriangleMesh(
    val coordinates: DoubleArray,
) {
    init {
        require(coordinates.size % 9 == 0) {
            "coordinates.size must be a multiple of 9, got ${coordinates.size}"
        }
    }

    /** Number of triangles represented by [coordinates]. */
    val triangleCount: Int get() = coordinates.size / 9
}
