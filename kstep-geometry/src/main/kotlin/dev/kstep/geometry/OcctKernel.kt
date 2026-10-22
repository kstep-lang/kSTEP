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
 * headers were absent at build time (or on any platform other than linux-x86-64 and
 * macos-aarch64), the native shim is never compiled, no `.so`/`.dylib` classpath resource exists, and
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

    /** Minimum / maximum chamfer distance, same role as [MIN_FILLET_RADIUS]/[MAX_FILLET_RADIUS]. */
    const val MIN_CHAMFER_DISTANCE: Double = 1e-7

    /** See [MIN_CHAMFER_DISTANCE]. */
    const val MAX_CHAMFER_DISTANCE: Double = 1e7

    /** Maximum edges chamfered in one operation. */
    const val MAX_CHAMFER_EDGES: Int = 64

    /** Maximum faces the INPUT shape of a chamfer may have. Measured (OCCT 7.9.3, macOS arm64): a
     *  64-edge chamfer costs 113 ms at 102 faces and 317 ms at 200 faces (roughly n^1.5), so 150
     *  keeps about 5x headroom under one second -- deliberately tighter than
     *  [MAX_FILLET_INPUT_FACES]. See docs/adr/ADR-0029-geometry-operations.adoc. */
    const val MAX_CHAMFER_INPUT_FACES: Int = 150

    /** Maximum faces EACH operand of a boolean operation may have. Measured (OCCT 7.9.3, macOS arm
     *  64): union/cut/common of a 198-gon prism (200 faces) with a shifted prism, a rotated prism
     *  or a cylinder costs 12-132 ms (roughly linear), i.e. at least 7x headroom under one second.
     *  A boolean holds the process-wide native lock and therefore blocks every other OCCT call --
     *  this limit is the DoS guard. Larger operands were not measured (the guard itself prevents
     *  it); raise it only together with a new measurement. See
     *  docs/adr/ADR-0029-geometry-operations.adoc. */
    const val MAX_BOOLEAN_INPUT_FACES: Int = 200

    /** Revolve angle bounds in degrees; 360 is a true full revolution. */
    const val MIN_REVOLVE_ANGLE_DEGREES: Double = 1e-4

    /** See [MIN_REVOLVE_ANGLE_DEGREES]. */
    const val MAX_REVOLVE_ANGLE_DEGREES: Double = 360.0

    /** Maximum vertices in a revolve profile. Measured (OCCT 7.9.3): a 512-point profile revolved
     *  360 degrees builds in 44 ms (BRepCheck included) and triangulates to 26 774 triangles in
     *  85 ms. See docs/adr/ADR-0029-geometry-operations.adoc. */
    const val MAX_REVOLVE_PROFILE_POINTS: Int = 512

    /** Minimum length of a direction vector (cylinder / hole axis). */
    const val MIN_DIRECTION_LENGTH: Double = 1e-9

    /** Maximum triangle count [OcctShape.triangulate] returns. Mirrors `kMaxTriangles` in
     *  kstep_occt_bridge.cpp (hand-synchronized pair, the same pattern ADR-0007's
     *  `NativeConstraintKind` already uses -- see that ADR's Decision section). Measured (OCCT
     *  7.9.2, see docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc): the most expensive shape
     *  reachable through this module's own public API (a 198-face prism with 64 fillets --
     *  [MAX_FILLET_INPUT_FACES]/[MAX_FILLET_EDGES] at their limits) triangulates to 6 332
     *  triangles in 52.6 ms; 130 000 is roughly 20x that. The time bound is transitively
     *  [MAX_FILLET_INPUT_FACES]/[MAX_PROFILE_POINTS], not this value -- this constant is a pure
     *  memory guard on the JNI return array.
     *
     *  Since ADR-0018 (smooth vertex normals), the JNI return array is `1 + 18*N` doubles in the
     *  with-normals layout (it was `1 + 9*N` before) -- ADR-0018's Security section notes "peak
     *  native allocation roughly doubles". At the 130 000 cap that is `1 + 18*130 000 =
     *  2 340 001` doubles, ~18.7 MB, not the ~9.4 MB this guard was originally sized against. JVM
     *  side, the peak is higher still: [decodeTriangles] slices that array into two fresh
     *  `copyOfRange` copies (positions + normals, ~9.4 MB each) while the original ~18.7 MB array
     *  is still reachable, so a single concurrent `triangulate()` call can peak around ~37 MB of
     *  JVM heap, not the ~18.7 MB the native-side array alone would suggest.
     *
     *  Welle J update (ADR-0029): the "6 332 is the most expensive reachable shape" statement above
     *  is OUTDATED. Revolve, cylinders and booleans reach more triangles: a 512-point profile
     *  revolved 360 degrees triangulates to 26 774 triangles (measured, OCCT 7.9.3) -- still about
     *  5x below this cap. The cap's `tooManyTriangles` branch (`kstep_occt_bridge.cpp`'s
     *  `nativeShapeTriangles`) therefore remains unreachable through this module's public API, and
     *  stays untested as defence-in-depth, mirroring the equally-untested `mesher.IsDone()` guard
     *  right next to it. */
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
        validateProfile(profile, MAX_PROFILE_POINTS)
        require(height.isFinite()) { "height must be finite, got $height" }
        require(abs(height) > MIN_DIMENSION) {
            "height magnitude must be greater than $MIN_DIMENSION, got $height"
        }
        require(abs(height) <= MAX_DIMENSION) {
            "height magnitude must be at most $MAX_DIMENSION, got $height"
        }
        requireAvailable()
        val handle =
            try {
                OcctBridge.nativeExtrudeProfile(toXyArray(profile), height)
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

    /**
     * Builds a cylinder (OCCT `BRepPrimAPI_MakeCylinder`): the base circle of radius [radius] is
     * centred at `(x, y, z)` and the cylinder extends [height] along `(dirX, dirY, dirZ)`
     * (default +Z; the direction need not be normalized).
     *
     * @throws IllegalArgumentException if radius/height are not finite or outside
     *   `[MIN_DIMENSION, MAX_DIMENSION]`, the position is not finite or beyond
     *   [MAX_ABS_COORDINATE], or the direction is not finite or shorter than [MIN_DIRECTION_LENGTH].
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT itself rejects the request.
     */
    fun makeCylinder(
        radius: Double,
        height: Double,
        x: Double = 0.0,
        y: Double = 0.0,
        z: Double = 0.0,
        dirX: Double = 0.0,
        dirY: Double = 0.0,
        dirZ: Double = 1.0,
    ): OcctShape {
        validateDimension("radius", radius)
        validateDimension("height", height)
        requireCoordinate("x", x)
        requireCoordinate("y", y)
        requireCoordinate("z", z)
        requireDirection(dirX, dirY, dirZ)
        requireAvailable()
        val handle =
            try {
                OcctBridge.nativeMakeCylinder(radius, height, x, y, z, dirX, dirY, dirZ)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: RuntimeException) {
                throw OcctGeometryException("OCCT failed to construct cylinder (r=$radius, h=$height)", e)
            }
        return OcctShape(handle)
    }

    /**
     * Returns a translated COPY of [shape]; [shape] is neither modified nor consumed.
     *
     * @throws IllegalArgumentException if any component is not finite or beyond [MAX_ABS_COORDINATE].
     * @throws IllegalStateException if [shape] is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT fails.
     */
    fun translate(
        shape: OcctShape,
        dx: Double,
        dy: Double,
        dz: Double,
    ): OcctShape {
        requireCoordinate("dx", dx)
        requireCoordinate("dy", dy)
        requireCoordinate("dz", dz)
        return transformed(shape, Placement.translation(dx, dy, dz))
    }

    /**
     * Returns a COPY of [shape] moved by the rigid transform [placement] (rotation + translation,
     * no scaling or reflection by construction of [Placement]). [shape] is neither modified nor
     * consumed.
     *
     * @throws IllegalArgumentException if the translation is not finite or beyond
     *   [MAX_ABS_COORDINATE].
     * @throws IllegalStateException if [shape] is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT fails.
     */
    fun transformed(
        shape: OcctShape,
        placement: Placement,
    ): OcctShape {
        val matrix = placement.toMatrix12()
        require(matrix.all { it.isFinite() }) { "placement must be finite" }
        for (row in 0 until 3) {
            require(abs(matrix[row * 4 + 3]) <= MAX_ABS_COORDINATE) {
                "placement translation must be within +/-$MAX_ABS_COORDINATE"
            }
        }
        requireAvailable()
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeTransformShape(h, matrix)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to transform shape", e)
                }
            }
        return OcctShape(handle)
    }

    /**
     * Reads STEP [content] (the raw file bytes) into an XCAF model ([OcctStepDocument]) the caller
     * must close. OCCT never opens a path itself, so there is no check-to-use gap between file
     * validation and reading; extern-file references are NOT followed (the document is read from a
     * stream with a synthetic, directory-less name). Input size and structure are bounded by
     * [StepImportLimits] -- see ADR-0030.
     *
     * @throws IllegalArgumentException if [content] is empty, too large, or not a readable STEP file.
     * @throws OcctImportLimitException if a structural limit is exceeded.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT fails while transferring the model.
     */
    fun readStep(content: ByteArray): OcctStepDocument = readStep(content, StepImportLimits.nativeLimits())

    internal fun readStep(
        content: ByteArray,
        limits: IntArray,
    ): OcctStepDocument {
        require(content.isNotEmpty()) { "STEP content must not be empty" }
        require(content.size <= StepImportLimits.MAX_FILE_BYTES) {
            "STEP content exceeds ${StepImportLimits.MAX_FILE_BYTES} bytes"
        }
        requireAvailable()
        val handle =
            try {
                OcctBridge.nativeReadStepXcaf(content, limits)
            } catch (e: IllegalArgumentException) {
                if (e.message?.startsWith("limit:") == true) {
                    throw OcctImportLimitException(e.message.orEmpty())
                }
                if (e.message?.startsWith("external-reference:") == true) {
                    throw OcctExternalReferenceException(e.message.orEmpty())
                }
                throw e
            } catch (e: RuntimeException) {
                throw OcctGeometryException("OCCT failed to read STEP content", e)
            }
        return try {
            OcctStepDocument(handle)
        } catch (e: Throwable) {
            OcctBridge.nativeReleaseImport(handle)
            throw e
        }
    }

    /** Union (`a + b`). See [boolean]. */
    fun union(
        a: OcctShape,
        b: OcctShape,
    ): OcctShape = boolean(BooleanOperation.UNION, a, b)

    /** Difference (`a - b`). See [boolean]. */
    fun cut(
        a: OcctShape,
        b: OcctShape,
    ): OcctShape = boolean(BooleanOperation.CUT, a, b)

    /** Intersection (`a & b`). See [boolean]. */
    fun common(
        a: OcctShape,
        b: OcctShape,
    ): OcctShape = boolean(BooleanOperation.COMMON, a, b)

    /**
     * Boolean operation on two solids (OCCT `BRepAlgoAPI_Fuse`/`Cut`/`Common`, run single-threaded
     * and followed by `SimplifyResult` so coplanar split faces are merged), returning a NEW shape.
     * Neither operand is modified or consumed.
     *
     * The result must be exactly one solid: a result with several disjoint solids (a union of two
     * separated boxes, a cut that severs the part) is rejected, because the STEP export path
     * (ADR-0027) assumes one `MANIFOLD_SOLID_BREP` per shape. Multi-body results are a follow-up.
     * Likewise a result whose single solid has an internal void (more than one shell, e.g. a cut
     * whose tool lies strictly inside the body) is rejected, because it would be exported as
     * `BREP_WITH_VOIDS`, which the geometry extraction does not support yet.
     *
     * A boolean holds the process-wide native lock for its whole duration and therefore blocks every
     * other OCCT call (and the Cleaner's release of shapes) until it finishes; the DoS guard is
     * [MAX_BOOLEAN_INPUT_FACES] per operand.
     *
     * @throws IllegalArgumentException if an operand has more than [MAX_BOOLEAN_INPUT_FACES] faces
     *   or contains no solid.
     * @throws IllegalStateException if an operand is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctEmptyResultException if the result contains no volume (e.g. the common of two
     *   disjoint solids, or `cut(a, a)`).
     * @throws OcctGeometryException if OCCT fails, the result has several disjoint solids, or the
     *   result has an internal void.
     */
    fun boolean(
        op: BooleanOperation,
        a: OcctShape,
        b: OcctShape,
    ): OcctShape {
        requireAvailable()
        val handle =
            a.withHandle { ha ->
                b.withHandle { hb ->
                    try {
                        OcctBridge.nativeBooleanOp(op.nativeCode, ha, hb)
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: OcctEmptyResultException) {
                        // A RuntimeException subtype: MUST precede the general catch below, or it
                        // would be re-labelled as a plain OcctGeometryException.
                        throw e
                    } catch (e: RuntimeException) {
                        throw OcctGeometryException("OCCT failed to compute boolean $op: ${e.message}", e)
                    }
                }
            }
        return OcctShape(handle)
    }

    /**
     * Drills a cylindrical hole into [shape]: a cylinder of [diameter] starting at `(x, y, z)` and
     * running [depth] along `(dirX, dirY, dirZ)` (default -Z, i.e. drilling downwards from a top
     * face) is cut from [shape]. Implemented as [makeCylinder] + [cut]; the temporary tool is closed
     * on every path.
     *
     * A hole that does not touch the body returns an unchanged copy of the shape (same volume) --
     * not an error. For a clean through-hole let the cylinder start slightly outside the body.
     *
     * @throws IllegalArgumentException for invalid dimensions/position/direction (as [makeCylinder])
     *   or when [shape] has more than [MAX_BOOLEAN_INPUT_FACES] faces.
     * @throws IllegalStateException if [shape] is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctEmptyResultException if the hole would remove the whole body.
     * @throws OcctGeometryException if OCCT fails, or the hole lies completely inside the body and
     *   would leave an internal void (not supported yet).
     */
    fun hole(
        shape: OcctShape,
        diameter: Double,
        depth: Double,
        x: Double,
        y: Double,
        z: Double,
        dirX: Double = 0.0,
        dirY: Double = 0.0,
        dirZ: Double = -1.0,
    ): OcctShape {
        validateDimension("diameter", diameter)
        validateDimension("depth", depth)
        return makeCylinder(diameter / 2.0, depth, x, y, z, dirX, dirY, dirZ).use { tool -> cut(shape, tool) }
    }

    /**
     * Revolves a closed, planar profile in the XY plane about [axis] (OCCT `BRepPrimAPI_MakeRevol`).
     * The profile is closed automatically. All points must lie on one side of the axis (points ON
     * the axis are fine -- a rectangle with an edge on the axis gives a solid cylinder).
     * [angleDegrees] = 360 is a true full revolution.
     *
     * @throws IllegalArgumentException for an invalid profile (as [extrudeProfile], with
     *   [MAX_REVOLVE_PROFILE_POINTS]), an angle outside
     *   `([MIN_REVOLVE_ANGLE_DEGREES]..[MAX_REVOLVE_ANGLE_DEGREES]]`, a profile crossing the axis,
     *   or a degenerate result.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT itself fails.
     */
    fun revolveProfile(
        profile: List<ProfilePoint>,
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
    ): OcctShape {
        validateProfile(profile, MAX_REVOLVE_PROFILE_POINTS)
        require(angleDegrees.isFinite()) { "angleDegrees must be finite, got $angleDegrees" }
        require(angleDegrees >= MIN_REVOLVE_ANGLE_DEGREES) {
            "angleDegrees must be at least $MIN_REVOLVE_ANGLE_DEGREES, got $angleDegrees"
        }
        require(angleDegrees <= MAX_REVOLVE_ANGLE_DEGREES) {
            "angleDegrees must be at most $MAX_REVOLVE_ANGLE_DEGREES, got $angleDegrees"
        }
        val radial: (ProfilePoint) -> Double = if (axis == RevolveAxis.Y) { p -> p.x } else { p -> p.y }
        require(profile.none { radial(it) > 0.0 } || profile.none { radial(it) < 0.0 }) {
            "profile crosses the rotation axis ${axis.name}: all " +
                "${if (axis == RevolveAxis.Y) "x" else "y"} coordinates must have the same sign"
        }
        requireAvailable()
        val handle =
            try {
                OcctBridge.nativeRevolveProfile(toXyArray(profile), angleDegrees, axis.nativeCode)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: RuntimeException) {
                throw OcctGeometryException(
                    "OCCT failed to revolve profile of ${profile.size} points by $angleDegrees degrees",
                    e,
                )
            }
        return OcctShape(handle)
    }

    /** Convenience overload for the single-edge case. */
    fun chamfer(
        shape: OcctShape,
        edgeIndex: Int,
        distance: Double,
    ): OcctShape = chamfer(shape, listOf(edgeIndex), distance)

    /**
     * Bevels one or more edges of [shape] with a symmetric [distance] (OCCT
     * `BRepFilletAPI_MakeChamfer`), returning a NEW shape; [shape] is neither modified nor consumed.
     * Edge addressing is identical to [fillet] (0-based, opaque, construction-order dependent).
     *
     * @throws IllegalArgumentException if [edgeIndices] is empty, larger than [MAX_CHAMFER_EDGES],
     *   contains an out-of-range index, [shape] has more than [MAX_CHAMFER_INPUT_FACES] faces, or
     *   [distance] is not finite or outside `[[MIN_CHAMFER_DISTANCE], [MAX_CHAMFER_DISTANCE]]`.
     * @throws IllegalStateException if [shape] is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT cannot build the chamfer (typically a distance too large
     *   for the local geometry).
     */
    fun chamfer(
        shape: OcctShape,
        edgeIndices: Collection<Int>,
        distance: Double,
    ): OcctShape {
        require(edgeIndices.isNotEmpty()) { "edgeIndices must not be empty" }
        require(edgeIndices.size <= MAX_CHAMFER_EDGES) {
            "edgeIndices must have at most $MAX_CHAMFER_EDGES entries, got ${edgeIndices.size}"
        }
        edgeIndices.forEach { index ->
            require(index >= 0) { "edge index must not be negative, got $index" }
        }
        require(distance.isFinite()) { "distance must be finite, got $distance" }
        require(distance >= MIN_CHAMFER_DISTANCE) { "distance must be at least $MIN_CHAMFER_DISTANCE, got $distance" }
        require(distance <= MAX_CHAMFER_DISTANCE) { "distance must be at most $MAX_CHAMFER_DISTANCE, got $distance" }
        requireAvailable()
        val edgeIndicesArray = edgeIndices.toIntArray()
        val resultHandle =
            shape.withHandle { handle ->
                try {
                    OcctBridge.nativeChamferEdges(handle, edgeIndicesArray, distance)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException(
                        "OCCT failed to chamfer ${edgeIndices.size} edge(s) at distance $distance",
                        e,
                    )
                }
            }
        return OcctShape(resultHandle)
    }

    /** Shared Kotlin-side profile validation of [extrudeProfile] and [revolveProfile]; the native
     *  side re-validates independently. Messages are unchanged from the original extrude checks. */
    private fun validateProfile(
        profile: List<ProfilePoint>,
        maxPoints: Int,
    ) {
        require(profile.size >= MIN_PROFILE_POINTS) {
            "profile must have at least $MIN_PROFILE_POINTS points, got ${profile.size}"
        }
        require(profile.size <= maxPoints) {
            "profile must have at most $maxPoints points, got ${profile.size}"
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
            // Distance-based (OCCT's own Precision::Confusion()), not bit-exact -- see
            // PROFILE_POINT_COINCIDENCE_TOLERANCE.
            val dx = current.x - next.x
            val dy = current.y - next.y
            require(dx * dx + dy * dy > PROFILE_POINT_COINCIDENCE_TOLERANCE * PROFILE_POINT_COINCIDENCE_TOLERANCE) {
                "profile has coincident (or near-coincident, within $PROFILE_POINT_COINCIDENCE_TOLERANCE) " +
                    "consecutive points at index $index: $current"
            }
        }
    }

    private fun toXyArray(profile: List<ProfilePoint>): DoubleArray {
        val profileXy = DoubleArray(profile.size * 2)
        profile.forEachIndexed { index, point ->
            profileXy[index * 2] = point.x
            profileXy[index * 2 + 1] = point.y
        }
        return profileXy
    }

    private fun requireAvailable() {
        val a = availability()
        if (a !is OcctAvailability.Available) {
            val unavailable = a as OcctAvailability.Unavailable
            throw OcctUnavailableException(unavailable.reason, unavailable.cause)
        }
    }

    private fun requireCoordinate(
        name: String,
        value: Double,
    ) {
        require(value.isFinite()) { "$name must be finite, got $value" }
        require(abs(value) <= MAX_ABS_COORDINATE) { "$name must be within +/-$MAX_ABS_COORDINATE, got $value" }
    }

    private fun requireDirection(
        dirX: Double,
        dirY: Double,
        dirZ: Double,
    ) {
        require(dirX.isFinite() && dirY.isFinite() && dirZ.isFinite()) {
            "direction must be finite, got ($dirX, $dirY, $dirZ)"
        }
        require(kotlin.math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ) >= MIN_DIRECTION_LENGTH) {
            "direction length must be at least $MIN_DIRECTION_LENGTH, got ($dirX, $dirY, $dirZ)"
        }
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
