package dev.kstep.geometry

import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.occt.OcctNativeLibrary

/**
 * Public entry point to the OCCT (Open CASCADE Technology) geometry kernel. Every other public
 * type in this module ([OcctShape], [OcctAvailability], [StepSchema], [ShapeTopology]) is either
 * produced by or passed to a function declared here.
 *
 * kSTEP does not vendor, build, or distribute OCCT itself in this wave (see
 * `docs/adr/ADR-0005-occt-jni-bridge.adoc`) -- it dynamically links, at build time, against a
 * system-installed OCCT (Ubuntu package `libocct-*-dev`, LGPL-2.1-only + Open CASCADE Exception
 * 1.0) via a small JNI shim compiled from `src/main/cpp/kstep_occt_bridge.cpp`. When those OCCT
 * headers were absent at build time (or on any platform other than linux-x86-64, the only one
 * built in this wave), the native shim is never compiled, no `.so` classpath resource exists, and
 * [availability] reports [OcctAvailability.Unavailable] -- every function here then fails
 * predictably with [OcctUnavailableException] rather than the build, or this whole module, failing
 * outright. See README's "Building" section for the `-Pkstep.occt.require=true` flag that turns
 * this into a hard build failure instead, for environments that must guarantee OCCT is present.
 */
object OcctKernel {
    /**
     * Minimum accepted box dimension. Guards against degenerate/near-zero-volume shapes reaching
     * native code -- not a physically meaningful unit boundary, just a sanity floor.
     */
    const val MIN_DIMENSION: Double = 1e-7

    /**
     * Maximum accepted box dimension. Guards against unbounded native memory/CPU use from a
     * single call (see docs/adr/ADR-0005-occt-jni-bridge.adoc, Security section, DoS row).
     */
    const val MAX_DIMENSION: Double = 1e7

    /**
     * Whether the native bridge is available on this JVM, and diagnostic detail either way.
     * Resolved once per process and cached -- see [OcctNativeLibrary.availability].
     */
    fun availability(): OcctAvailability = OcctNativeLibrary.availability

    /**
     * The OCCT version string (e.g. `"7.9.2"`) reported by the loaded native library.
     *
     * @throws OcctUnavailableException if the native bridge is not available -- see [availability].
     */
    fun occtVersion(): String =
        when (val a = availability()) {
            is OcctAvailability.Available -> a.occtVersion
            is OcctAvailability.Unavailable -> throw OcctUnavailableException(a.reason, a.cause)
        }

    /**
     * Builds an axis-aligned rectangular box of the given dimensions (OCCT's
     * `BRepPrimAPI_MakeBox`), returning it as an [OcctShape] the caller owns and must
     * [OcctShape.close] (or use via [OcctShape]'s [AutoCloseable] contract with `use { }`).
     *
     * Dimensions are validated *before* any native call is made, so a malformed request never
     * reaches OCCT at all.
     *
     * @throws IllegalArgumentException if any dimension is not finite, not greater than
     *   [MIN_DIMENSION], or exceeds [MAX_DIMENSION].
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT itself rejects the request.
     */
    fun makeBox(
        dx: Double,
        dy: Double,
        dz: Double,
    ): OcctShape {
        validateDimension("dx", dx)
        validateDimension("dy", dy)
        validateDimension("dz", dz)
        val a = availability()
        if (a !is OcctAvailability.Available) {
            val unavailable = a as OcctAvailability.Unavailable
            throw OcctUnavailableException(unavailable.reason, unavailable.cause)
        }
        val handle =
            try {
                OcctBridge.nativeMakeBox(dx, dy, dz)
            } catch (e: RuntimeException) {
                throw OcctGeometryException("OCCT failed to construct box ($dx x $dy x $dz)", e)
            }
        return OcctShape(handle)
    }

    private fun validateDimension(
        name: String,
        value: Double,
    ) {
        require(value.isFinite()) { "$name must be finite, got $value" }
        require(value > MIN_DIMENSION) { "$name must be greater than $MIN_DIMENSION, got $value" }
        require(value <= MAX_DIMENSION) { "$name must be at most $MAX_DIMENSION, got $value" }
    }
}
