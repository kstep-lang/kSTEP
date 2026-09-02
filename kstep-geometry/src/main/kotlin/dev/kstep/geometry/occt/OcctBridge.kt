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
}
