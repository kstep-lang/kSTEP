package dev.kstep.geometry.occt

/**
 * Raw JNI declarations for the `kstep_occt_bridge` native library (see
 * `src/main/cpp/kstep_occt_bridge.cpp`).
 *
 * A plain Kotlin `object`, not `@JvmStatic` and not a `companion object` -- the Kotlin compiler
 * emits this as JVM *instance* methods on the object's singleton instance, so every native symbol
 * below carries a `jobject` second parameter
 * (`Java_dev_kstep_geometry_occt_OcctBridge_<name>(JNIEnv*, jobject, ...)`), NOT
 * `..._OcctBridge_00024Companion_...`, which a `companion object` would produce instead. Verify
 * with `nm -D --defined-only <library>.so` after any signature change here.
 *
 * Public (not `internal`) so that [dev.kstep.tests.OcctBridgeSmokeTest] can exercise the raw
 * bridge directly for a scenario [dev.kstep.geometry.OcctKernel]'s own public API can never
 * reach on its own -- an unknown/garbage native handle (`OcctKernel`/[dev.kstep.geometry.OcctShape]
 * only ever hand out handles the native side actually registered). This is still an
 * implementation-detail package by convention, not a stable public API: nothing outside
 * `dev.kstep.geometry` itself (and its own test suite) should depend on it.
 */
object OcctBridge {
    external fun nativeOcctVersion(): String

    external fun nativeMakeBox(
        dx: Double,
        dy: Double,
        dz: Double,
    ): Long

    external fun nativeShapeCounts(handle: Long): IntArray

    external fun nativeShapeVolume(handle: Long): Double

    external fun nativeWriteStep(
        handle: Long,
        absolutePath: String,
        schema: String,
    ): Int

    external fun nativeReleaseShape(handle: Long)

    /**
     * Extrudes a closed planar polygon (XY plane, z = 0) into a solid via
     * `BRepBuilderAPI_MakePolygon` -> `BRepBuilderAPI_MakeFace` -> `BRepPrimAPI_MakePrism`.
     *
     * @param profileXy flat x,y pairs: `[x0, y0, x1, y1, ...]`. Length must be even and in
     *   `[2*OcctKernel.MIN_PROFILE_POINTS, 2*OcctKernel.MAX_PROFILE_POINTS]`. The polygon is
     *   closed by the native side -- the caller must NOT repeat the first point at the end.
     * @param height signed extrusion distance along +Z. Negative is allowed and yields a positive
     *   volume (verified against OCCT 7.9.2).
     * @return a fresh native shape handle.
     */
    external fun nativeExtrudeProfile(
        profileXy: DoubleArray,
        height: Double,
    ): Long

    /**
     * Fillets one or more edges of an existing shape via `BRepFilletAPI_MakeFillet`, returning a
     * NEW shape handle; the input shape is left untouched and still owned by its caller.
     *
     * @param edgeIndices 0-based indices into the same `TopExp::MapShapes(TopAbs_EDGE)` ordering
     *   that backs [dev.kstep.geometry.ShapeTopology.edges]. Converted to OCCT's 1-based map
     *   indexing natively.
     * @param radius fillet radius, same units as the shape.
     */
    external fun nativeFilletEdges(
        handle: Long,
        edgeIndices: IntArray,
        radius: Double,
    ): Long
}
