package dev.kstep.geometry

import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.occt.OcctNativeLibrary
import kotlin.math.abs

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

    /** Minimum vertices in an extrusion profile. Two points produce a zero-volume "solid" that
     *  `BRepCheck_Analyzer` nonetheless reports as VALID (measured against OCCT 7.9.2) -- so this
     *  floor, not the native validity check, is what rejects that case. See
     *  docs/adr/ADR-0008-occt-feature-operations.adoc. */
    const val MIN_PROFILE_POINTS: Int = 3

    /** Maximum vertices in an extrusion profile. Measured on OCCT 7.9.2 (see
     *  docs/adr/ADR-0008-occt-feature-operations.adoc): profile construction is linear
     *  (n=1000 -> 16 ms, n=50 000 -> 1057 ms), but the mandatory `BRepCheck_Analyzer` post-check
     *  is superlinear (202 faces -> 27.6 ms, 1002 faces -> 311.5 ms). At n=512 the whole extrude
     *  path measures well under 200 ms, i.e. roughly 5x headroom under one second -- the same
     *  "measure, then leave headroom" method `PlaneGcsSolver.MAX_POINTS` uses. */
    const val MAX_PROFILE_POINTS: Int = 512

    /** Maximum faces the INPUT shape of a fillet may have. This is this module's dominant DoS
     *  lever and is deliberately far stricter than [MAX_PROFILE_POINTS]: a single-edge fillet
     *  costs roughly quadratic time in face count (measured on OCCT 7.9.2, see
     *  docs/adr/ADR-0008-occt-feature-operations.adoc's DoS table). 200 faces is the last
     *  directly-measured point with comfortable headroom under one second.
     *
     *  Consequence, intentional and documented rather than papered over: a solid extruded from a
     *  profile near [MAX_PROFILE_POINTS] cannot be filleted, and fails with a clear
     *  `IllegalArgumentException` naming this constant instead of hanging for tens of seconds. */
    const val MAX_FILLET_INPUT_FACES: Int = 200

    /** Maximum edges filleted in one operation. Measured (see
     *  docs/adr/ADR-0008-occt-feature-operations.adoc) to be a weak cost lever compared to input
     *  size -- bounded anyway. */
    const val MAX_FILLET_EDGES: Int = 64

    /** Bound on any profile coordinate; mirrors [MAX_DIMENSION]'s role for [makeBox]. */
    const val MAX_ABS_COORDINATE: Double = 1e7

    /**
     * Distance below which two consecutive profile points are treated as coincident, mirroring
     * OCCT's own `Precision::Confusion()` (1e-7, the tolerance `BRepBuilderAPI_MakePolygon` itself
     * uses internally to decide whether to drop a point). An EXACT (`==`) comparison here would
     * only catch a strict subset of what OCCT silently reinterprets: two points 1e-8 apart are
     * bit-distinct but still collapsed by `MakePolygon` into a shorter, unrequested polygon with
     * no error raised (measured against OCCT 7.9.2 -- see docs/adr/ADR-0008-occt-feature-operations.adoc
     * and the regression case in OcctFeatureOperationsTest covering d = 1e-12 .. 1e-7 all silently
     * losing the point, and d = 2e-7 correctly preserving it). Matching OCCT's own threshold here
     * closes that gap instead of only handling the bit-identical case.
     */
    const val PROFILE_POINT_COINCIDENCE_TOLERANCE: Double = 1e-7

    /** Fillet radius bounds. OCCT does NOT throw for a nonsensical radius -- it returns
     *  `IsDone()==false` for `r <= 0`, `r = 1e-12`, `r = 1e12` and `r = NaN` (measured), and
     *  calling `Shape()` in that state throws `Standard_Failure`. These bounds reject the obvious
     *  cases before OCCT sees them; the geometrically-too-large case CANNOT be validated
     *  numerically here (on a 20x30x40 solid, r=15 succeeds and r=25 fails -- the limit is
     *  geometry-dependent), so that one is deliberately left to OCCT's `IsDone()` and surfaces as
     *  [OcctGeometryException]. */
    const val MIN_FILLET_RADIUS: Double = 1e-7

    /** See [MIN_FILLET_RADIUS]. */
    const val MAX_FILLET_RADIUS: Double = 1e7

    /** Maximum triangle count [OcctShape.triangulate] returns. Mirrors `kMaxTriangles` in
     *  kstep_occt_bridge.cpp (hand-synchronized pair, the same pattern ADR-0007's
     *  `NativeConstraintKind` already uses -- see that ADR's Decision section). Measured (OCCT
     *  7.9.2, see docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc): the most expensive shape
     *  reachable through this module's own public API (a 198-face prism with 64 fillets --
     *  [MAX_FILLET_INPUT_FACES]/[MAX_FILLET_EDGES] at their limits) triangulates to 6 332
     *  triangles in 52.6 ms; 130 000 is roughly 20x that (a 9.4 MB return array). The time bound
     *  is transitively [MAX_FILLET_INPUT_FACES]/[MAX_PROFILE_POINTS], not this value -- this
     *  constant is a pure memory guard on the JNI return array.
     *
     *  Untested by construction, not by oversight: the "6 332 is the most expensive shape
     *  reachable through this module's own public API" measurement above means this guard's
     *  `tooManyTriangles` branch (`kstep_occt_bridge.cpp`'s `nativeShapeTriangles`) cannot
     *  actually be exercised by any [makeBox]/[extrudeProfile]/[fillet] call this module exposes
     *  -- reaching it would require either a native-only test harness this repo does not have, or
     *  lowering this constant to an artificially small value just to trip it, which would test the
     *  wrong number. Left as pure defense-in-depth, mirroring the equally-untested
     *  `mesher.IsDone()`/unmeshed-face guard right next to it in the same native function. */
    const val MAX_TRIANGLES: Int = 130_000

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

    /**
     * Extrudes a closed, planar, non-self-intersecting polygon in the XY plane into a solid
     * (OCCT: `BRepBuilderAPI_MakePolygon` -> `BRepBuilderAPI_MakeFace` -> `BRepPrimAPI_MakePrism`).
     *
     * The polygon is closed automatically -- do not repeat the first point as the last.
     *
     * @throws IllegalArgumentException if the profile has fewer than [MIN_PROFILE_POINTS] or more
     *   than [MAX_PROFILE_POINTS] points, if any coordinate is not finite or exceeds
     *   [MAX_ABS_COORDINATE], if two consecutive points (including last->first) are coincident or
     *   within [PROFILE_POINT_COINCIDENCE_TOLERANCE] of each other, if
     *   `height` is not finite or outside `[[MIN_DIMENSION], [MAX_DIMENSION]]` in magnitude, or if
     *   the resulting solid fails OCCT's own validity check (collinear or self-intersecting
     *   profiles reach OCCT successfully but produce a zero-volume, `BRepCheck`-invalid solid --
     *   measured, see docs/adr/ADR-0008-occt-feature-operations.adoc).
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT itself rejects the request.
     */
    fun extrudeProfile(
        profile: List<ProfilePoint>,
        height: Double,
    ): OcctShape {
        require(profile.size >= MIN_PROFILE_POINTS) {
            "profile must have at least $MIN_PROFILE_POINTS points, got ${profile.size}"
        }
        require(profile.size <= MAX_PROFILE_POINTS) {
            "profile must have at most $MAX_PROFILE_POINTS points, got ${profile.size}"
        }
        profile.forEachIndexed { index, point ->
            require(point.x.isFinite() && point.y.isFinite()) {
                "profile[$index] must be finite, got (${point.x}, ${point.y})"
            }
            require(abs(point.x) <= MAX_ABS_COORDINATE && abs(point.y) <= MAX_ABS_COORDINATE) {
                "profile[$index] coordinates must be within +/-$MAX_ABS_COORDINATE, got (${point.x}, ${point.y})"
            }
        }
        for (index in profile.indices) {
            val current = profile[index]
            val next = profile[(index + 1) % profile.size]
            // Distance-based, not `current != next` (ProfilePoint's data-class `equals()`) nor a
            // primitive `==`: either of those bit-exact comparisons only catches a strict subset
            // of what `BRepBuilderAPI_MakePolygon` itself silently collapses -- see
            // PROFILE_POINT_COINCIDENCE_TOLERANCE's KDoc. Ordinary subtraction already treats
            // -0.0 and 0.0 as equal (dx/dy come out exactly 0.0 either way), so the -0.0 case this
            // check used to call out by name is still caught -- just as one instance of the wider
            // distance check rather than a special case. The native side (kstep_occt_bridge.cpp)
            // makes the identical choice for the same reason.
            val dx = current.x - next.x
            val dy = current.y - next.y
            require(dx * dx + dy * dy > PROFILE_POINT_COINCIDENCE_TOLERANCE * PROFILE_POINT_COINCIDENCE_TOLERANCE) {
                "profile has coincident (or near-coincident, within $PROFILE_POINT_COINCIDENCE_TOLERANCE) " +
                    "consecutive points at index $index: $current"
            }
        }
        require(height.isFinite()) { "height must be finite, got $height" }
        require(abs(height) > MIN_DIMENSION) {
            "height magnitude must be greater than $MIN_DIMENSION, got $height"
        }
        require(abs(height) <= MAX_DIMENSION) {
            "height magnitude must be at most $MAX_DIMENSION, got $height"
        }
        val a = availability()
        if (a !is OcctAvailability.Available) {
            val unavailable = a as OcctAvailability.Unavailable
            throw OcctUnavailableException(unavailable.reason, unavailable.cause)
        }
        val profileXy = DoubleArray(profile.size * 2)
        profile.forEachIndexed { index, point ->
            profileXy[index * 2] = point.x
            profileXy[index * 2 + 1] = point.y
        }
        val handle =
            try {
                OcctBridge.nativeExtrudeProfile(profileXy, height)
            } catch (e: IllegalArgumentException) {
                // The native side's own degenerate-profile validation (collinear points, a
                // self-crossing "bowtie" polygon, a face OCCT could not build) throws
                // IllegalArgumentException and must surface as-is, exactly like fillet's identical
                // exception-mapping rule below -- see this function's KDoc and
                // OcctFeatureOperationsTest's regression case for the analogous fillet scenario.
                // IllegalArgumentException is itself a RuntimeException, so this catch MUST come
                // before the general RuntimeException catch below, or it would never run.
                throw e
            } catch (e: RuntimeException) {
                throw OcctGeometryException(
                    "OCCT failed to extrude profile of ${profile.size} points to height $height",
                    e,
                )
            }
        return OcctShape(handle)
    }

    /** Convenience overload for the single-edge case. */
    fun fillet(
        shape: OcctShape,
        edgeIndex: Int,
        radius: Double,
    ): OcctShape = fillet(shape, listOf(edgeIndex), radius)

    /**
     * Rounds one or more edges of `shape` (OCCT: `BRepFilletAPI_MakeFillet`), returning a NEW
     * shape. `shape` is neither modified nor consumed -- the caller still owns and must still
     * close it, and the returned shape is independently owned and independently closeable.
     *
     * Edge indices are 0-based into the same `TopExp::MapShapes(TopAbs_EDGE)` ordering that
     * [ShapeTopology.edges] counts. That ordering is reproducible for a given construction
     * sequence (verified: five identical rebuilds produce identical per-index edge lengths) but is
     * NOT geometrically meaningful and is NOT stable across a change to how the shape was built --
     * treat it as an opaque, shape-specific address, never as a durable identifier. Making edges
     * addressable by a stable, geometric identity is exactly what the parametric-history wave
     * (Geometrie Welle 5b) has to solve, and is deliberately out of scope here.
     *
     * @throws IllegalArgumentException if `edgeIndices` is empty, larger than [MAX_FILLET_EDGES],
     *   contains an out-of-range index, if `shape` has more than [MAX_FILLET_INPUT_FACES] faces,
     *   or if `radius` is not finite or outside `[[MIN_FILLET_RADIUS], [MAX_FILLET_RADIUS]]`.
     * @throws IllegalStateException if `shape` is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT cannot build the fillet -- most commonly a radius too
     *   large for the local geometry (`IsDone()==false`), or an edge OCCT considers unsuitable
     *   (`Build()` raises `Standard_Failure` "There are no suitable edges for chamfer or fillet").
     */
    fun fillet(
        shape: OcctShape,
        edgeIndices: Collection<Int>,
        radius: Double,
    ): OcctShape {
        require(edgeIndices.isNotEmpty()) { "edgeIndices must not be empty" }
        require(edgeIndices.size <= MAX_FILLET_EDGES) {
            "edgeIndices must have at most $MAX_FILLET_EDGES entries, got ${edgeIndices.size}"
        }
        edgeIndices.forEach { index ->
            require(index >= 0) { "edge index must not be negative, got $index" }
        }
        require(radius.isFinite()) { "radius must be finite, got $radius" }
        require(radius >= MIN_FILLET_RADIUS) { "radius must be at least $MIN_FILLET_RADIUS, got $radius" }
        require(radius <= MAX_FILLET_RADIUS) { "radius must be at most $MAX_FILLET_RADIUS, got $radius" }

        // checkOpen() (via withHandle, below) covers "already closed"; this call intentionally
        // does not read shape.topology first to check MAX_FILLET_INPUT_FACES here in Kotlin --
        // that would mean two round-trips through the JNI boundary (one to count faces, one to
        // fillet) with a check-then-act gap in between where nothing prevents the shape from being
        // closed concurrently. The native side re-derives the face count from the same locked
        // lookup it uses to fillet, so the check and the operation are atomic -- see
        // kstep_occt_bridge.cpp's nativeFilletEdges.
        val a = availability()
        if (a !is OcctAvailability.Available) {
            val unavailable = a as OcctAvailability.Unavailable
            throw OcctUnavailableException(unavailable.reason, unavailable.cause)
        }
        val edgeIndicesArray = edgeIndices.toIntArray()
        val resultHandle =
            shape.withHandle { handle ->
                try {
                    OcctBridge.nativeFilletEdges(handle, edgeIndicesArray, radius)
                } catch (e: IllegalArgumentException) {
                    // Native-side range/face-count validation failures must surface as-is, not be
                    // reinterpreted as an OCCT geometry failure -- see this function's KDoc and
                    // OcctFeatureOperationsTest's regression case for this exact distinction.
                    // IllegalArgumentException is itself a RuntimeException, so this catch MUST
                    // come before the general RuntimeException catch below, or it would never run.
                    throw e
                } catch (e: RuntimeException) {
                    // Everything else -- most commonly the native "unknown handle"
                    // IllegalStateException (a concurrent-close race lost, mirroring
                    // OcctShape.writeStepFile's identical wrapping) or "did not complete" (radius
                    // too large for the local geometry, or an unsuitable edge) -- is a genuine OCCT
                    // geometry failure, not a caller-input-shape problem.
                    throw OcctGeometryException(
                        "OCCT failed to fillet ${edgeIndices.size} edge(s) at radius $radius",
                        e,
                    )
                }
            }
        return OcctShape(resultHandle)
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
