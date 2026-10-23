package dev.kstep.geometry

import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.occt.OcctNativeLibrary
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeNamingException
import dev.kstep.geometry.select.EdgeSelection
import dev.kstep.geometry.select.EdgeSelectionException
import dev.kstep.geometry.select.EdgeSelector
import dev.kstep.geometry.select.FaceKind
import dev.kstep.geometry.select.FaceSelection
import dev.kstep.geometry.select.FaceSelector
import dev.kstep.geometry.select.Point3
import dev.kstep.geometry.select.SelectionTolerance
import dev.kstep.geometry.select.SelectorTree
import dev.kstep.geometry.select.VertexSelection
import dev.kstep.geometry.select.VertexSelector
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

    /** Welle L: upper bound on the edges of a shape whose edge geometry may be queried
     *  ([OcctShape.edges]); mirrors `kMaxQueryEdges` in `kstep_occt_bridge.cpp`. */
    const val MAX_QUERY_EDGES: Int = 20_000

    /** Welle L: upper bound on the faces of a queryable shape ([OcctShape.faces]); mirrors `kMaxQueryFaces`. */
    const val MAX_QUERY_FACES: Int = 10_000

    /** Welle Q (ADR-0037): upper bound on the vertices of a queryable shape ([OcctShape.vertices]); mirrors `kMaxQueryVertices`. */
    const val MAX_QUERY_VERTICES: Int = 20_000

    /** Welle Q: upper bound on the edges or faces one `nearest(...)` query measures to; mirrors `kMaxNearestCandidates`.
     *  Measured: 9 744 point-to-edge queries take 29 ms (OCCT 7.9.3), so the bound leaves about 2x headroom. */
    const val MAX_NEAREST_CANDIDATES: Int = 20_000

    /** Welle Q: upper bound on the faces of a shape [shell] accepts; mirrors `kMaxShellInputFaces`. Measured: the
     *  shell of a 200-face prism takes 0.54 s and of a 400-face prism 2.0 s (OCCT 7.9.3, ADR-0037). */
    const val MAX_SHELL_INPUT_FACES: Int = 200

    /** Welle Q: upper bound on the faces one [shell] opens; mirrors `kMaxShellOpenFaces`. */
    const val MAX_SHELL_OPEN_FACES: Int = 64

    /** Welle P (ADR-0036): smallest accepted [scale] factor; mirrors `kMinScaleFactor` in `kstep_occt_bridge.cpp`. */
    const val MIN_SCALE_FACTOR: Double = 1e-6

    /** Welle P: largest accepted [scale] factor; mirrors `kMaxScaleFactor`. */
    const val MAX_SCALE_FACTOR: Double = 1e6

    /** Welle P: upper bound on the faces of a shape [mirror]/[scale] accept; mirrors `kMaxShapeOperationInputFaces`. */
    const val MAX_SHAPE_OPERATION_INPUT_FACES: Int = 10_000

    /** Welle P: upper bound on the parts of one [compound]; mirrors `kMaxCompoundParts`. */
    const val MAX_COMPOUND_PARTS: Int = 32

    /** Welle L: upper bound on the size of a native edge-history payload; mirrors `kMaxHistoryInts`. */
    const val MAX_HISTORY_INTS: Int = 2_000_000

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
        requireExtrudeHeight(height)
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
     * addressable by a stable, geometric identity is what the selector overloads below
     * ([fillet] with an [EdgeSelector], ADR-0031) provide.
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
        requireFilletArguments(edgeIndices, radius)
        requireSingleSolidOperand(shape, "fillet")

        // checkOpen() (via withHandle, below) covers "already closed". requireSingleSolidOperand above
        // reads the lazily cached shape.topology (a concurrent close surfaces as the documented
        // IllegalStateException via withHandle). The MAX_FILLET_INPUT_FACES check is deliberately NOT
        // done here in Kotlin: the native side re-derives the face count from the same locked lookup it
        // uses to fillet, so that check and the operation are atomic -- see kstep_occt_bridge.cpp's
        // nativeFilletEdges.
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
     * no scaling or reflection by construction of [Placement]; for those see [mirror] and [scale]).
     * [shape] is neither modified nor consumed.
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

    // ---------------------------------------------------------------------------------------------
    // Welle P (ADR-0036): mirror, scale and compound as SHAPE operations. A placement stays rigid.
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns a mirrored COPY of [shape], reflected in the plane through ([originX], [originY], [originZ]) with
     * the normal ([normalX], [normalY], [normalZ]) (any length >= [MIN_DIRECTION_LENGTH]). [shape] is neither
     * modified nor consumed. Works on a solid or a compound of solids; every solid of the result is checked.
     *
     * @throws IllegalArgumentException if a value is not finite, the origin is beyond [MAX_ABS_COORDINATE], the
     *   normal is too short, the shape has more than [MAX_SHAPE_OPERATION_INPUT_FACES] faces, or the mirrored shape
     *   would leave +/-[MAX_ABS_COORDINATE].
     * @throws IllegalStateException if [shape] is closed or holds no solid.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT fails.
     */
    fun mirror(
        shape: OcctShape,
        originX: Double,
        originY: Double,
        originZ: Double,
        normalX: Double,
        normalY: Double,
        normalZ: Double,
    ): OcctShape {
        validateMirror(originX, originY, originZ, normalX, normalY, normalZ)
        requireAvailable()
        requireMirroredBoundsInRange(shape, originX, originY, originZ, normalX, normalY, normalZ)
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeMirrorShape(h, originX, originY, originZ, normalX, normalY, normalZ)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to mirror shape", e)
                }
            }
        return OcctShape(handle)
    }

    /** [mirror] in [plane]. */
    fun mirror(
        shape: OcctShape,
        plane: MirrorPlane,
    ): OcctShape =
        mirror(
            shape,
            plane.origin.x,
            plane.origin.y,
            plane.origin.z,
            plane.normal.x,
            plane.normal.y,
            plane.normal.z,
        )

    /** [mirror] that also returns the [EdgeHistory] (every edge is `Modified` to exactly one mirrored edge). */
    fun mirrorWithHistory(
        shape: OcctShape,
        originX: Double,
        originY: Double,
        originZ: Double,
        normalX: Double,
        normalY: Double,
        normalZ: Double,
    ): TrackedShape {
        validateMirror(originX, originY, originZ, normalX, normalY, normalZ)
        requireAvailable()
        requireMirroredBoundsInRange(shape, originX, originY, originZ, normalX, normalY, normalZ)
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeMirrorShapeTracked(h, originX, originY, originZ, normalX, normalY, normalZ, holder)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to mirror shape", e)
                }
            }
        return trackedResult(handle, holder, listOf(shape))
    }

    /**
     * Returns a COPY of [shape] uniformly scaled by [factor] about ([aboutX], [aboutY], [aboutZ]); [shape] is
     * neither modified nor consumed. [factor] must be finite and within [[MIN_SCALE_FACTOR], [MAX_SCALE_FACTOR]];
     * zero and negative factors are rejected (use [mirror] for a reflection). The scaled bounding box must stay within
     * +/-[MAX_ABS_COORDINATE], its largest extent within [MAX_DIMENSION] and its smallest above [MIN_DIMENSION].
     *
     * @throws IllegalArgumentException for an invalid factor/centre or a result beyond the limits above.
     * @throws IllegalStateException if [shape] is closed or holds no solid.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT fails.
     */
    fun scale(
        shape: OcctShape,
        factor: Double,
        aboutX: Double = 0.0,
        aboutY: Double = 0.0,
        aboutZ: Double = 0.0,
    ): OcctShape {
        validateScale(factor, aboutX, aboutY, aboutZ)
        requireAvailable()
        requireScaledBoundsInRange(shape, factor, aboutX, aboutY, aboutZ)
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeScaleShape(h, aboutX, aboutY, aboutZ, factor)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to scale shape", e)
                }
            }
        return OcctShape(handle)
    }

    /** [scale] that also returns the [EdgeHistory] (every edge is `Modified` to exactly one scaled edge). */
    fun scaleWithHistory(
        shape: OcctShape,
        factor: Double,
        aboutX: Double = 0.0,
        aboutY: Double = 0.0,
        aboutZ: Double = 0.0,
    ): TrackedShape {
        validateScale(factor, aboutX, aboutY, aboutZ)
        requireAvailable()
        requireScaledBoundsInRange(shape, factor, aboutX, aboutY, aboutZ)
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeScaleShapeTracked(h, aboutX, aboutY, aboutZ, factor, holder)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to scale shape", e)
                }
            }
        return trackedResult(handle, holder, listOf(shape))
    }

    /**
     * Combines several separate bodies into ONE shape (a compound) that can be assigned to a single product
     * definition: the STEP export writes one shape representation with one `MANIFOLD_SOLID_BREP` per body.
     * Every part must be exactly one solid and the parts must be pairwise separated (touching or overlapping bodies
     * are rejected -- use [boolean] with `UNION` for those). The parts are copied, not consumed; the caller owns and
     * must close the result.
     *
     * @throws IllegalArgumentException if [shapes] is empty or has more than [MAX_COMPOUND_PARTS] entries, or a part
     *   is not exactly one solid, or two parts touch or overlap.
     * @throws IllegalStateException if a part is closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT fails.
     */
    fun compound(shapes: List<OcctShape>): OcctShape {
        require(shapes.isNotEmpty()) { "compound needs at least one shape" }
        require(shapes.size <= MAX_COMPOUND_PARTS) {
            "compound accepts at most $MAX_COMPOUND_PARTS shapes, got ${shapes.size}"
        }
        requireAvailable()
        val handles = LongArray(shapes.size)
        shapes.forEachIndexed { i, part -> part.withHandle { handles[i] = it } }
        val handle =
            try {
                OcctBridge.nativeMakeCompound(handles)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: RuntimeException) {
                throw OcctGeometryException("OCCT failed to combine ${shapes.size} bodies", e)
            }
        return OcctShape(handle)
    }

    private fun validateMirror(
        originX: Double,
        originY: Double,
        originZ: Double,
        normalX: Double,
        normalY: Double,
        normalZ: Double,
    ) {
        requireCoordinate("originX", originX)
        requireCoordinate("originY", originY)
        requireCoordinate("originZ", originZ)
        requireDirection(normalX, normalY, normalZ)
    }

    private fun validateScale(
        factor: Double,
        aboutX: Double,
        aboutY: Double,
        aboutZ: Double,
    ) {
        require(!factor.isNaN()) { "scale factor must be a number, got NaN" }
        require(!factor.isInfinite()) { "scale factor must be finite, got $factor" }
        require(factor != 0.0) { "scale factor must not be 0 (a scaling to a point is not a solid)" }
        require(factor > 0.0) { "scale factor must be positive, got $factor -- use mirror(...) for a reflection" }
        require(factor in MIN_SCALE_FACTOR..MAX_SCALE_FACTOR) {
            "scale factor must be within [$MIN_SCALE_FACTOR, $MAX_SCALE_FACTOR], got $factor"
        }
        requireCoordinate("aboutX", aboutX)
        requireCoordinate("aboutY", aboutY)
        requireCoordinate("aboutZ", aboutZ)
    }

    /** The mirrored box must stay inside +/-[MAX_ABS_COORDINATE]; the native side re-checks as defence in depth. */
    private fun requireMirroredBoundsInRange(
        shape: OcctShape,
        originX: Double,
        originY: Double,
        originZ: Double,
        normalX: Double,
        normalY: Double,
        normalZ: Double,
    ) {
        val box = shape.boundingBox ?: return
        val len = kotlin.math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        val nx = normalX / len
        val ny = normalY / len
        val nz = normalZ / len
        for (x in doubleArrayOf(box.minX, box.maxX)) {
            for (y in doubleArrayOf(box.minY, box.maxY)) {
                for (z in doubleArrayOf(box.minZ, box.maxZ)) {
                    val d = (x - originX) * nx + (y - originY) * ny + (z - originZ) * nz
                    val mx = x - 2.0 * d * nx
                    val my = y - 2.0 * d * ny
                    val mz = z - 2.0 * d * nz
                    val inRange = maxOf(abs(mx), abs(my), abs(mz)) <= MAX_ABS_COORDINATE
                    require(inRange) {
                        "mirrored shape would leave +/-$MAX_ABS_COORDINATE (corner ($x, $y, $z) maps to ($mx, $my, $mz))"
                    }
                }
            }
        }
    }

    private fun requireScaledBoundsInRange(
        shape: OcctShape,
        factor: Double,
        aboutX: Double,
        aboutY: Double,
        aboutZ: Double,
    ) {
        val box = shape.boundingBox ?: return
        val lows = doubleArrayOf(box.minX, box.minY, box.minZ)
        val highs = doubleArrayOf(box.maxX, box.maxY, box.maxZ)
        val about = doubleArrayOf(aboutX, aboutY, aboutZ)
        var largest = 0.0
        var smallest = Double.MAX_VALUE
        for (axis in 0 until 3) {
            val lo = about[axis] + factor * (lows[axis] - about[axis])
            val hi = about[axis] + factor * (highs[axis] - about[axis])
            require(abs(lo) <= MAX_ABS_COORDINATE && abs(hi) <= MAX_ABS_COORDINATE) {
                "scaled shape would leave +/-$MAX_ABS_COORDINATE (factor $factor)"
            }
            val extent = hi - lo
            largest = maxOf(largest, extent)
            smallest = minOf(smallest, extent)
        }
        require(largest <= MAX_DIMENSION) { "scaled shape would be larger than $MAX_DIMENSION (factor $factor)" }
        require(smallest >= MIN_DIMENSION) {
            "scaled shape would be thinner than $MIN_DIMENSION in one direction (factor $factor)"
        }
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
                if (e.message?.startsWith("unit:") == true) {
                    throw OcctUnitException(e.message.orEmpty())
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
        requireRevolveProfileArguments(profile, angleDegrees, axis)
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
        requireChamferArguments(edgeIndices, distance)
        requireSingleSolidOperand(shape, "chamfer")
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

    // ---------------------------------------------------------------------------------------
    // Welle L (ADR-0031): geometric edge selection, edge history, sketch regions.
    // ---------------------------------------------------------------------------------------

    /**
     * Resolves [selector] against the edges and faces of [shape] and returns the matching 0-based
     * edge indices (ascending) -- the same indices [fillet]/[chamfer] accept. Never guesses: an
     * empty result, or a count different from [expected], throws
     * [dev.kstep.geometry.select.EdgeSelectionException] listing the candidates.
     *
     * @throws IllegalArgumentException for an invalid selector (depth/node limits) or tolerance.
     * @throws IllegalStateException if [shape] is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     */
    fun selectEdges(
        shape: OcctShape,
        selector: EdgeSelector,
        expected: Int? = null,
        tolerance: SelectionTolerance = SelectionTolerance(),
    ): List<Int> {
        requireAvailable()
        return EdgeSelection.resolve(shape.selectionContext(), selector, expected, tolerance)
    }

    /**
     * Resolves a face [selector] against [shape] and returns the matching 0-based face indices (ascending), the
     * indices [shell] accepts. Never guesses: an empty result, or a count different from [expected], throws
     * [FaceSelectionException] listing the candidates (ADR-0037).
     *
     * @throws IllegalArgumentException for an invalid selector (depth/node limits), [expected] or tolerance.
     * @throws IllegalStateException if [shape] is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     */
    fun selectFaces(
        shape: OcctShape,
        selector: FaceSelector,
        expected: Int? = null,
        tolerance: SelectionTolerance = SelectionTolerance(),
    ): List<Int> {
        requireAvailable()
        return FaceSelection.resolve(shape.selectionContext(), selector, expected, tolerance)
    }

    /**
     * Resolves a vertex [selector] against [shape] and returns the matching 0-based vertex indices (ascending, the
     * order of [OcctShape.vertices]). Vertices steer edge operations through `Edges.atVertex`. Never guesses; see
     * [selectFaces]. Throws [VertexSelectionException] for an empty or ambiguous result (ADR-0037).
     */
    fun selectVertices(
        shape: OcctShape,
        selector: VertexSelector,
        expected: Int? = null,
        tolerance: SelectionTolerance = SelectionTolerance(),
    ): List<Int> {
        requireAvailable()
        return VertexSelection.resolve(shape.selectionContext(), selector, expected, tolerance)
    }

    // ---------------------------------------------------------------------------------------------
    // Welle Q (ADR-0037): shell (hollowing) of a solid.
    // ---------------------------------------------------------------------------------------------

    /**
     * Hollows [shape] (OCCT: `BRepOffsetAPI_MakeThickSolid`): the faces [openFaceIndices] are removed and the remaining
     * faces get a wall of [thickness] -- into the material for [ShellDirection.INWARD] (the original outer surface is
     * kept), outwards for [ShellDirection.OUTWARD] (the original surface becomes the inside of the cavity). [shape] is
     * neither modified nor consumed. The thickness is always positive; the direction is the enum.
     *
     * The wall must be thinner than half the smallest bounding-box extent, in BOTH directions (a conservative rule
     * that keeps OCCT away from self-intersecting offsets). Indices are 0-based into the same face order as
     * [OcctShape.faces] / [selectFaces].
     *
     * @throws IllegalArgumentException if [openFaceIndices] is empty, has more than [MAX_SHELL_OPEN_FACES] entries, a
     *   negative or duplicate index, an index beyond the faces of [shape], or opens every face; if [thickness] is not
     *   finite, outside `[[MIN_DIMENSION], [MAX_DIMENSION]]` or not less than half the smallest extent; if [shape] has
     *   more than [MAX_SHELL_INPUT_FACES] faces or is not exactly one solid.
     * @throws IllegalStateException if [shape] is already closed.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT cannot build the shell or the result is invalid (for example a wall that
     *   closes a narrow hole).
     */
    fun shell(
        shape: OcctShape,
        openFaceIndices: Collection<Int>,
        thickness: Double,
        direction: ShellDirection = ShellDirection.INWARD,
    ): OcctShape {
        requireShellArguments(openFaceIndices, thickness)
        requireAvailable()
        requireShellOperand(shape, thickness)
        val indices = openFaceIndices.toIntArray()
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeShellShape(h, indices, thickness, direction.nativeCode)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to shell shape (thickness $thickness, $direction)", e)
                }
            }
        return OcctShape(handle)
    }

    /**
     * [shell] that opens the faces [openFaces] resolves to (see [selectFaces]). Never guesses: a selection that matches
     * nothing, or a count different from [expected], throws [FaceSelectionException] listing the candidates.
     */
    fun shell(
        shape: OcctShape,
        openFaces: FaceSelector,
        thickness: Double,
        direction: ShellDirection = ShellDirection.INWARD,
        expected: Int? = null,
        tolerance: SelectionTolerance = SelectionTolerance(),
    ): OcctShape = shell(shape, selectFaces(shape, openFaces, expected, tolerance), thickness, direction)

    /**
     * [shell] that also returns the [EdgeHistory]. Measured (ADR-0037): `MakeThickSolid` keeps every edge of the
     * original solid by identity, so an INWARD shell tracks each original edge to the same edge of the outer skin. An
     * OUTWARD shell keeps the original edges as the INNER edges of the cavity, which is rarely what a caller means, so
     * history is only offered for [ShellDirection.INWARD].
     *
     * @throws IllegalArgumentException additionally if [direction] is [ShellDirection.OUTWARD].
     */
    fun shellWithHistory(
        shape: OcctShape,
        openFaceIndices: Collection<Int>,
        thickness: Double,
        direction: ShellDirection = ShellDirection.INWARD,
    ): TrackedShape {
        require(direction == ShellDirection.INWARD) {
            "edges cannot be tracked through an OUTWARD shell: the original edges become the inner edges of the " +
                "cavity (ADR-0037); use INWARD, or name the edges after the shell"
        }
        requireShellArguments(openFaceIndices, thickness)
        requireAvailable()
        requireShellOperand(shape, thickness)
        val indices = openFaceIndices.toIntArray()
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeShellShapeTracked(h, indices, thickness, direction.nativeCode, holder)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to shell shape (thickness $thickness, $direction)", e)
                }
            }
        return trackedResult(handle, holder, listOf(shape))
    }

    /** The scalar rules of [shell], checked before any native call. */
    private fun requireShellArguments(
        openFaceIndices: Collection<Int>,
        thickness: Double,
    ) {
        require(thickness.isFinite()) { "shell thickness must be finite, got $thickness" }
        require(thickness in MIN_DIMENSION..MAX_DIMENSION) {
            "shell thickness must be within [$MIN_DIMENSION, $MAX_DIMENSION], got $thickness"
        }
        require(openFaceIndices.isNotEmpty()) { "shell needs at least one face to open" }
        require(openFaceIndices.size <= MAX_SHELL_OPEN_FACES) {
            "shell opens at most $MAX_SHELL_OPEN_FACES faces, got ${openFaceIndices.size}"
        }
        require(openFaceIndices.all { it >= 0 }) { "shell face indices must not be negative: $openFaceIndices" }
        require(openFaceIndices.toSet().size == openFaceIndices.size) {
            "shell face indices must be unique: $openFaceIndices"
        }
    }

    /** The rules that need the shape itself; the native side re-derives all of them under its lock. */
    private fun requireShellOperand(
        shape: OcctShape,
        thickness: Double,
    ) {
        requireSingleSolidOperand(shape, "shell")
        require(shape.topology.solids == 1) { "shell needs exactly one solid, got ${shape.topology.solids}" }
        val faces = shape.topology.faces
        require(faces <= MAX_SHELL_INPUT_FACES) {
            "shell input has $faces faces, more than the limit of $MAX_SHELL_INPUT_FACES"
        }
        val box = shape.boundingBox
        if (box != null) {
            val smallest = minOf(box.maxX - box.minX, box.maxY - box.minY, box.maxZ - box.minZ)
            require(thickness < smallest / 2.0) {
                "shell thickness $thickness must be less than half the smallest extent (${smallest / 2.0})"
            }
        }
    }

    /** [fillet] on the edges [selector] resolves to (see [selectEdges]). */
    fun fillet(
        shape: OcctShape,
        selector: EdgeSelector,
        radius: Double,
        expected: Int? = null,
        tolerance: SelectionTolerance = SelectionTolerance(),
    ): OcctShape = fillet(shape, selectEdges(shape, selector, expected, tolerance), radius)

    /** [chamfer] on the edges [selector] resolves to (see [selectEdges]). */
    fun chamfer(
        shape: OcctShape,
        selector: EdgeSelector,
        distance: Double,
        expected: Int? = null,
        tolerance: SelectionTolerance = SelectionTolerance(),
    ): OcctShape = chamfer(shape, selectEdges(shape, selector, expected, tolerance), distance)

    /**
     * [fillet] that also returns the [EdgeHistory] of the operation: for every edge of [shape], the
     * fate (kept / modified / deleted / lost) against the edges of the new shape. Same contract
     * and exceptions as [fillet]; the returned [TrackedShape] must be closed.
     */
    fun filletWithHistory(
        shape: OcctShape,
        edgeIndices: Collection<Int>,
        radius: Double,
    ): TrackedShape {
        requireFilletArguments(edgeIndices, radius)
        requireSingleSolidOperand(shape, "fillet")
        requireAvailable()
        val indices = edgeIndices.toIntArray()
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeFilletEdgesTracked(h, indices, radius, holder)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException(
                        "OCCT failed to fillet ${edgeIndices.size} edge(s) at radius $radius",
                        e,
                    )
                }
            }
        return trackedResult(handle, holder, listOf(shape))
    }

    /** [chamfer] that also returns the [EdgeHistory]; see [filletWithHistory]. */
    fun chamferWithHistory(
        shape: OcctShape,
        edgeIndices: Collection<Int>,
        distance: Double,
    ): TrackedShape {
        requireChamferArguments(edgeIndices, distance)
        requireSingleSolidOperand(shape, "chamfer")
        requireAvailable()
        val indices = edgeIndices.toIntArray()
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeChamferEdgesTracked(h, indices, distance, holder)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException(
                        "OCCT failed to chamfer ${edgeIndices.size} edge(s) at distance $distance",
                        e,
                    )
                }
            }
        return trackedResult(handle, holder, listOf(shape))
    }

    /** [boolean] that also returns the [EdgeHistory] (two operands: [a] then [b]); see [filletWithHistory]. */
    fun booleanWithHistory(
        op: BooleanOperation,
        a: OcctShape,
        b: OcctShape,
    ): TrackedShape {
        requireAvailable()
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            a.withHandle { ha ->
                b.withHandle { hb ->
                    try {
                        OcctBridge.nativeBooleanOpTracked(op.nativeCode, ha, hb, holder)
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: OcctEmptyResultException) {
                        throw e
                    } catch (e: RuntimeException) {
                        throw OcctGeometryException("OCCT failed to compute boolean $op: ${e.message}", e)
                    }
                }
            }
        return trackedResult(handle, holder, listOf(a, b))
    }

    /** [translate] that also returns the [EdgeHistory] (every edge is `Modified`/`Kept` to its moved copy). */
    fun translateWithHistory(
        shape: OcctShape,
        dx: Double,
        dy: Double,
        dz: Double,
    ): TrackedShape {
        requireCoordinate("dx", dx)
        requireCoordinate("dy", dy)
        requireCoordinate("dz", dz)
        requireAvailable()
        val matrix = Placement.translation(dx, dy, dz).toMatrix12()
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            shape.withHandle { h ->
                try {
                    OcctBridge.nativeTransformShapeTracked(h, matrix, holder)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to transform shape", e)
                }
            }
        return trackedResult(handle, holder, listOf(shape))
    }

    /**
     * [hole] that also returns the [EdgeHistory]; the history has two operands (the shape, then
     * the drill cylinder, which is closed before returning).
     */
    fun holeWithHistory(
        shape: OcctShape,
        diameter: Double,
        depth: Double,
        x: Double,
        y: Double,
        z: Double,
        dirX: Double = 0.0,
        dirY: Double = 0.0,
        dirZ: Double = -1.0,
    ): TrackedShape {
        validateDimension("diameter", diameter)
        validateDimension("depth", depth)
        return makeCylinder(diameter / 2.0, depth, x, y, z, dirX, dirY, dirZ).use { tool ->
            booleanWithHistory(BooleanOperation.CUT, shape, tool)
        }
    }

    private fun trackedResult(
        handle: Long,
        holder: Array<IntArray?>,
        operands: List<OcctShape>,
    ): TrackedShape {
        val shape = OcctShape(handle)
        try {
            val raw = checkNotNull(holder[0]) { "native bridge returned no edge history" }
            val history = decodeEdgeHistory(raw, operands.map { it.topology.edges }, shape.topology.edges)
            return TrackedShape(shape, history)
        } catch (e: Throwable) {
            shape.close()
            throw e
        }
    }

    /**
     * Extrudes a planar [region] (outer loop plus holes, XY plane) by [height] along +Z. Like
     * [extrudeProfile], but the profile may contain arcs, circles and holes.
     *
     * @throws IllegalArgumentException for an invalid region (see [ProfileRegion]) or height.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT itself fails.
     */
    fun extrudeRegion(
        region: ProfileRegion,
        height: Double,
    ): OcctShape {
        RegionValidation.validate(region)
        requireExtrudeHeight(height)
        requireAvailable()
        val encoded = RegionValidation.encode(RegionValidation.normalized(region))
        val handle =
            try {
                OcctBridge.nativeExtrudeRegion(encoded.loopSizes, encoded.kinds, encoded.data, height)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: RuntimeException) {
                throw OcctGeometryException(
                    "OCCT failed to extrude a region of ${encoded.kinds.size} segments to height $height",
                    e,
                )
            }
        return OcctShape(handle)
    }

    /**
     * Revolves a planar [region] about [axis] (as [revolveProfile], but with arcs, circles and
     * holes). Every boundary sample must lie on one side of the axis.
     *
     * @throws IllegalArgumentException for an invalid region or angle, or a region crossing the axis.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT itself fails.
     */
    fun revolveRegion(
        region: ProfileRegion,
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
    ): OcctShape {
        requireRevolveRegionArguments(region, angleDegrees, axis)
        requireAvailable()
        val encoded = RegionValidation.encode(RegionValidation.normalized(region))
        val handle =
            try {
                OcctBridge.nativeRevolveRegion(
                    encoded.loopSizes,
                    encoded.kinds,
                    encoded.data,
                    angleDegrees,
                    axis.nativeCode,
                )
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: RuntimeException) {
                throw OcctGeometryException("OCCT failed to revolve a region by $angleDegrees degrees", e)
            }
        return OcctShape(handle)
    }

    /**
     * Fillet and chamfer are only supported on a single solid: an operand with several bodies (a multi-body
     * extrusion, or an import) is rejected clearly instead of failing somewhere inside OCCT. See ADR-0032.
     */
    private fun requireSingleSolidOperand(
        shape: OcctShape,
        operation: String,
    ) {
        val solids = shape.topology.solids
        require(solids <= 1) {
            "operand has $solids solids; $operation on a multi-body shape is not supported yet (ADR-0032)"
        }
    }

    /**
     * Extrudes SEVERAL disjoint planar regions by [height] along +Z into ONE shape (a compound of one solid
     * per region). The regions must be pairwise disjoint, see [requireValidDisjoint]; at most
     * [RegionValidation.MAX_REGIONS]. A single region is exactly [extrudeRegion]. Every intermediate shape is
     * closed again, whatever happens.
     *
     * @throws IllegalArgumentException for an invalid region list, height, or regions that touch.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT itself fails.
     */
    fun extrudeRegions(
        regions: List<ProfileRegion>,
        height: Double,
    ): OcctShape {
        RegionValidation.validateDisjoint(regions)
        return combineRegions(regions) { extrudeRegion(it, height) }
    }

    /**
     * Revolves several disjoint regions, see [extrudeRegions] and [revolveRegion].
     *
     * Regions that are disjoint in 2D can still produce solids that touch or overlap once revolved: two
     * regions mirrored about the revolve axis (for example x in [1, 2] and x in [-2, -1] about Y) make
     * identical rings, and the combination fails with an [IllegalArgumentException] ("touch or overlap").
     */
    fun revolveRegions(
        regions: List<ProfileRegion>,
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
    ): OcctShape {
        RegionValidation.validateDisjoint(regions)
        return combineRegions(regions) { revolveRegion(it, angleDegrees, axis) }
    }

    private fun combineRegions(
        regions: List<ProfileRegion>,
        build: (ProfileRegion) -> OcctShape,
    ): OcctShape {
        if (regions.size == 1) return build(regions[0])
        val parts = ArrayList<OcctShape>(regions.size)
        try {
            regions.forEach { parts.add(build(it)) }
            requireAvailable()
            val handles = LongArray(parts.size)
            // Handles are read through withHandle one by one; the native call re-validates every one.
            parts.forEachIndexed { i, part -> part.withHandle { handles[i] = it } }
            val handle =
                try {
                    OcctBridge.nativeMakeCompound(handles)
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to combine ${parts.size} bodies", e)
                }
            return OcctShape(handle)
        } finally {
            parts.forEach { it.close() }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Welle N (ADR-0033): persistent naming of the edges a sweep generates.
    // ---------------------------------------------------------------------------------------------

    /** Upper bound on the generation sources of one named sweep, enforced in Kotlin and natively. */
    const val MAX_NAMING_SOURCES: Int = 1024

    /**
     * [extrudeProfile] that also reports, for every requested [sources] entry, the edges generated from it
     * ([ProfileVertex] -> SIDE edge, [ProfileEdge] -> START / END copies). The edge indices are identical to
     * the ones [extrudeProfile] returns (one shared native construction path).
     *
     * @throws IllegalArgumentException as [extrudeProfile], and for an out-of-range, duplicate, foreign or more
     *   than [MAX_NAMING_SOURCES] sources.
     * @throws OcctUnavailableException if the native bridge is not available.
     * @throws OcctGeometryException if OCCT itself fails.
     */
    fun extrudeProfileNamed(
        profile: List<ProfilePoint>,
        height: Double,
        sources: List<GenerationSource>,
    ): NamedShape {
        validateProfile(profile, MAX_PROFILE_POINTS)
        requireExtrudeHeight(height)
        val queries = resolveProfileSources(profile, sources)
        requireAvailable()
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            nativeSweep("extrude profile of ${profile.size} points to height $height") {
                OcctBridge.nativeExtrudeProfileNamed(
                    toXyArray(profile),
                    height,
                    queries.vertexXy,
                    queries.segQuery,
                    holder,
                )
            }
        return namedResult(handle, holder, queries)
    }

    /** [revolveProfile] with a generation map; see [extrudeProfileNamed]. */
    fun revolveProfileNamed(
        profile: List<ProfilePoint>,
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
        sources: List<GenerationSource>,
    ): NamedShape {
        requireRevolveProfileArguments(profile, angleDegrees, axis)
        val queries = resolveProfileSources(profile, sources)
        requireAvailable()
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            nativeSweep("revolve profile of ${profile.size} points by $angleDegrees degrees") {
                OcctBridge.nativeRevolveProfileNamed(
                    toXyArray(profile),
                    angleDegrees,
                    axis.nativeCode,
                    queries.vertexXy,
                    queries.segQuery,
                    holder,
                )
            }
        return namedResult(handle, holder, queries)
    }

    /**
     * [extrudeRegion] with a generation map ([RegionVertex] -> SIDE, [RegionEdge] -> START / END). The sources
     * refer to the region AS GIVEN; the orientation normalisation is undone by coordinate matching. Several
     * separate regions (multi-body) cannot be named yet, see ADR-0033.
     */
    fun extrudeRegionNamed(
        region: ProfileRegion,
        height: Double,
        sources: List<GenerationSource>,
    ): NamedShape {
        RegionValidation.validate(region)
        requireExtrudeHeight(height)
        val queries = resolveRegionSources(region, sources)
        requireAvailable()
        val encoded = RegionValidation.encode(RegionValidation.normalized(region))
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            nativeSweep("extrude a region of ${encoded.kinds.size} segments to height $height") {
                OcctBridge.nativeExtrudeRegionNamed(
                    encoded.loopSizes,
                    encoded.kinds,
                    encoded.data,
                    height,
                    queries.vertexXy,
                    queries.segQuery,
                    holder,
                )
            }
        return namedResult(handle, holder, queries)
    }

    /** [revolveRegion] with a generation map; see [extrudeRegionNamed]. */
    fun revolveRegionNamed(
        region: ProfileRegion,
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
        sources: List<GenerationSource>,
    ): NamedShape {
        requireRevolveRegionArguments(region, angleDegrees, axis)
        val queries = resolveRegionSources(region, sources)
        requireAvailable()
        val encoded = RegionValidation.encode(RegionValidation.normalized(region))
        val holder = arrayOfNulls<IntArray>(1)
        val handle =
            nativeSweep("revolve a region by $angleDegrees degrees") {
                OcctBridge.nativeRevolveRegionNamed(
                    encoded.loopSizes,
                    encoded.kinds,
                    encoded.data,
                    angleDegrees,
                    axis.nativeCode,
                    queries.vertexXy,
                    queries.segQuery,
                    holder,
                )
            }
        return namedResult(handle, holder, queries)
    }

    /**
     * The edge indices (into `named.shape.edges`) generated from [sources] with [role], sorted and distinct.
     * Strict: every source must have at least one image.
     *
     * @param expected if set, the number of resulting edges must equal it.
     * @throws EdgeNamingException if a source has no image, or the count differs from [expected].
     * @throws IllegalArgumentException for a source that was not requested or a role that does not fit it.
     */
    fun generatedEdges(
        named: NamedShape,
        sources: List<GenerationSource>,
        role: GeneratedRole = GeneratedRole.SIDE,
        expected: Int? = null,
    ): List<Int> = resolveGeneratedImages(named.generation, sources, role, expected, named.shape, null)

    /** [fillet] on the edges generated from [sources] (see [generatedEdges]). */
    fun fillet(
        named: NamedShape,
        sources: List<GenerationSource>,
        radius: Double,
        role: GeneratedRole = GeneratedRole.SIDE,
        expected: Int? = null,
    ): OcctShape = fillet(named.shape, generatedEdges(named, sources, role, expected), radius)

    /** [chamfer] on the edges generated from [sources] (see [generatedEdges]). */
    fun chamfer(
        named: NamedShape,
        sources: List<GenerationSource>,
        distance: Double,
        role: GeneratedRole = GeneratedRole.SIDE,
        expected: Int? = null,
    ): OcctShape = chamfer(named.shape, generatedEdges(named, sources, role, expected), distance)

    // ---------------------------------------------------------------------------------------------
    // Welle N2 (ADR-0034): naming of the edges that fillet, chamfer, boolean and hole generate.
    // ---------------------------------------------------------------------------------------------

    /** Upper bound on the `Generated()` queries of one derived naming request, enforced in Kotlin and natively. */
    const val MAX_DERIVED_QUERIES: Int = 4096

    /**
     * [fillet] that also returns the [EdgeHistory] and a [GenerationMap] for [sources] ([BlendedEdges], [OperandEdges]
     * with role BOUNDARY), both read from the same OCCT maker. The edge indices equal the ones [fillet] returns.
     *
     * @throws IllegalArgumentException as [filletWithHistory], and for foreign, duplicate, mismatching or more than
     *   [MAX_NAMING_SOURCES] sources.
     * @throws dev.kstep.geometry.select.EdgeNamingException if an [OperandEdges] selector matches nothing.
     */
    fun filletNamed(
        shape: OcctShape,
        edgeIndices: Collection<Int>,
        radius: Double,
        sources: List<GenerationSource>,
    ): DerivedNamedShape {
        requireFilletArguments(edgeIndices, radius)
        requireSingleSolidOperand(shape, "fillet")
        requireDerivedSources(sources, DerivedOperation.BLEND)
        requireAvailable()
        val queries = blendQueries(shape, edgeIndices, sources)
        val history = arrayOfNulls<IntArray>(1)
        val map = arrayOfNulls<IntArray>(1)
        val indices = edgeIndices.toIntArray()
        val handle =
            shape.withHandle { h ->
                nativeSweep("fillet ${edgeIndices.size} edge(s) at radius $radius") {
                    OcctBridge.nativeFilletEdgesNamed(h, indices, radius, queries.triples, history, map)
                }
            }
        return derivedResult(handle, history, map, listOf(shape), queries, sources, DerivedOperation.BLEND, null)
    }

    /** [chamfer] with history and generation map; see [filletNamed]. */
    fun chamferNamed(
        shape: OcctShape,
        edgeIndices: Collection<Int>,
        distance: Double,
        sources: List<GenerationSource>,
    ): DerivedNamedShape {
        requireChamferArguments(edgeIndices, distance)
        requireSingleSolidOperand(shape, "chamfer")
        requireDerivedSources(sources, DerivedOperation.BLEND)
        requireAvailable()
        val queries = blendQueries(shape, edgeIndices, sources)
        val history = arrayOfNulls<IntArray>(1)
        val map = arrayOfNulls<IntArray>(1)
        val indices = edgeIndices.toIntArray()
        val handle =
            shape.withHandle { h ->
                nativeSweep("chamfer ${edgeIndices.size} edge(s) at distance $distance") {
                    OcctBridge.nativeChamferEdgesNamed(h, indices, distance, queries.triples, history, map)
                }
            }
        return derivedResult(handle, history, map, listOf(shape), queries, sources, DerivedOperation.BLEND, null)
    }

    /**
     * [boolean] that also returns the [EdgeHistory] and a [GenerationMap] for [sources] ([BooleanSection],
     * [OperandFaces] with role SECTION). The edges are those where the faces named by a source were cut by the other
     * body, measured after the coplanar faces were merged.
     */
    fun booleanNamed(
        op: BooleanOperation,
        a: OcctShape,
        b: OcctShape,
        sources: List<GenerationSource>,
    ): DerivedNamedShape {
        requireDerivedSources(sources, DerivedOperation.BOOLEAN)
        requireAvailable()
        return booleanNamedImpl(op, a, b, sources, DerivedOperation.BOOLEAN, null)
    }

    /**
     * [hole] with history and generation map. Besides the boolean sources, [HoleWall] is available with the roles
     * SECTION, RIM_ENTRY (the ring nearest to the start point `(x, y, z)` along the drill direction) and RIM_EXIT
     * (the farthest ring; empty for a blind hole). RIM_ENTRY and RIM_EXIT raise an [EdgeNamingException] when the
     * drill breaks out through a side face or the rings are ambiguous; SECTION stays usable as the fallback. Operand 1 of [OperandFaces] is the drill cylinder.
     */
    fun holeNamed(
        shape: OcctShape,
        diameter: Double,
        depth: Double,
        x: Double,
        y: Double,
        z: Double,
        dirX: Double = 0.0,
        dirY: Double = 0.0,
        dirZ: Double = -1.0,
        sources: List<GenerationSource>,
    ): DerivedNamedShape {
        validateDimension("diameter", diameter)
        validateDimension("depth", depth)
        requireDerivedSources(sources, DerivedOperation.HOLE)
        return makeCylinder(diameter / 2.0, depth, x, y, z, dirX, dirY, dirZ).use { tool ->
            val geometry = HoleGeometry(Point3(x, y, z), unitDirection(dirX, dirY, dirZ), diameter)
            booleanNamedImpl(BooleanOperation.CUT, shape, tool, sources, DerivedOperation.HOLE, geometry)
        }
    }

    /**
     * The edge indices (into `named.shape.edges`) generated from [sources] with [role], sorted and distinct. Strict:
     * every source must have at least one image.
     *
     * @param expected if set, the number of resulting edges must equal it.
     * @throws EdgeNamingException if a source has no image (a blind hole has no RIM_EXIT), or the count differs.
     * @throws IllegalArgumentException for a source that was not requested or a role that does not fit it.
     */
    fun generatedEdges(
        named: DerivedNamedShape,
        sources: List<GenerationSource>,
        role: GeneratedRole,
        expected: Int? = null,
    ): List<Int> = resolveGeneratedImages(named.generation, sources, role, expected, named.shape, null)

    private class HoleGeometry(
        val start: Point3,
        val direction: Direction3,
        val diameter: Double,
    )

    private fun booleanNamedImpl(
        op: BooleanOperation,
        a: OcctShape,
        b: OcctShape,
        sources: List<GenerationSource>,
        operation: DerivedOperation,
        hole: HoleGeometry?,
    ): DerivedNamedShape {
        val queries = booleanQueries(a, b, sources)
        val history = arrayOfNulls<IntArray>(1)
        val map = arrayOfNulls<IntArray>(1)
        val handle =
            a.withHandle { ha ->
                b.withHandle { hb ->
                    try {
                        OcctBridge.nativeBooleanOpNamed(op.nativeCode, ha, hb, queries.triples, history, map)
                    } catch (e: IllegalArgumentException) {
                        throw e
                    } catch (e: OcctEmptyResultException) {
                        throw e
                    } catch (e: RuntimeException) {
                        throw OcctGeometryException("OCCT failed to compute boolean $op: ${e.message}", e)
                    }
                }
            }
        return derivedResult(handle, history, map, listOf(a, b), queries, sources, operation, hole)
    }

    private fun requireDerivedSources(
        sources: List<GenerationSource>,
        operation: DerivedOperation,
    ) {
        require(sources.isNotEmpty()) { "at least one generation source is required" }
        require(sources.size <= MAX_NAMING_SOURCES) {
            "at most $MAX_NAMING_SOURCES generation sources are allowed, got ${sources.size}"
        }
        // Selector trees first and iteratively: hashing, comparing or describing a hostile deep tree recurses.
        sources.forEach { validateSourceSelector(it) }
        require(sources.toSet().size == sources.size) { "duplicate generation source" }
        sources.forEach { source ->
            require(source is DerivedGenerationSource) {
                "${source.describe()} is a profile source; a ${operationName(operation)} takes only " +
                    "BlendedEdges, OperandEdges, OperandFaces, BooleanSection or HoleWall"
            }
            val fits =
                when (source) {
                    BlendedEdges -> operation == DerivedOperation.BLEND
                    is OperandEdges -> operation == DerivedOperation.BLEND && source.operand == 0
                    is OperandFaces -> operation != DerivedOperation.BLEND && source.operand in 0..1
                    BooleanSection -> operation != DerivedOperation.BLEND
                    HoleWall -> operation == DerivedOperation.HOLE
                }
            require(fits) { "${source.describe()} does not fit a ${operationName(operation)}" }
        }
    }

    private fun validateSourceSelector(source: GenerationSource) {
        when (source) {
            is OperandEdges -> SelectorTree.validate(source.selector)
            is OperandFaces -> SelectorTree.validate(source.selector)
            else -> Unit
        }
    }

    private fun operationName(operation: DerivedOperation) =
        when (operation) {
            DerivedOperation.BLEND -> "fillet or chamfer"
            DerivedOperation.BOOLEAN -> "boolean"
            DerivedOperation.HOLE -> "hole"
        }

    private fun blendQueries(
        shape: OcctShape,
        edgeIndices: Collection<Int>,
        sources: List<GenerationSource>,
    ): DerivedQueries {
        val b = DerivedQueryBuilder()
        sources.forEachIndexed { i, source ->
            val edges =
                when (source) {
                    BlendedEdges -> edgeIndices.toList()
                    is OperandEdges -> resolveOperandEdges(shape, source)
                    else -> error("unreachable: ${source.describe()} was validated")
                }
            edges.forEach { b.add(i, 0, DERIVED_KIND_EDGE, it) }
        }
        // Everything the operation acted on: the "created faces" that BOUNDARY is measured against.
        edgeIndices.forEach { b.add(DerivedQueries.EXTRA_OWNER, 0, DERIVED_KIND_EDGE, it) }
        return b.build()
    }

    private fun booleanQueries(
        a: OcctShape,
        b: OcctShape,
        sources: List<GenerationSource>,
    ): DerivedQueries {
        val builder = DerivedQueryBuilder()
        val operands = listOf(a, b)
        sources.forEachIndexed { i, source ->
            when (source) {
                BooleanSection ->
                    operands.forEachIndexed { slot, operand ->
                        repeat(operand.topology.faces) { builder.add(i, slot, DERIVED_KIND_FACE, it) }
                    }
                is OperandFaces ->
                    resolveOperandFaces(operands[source.operand], source).forEach {
                        builder.add(i, source.operand, DERIVED_KIND_FACE, it)
                    }
                HoleWall -> {
                    val wall = b.faces.filter { it.kind == FaceKind.CYLINDER }
                    check(wall.size == 1) { "the drill has ${wall.size} cylindrical faces, expected exactly one" }
                    builder.add(i, 1, DERIVED_KIND_FACE, wall[0].index)
                }
                else -> error("unreachable: ${source.describe()} was validated")
            }
        }
        return builder.build()
    }

    private fun resolveOperandEdges(
        shape: OcctShape,
        source: OperandEdges,
    ): List<Int> =
        try {
            EdgeSelection.resolve(shape.edges, shape.faces, source.selector)
        } catch (e: EdgeSelectionException) {
            throw EdgeNamingException(
                "${source.describe()} matched no edge on the operand\n" + e.message,
                null,
                listOf(source),
                emptyList(),
                emptyMap(),
                e.candidates,
            )
        }

    private fun resolveOperandFaces(
        shape: OcctShape,
        source: OperandFaces,
    ): List<Int> {
        val faces = EdgeSelection.resolveFaces(shape.edges, shape.faces, source.selector)
        if (faces.isEmpty()) {
            throw EdgeNamingException(
                "${source.describe()} matched no face on operand ${source.operand}. Candidate faces:\n" +
                    describeFaces(shape.faces),
                null,
                listOf(source),
                emptyList(),
                emptyMap(),
                shape.edges.filter { !it.seam && !it.degenerated },
            )
        }
        return faces
    }

    private fun derivedResult(
        handle: Long,
        historyHolder: Array<IntArray?>,
        mapHolder: Array<IntArray?>,
        operands: List<OcctShape>,
        queries: DerivedQueries,
        sources: List<GenerationSource>,
        operation: DerivedOperation,
        hole: HoleGeometry?,
    ): DerivedNamedShape {
        val shape = OcctShape(handle)
        try {
            val rawHistory = checkNotNull(historyHolder[0]) { "native bridge returned no edge history" }
            val rawMap = checkNotNull(mapHolder[0]) { "native bridge returned no derived generation map" }
            val topology = shape.topology
            val history = decodeEdgeHistory(rawHistory, operands.map { it.topology.edges }, topology.edges)
            val images = decodeDerivedGeneration(rawMap, queries.count, topology.edges, topology.faces)
            val (derived, roleErrors) = derivedMap(sources, queries, images, shape, operation, hole)
            return DerivedNamedShape(shape, history, GenerationMap(emptyMap(), derived, roleErrors))
        } catch (e: Throwable) {
            shape.close()
            throw e
        }
    }

    private fun derivedMap(
        sources: List<GenerationSource>,
        queries: DerivedQueries,
        images: List<DerivedImages>,
        shape: OcctShape,
        operation: DerivedOperation,
        hole: HoleGeometry?,
    ): Pair<
        Map<GenerationSource, Map<GeneratedRole, List<Int>>>,
        Map<GenerationSource, Map<GeneratedRole, EdgeNamingException>>,
    > {
        val roleErrors = LinkedHashMap<GenerationSource, Map<GeneratedRole, EdgeNamingException>>()
        val edgesBy = List(sources.size) { java.util.TreeSet<Int>() }
        val facesBy = List(sources.size) { java.util.TreeSet<Int>() }
        val allBlendFaces = java.util.TreeSet<Int>()
        images.forEachIndexed { q, image ->
            val owner = queries.owner[q]
            if (owner == DerivedQueries.EXTRA_OWNER) {
                allBlendFaces.addAll(image.faces)
            } else {
                edgesBy[owner].addAll(image.edges)
                facesBy[owner].addAll(image.faces)
            }
        }
        val edgeInfos = shape.edges
        val out = LinkedHashMap<GenerationSource, Map<GeneratedRole, List<Int>>>()
        sources.forEachIndexed { i, source ->
            out[source] =
                when (operation) {
                    DerivedOperation.BLEND ->
                        mapOf(
                            GeneratedRole.BOUNDARY to boundaryEdges(facesBy[i], allBlendFaces, shape.faces, edgeInfos),
                        )
                    DerivedOperation.BOOLEAN, DerivedOperation.HOLE -> {
                        val section = edgesBy[i].filter { !edgeInfos[it].seam && !edgeInfos[it].degenerated }
                        if (source == HoleWall) {
                            var rimRings: List<List<Int>> = emptyList()
                            val geometry = checkNotNull(hole) { "a hole wall needs the hole geometry" }
                            // Only the rim roles fail when the rims cannot be told apart; SECTION stays usable.
                            val rimError: EdgeNamingException? =
                                try {
                                    val infos = section.map { edgeInfos[it] }
                                    val rings =
                                        classifyRims(
                                            infos,
                                            geometry.start,
                                            geometry.direction,
                                            1e-6,
                                            geometry.diameter,
                                            shape.faces,
                                            geometry.diameter / 2.0,
                                        )
                                    rimRings = rings
                                    null
                                } catch (e: EdgeNamingException) {
                                    e
                                } catch (e: IllegalArgumentException) {
                                    EdgeNamingException(
                                        "hole rim classification failed: ${e.message}",
                                        null,
                                        listOf(HoleWall),
                                        emptyList(),
                                        mapOf("HoleWall" to section),
                                        section.map { edgeInfos[it] },
                                    )
                                }
                            if (rimError != null) {
                                roleErrors[source] =
                                    mapOf(GeneratedRole.RIM_ENTRY to rimError, GeneratedRole.RIM_EXIT to rimError)
                            }
                            val rings = if (rimError == null) rimRings else emptyList()
                            mapOf(
                                GeneratedRole.SECTION to section,
                                GeneratedRole.RIM_ENTRY to (rings.firstOrNull() ?: emptyList()),
                                GeneratedRole.RIM_EXIT to (if (rings.size >= 2) rings.last() else emptyList()),
                            )
                        } else {
                            mapOf(GeneratedRole.SECTION to section)
                        }
                    }
                }
        }
        return out to roleErrors
    }

    private fun nativeSweep(
        what: String,
        call: () -> Long,
    ): Long =
        try {
            call()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw OcctGeometryException("OCCT failed to $what", e)
        }

    private fun namedResult(
        handle: Long,
        holder: Array<IntArray?>,
        queries: NamingQueries,
    ): NamedShape {
        val shape = OcctShape(handle)
        try {
            val raw = checkNotNull(holder[0]) { "native bridge returned no generation map" }
            return NamedShape(shape, decodeGenerationMap(raw, queries, shape.topology.edges))
        } catch (e: Throwable) {
            shape.close()
            throw e
        }
    }

    private fun requireExtrudeHeight(height: Double) {
        require(height.isFinite()) { "height must be finite, got $height" }
        require(abs(height) > MIN_DIMENSION) { "height magnitude must be greater than $MIN_DIMENSION, got $height" }
        require(abs(height) <= MAX_DIMENSION) { "height magnitude must be at most $MAX_DIMENSION, got $height" }
    }

    private fun requireRevolveAngle(angleDegrees: Double) {
        require(angleDegrees.isFinite()) { "angleDegrees must be finite, got $angleDegrees" }
        require(angleDegrees >= MIN_REVOLVE_ANGLE_DEGREES) {
            "angleDegrees must be at least $MIN_REVOLVE_ANGLE_DEGREES, got $angleDegrees"
        }
        require(angleDegrees <= MAX_REVOLVE_ANGLE_DEGREES) {
            "angleDegrees must be at most $MAX_REVOLVE_ANGLE_DEGREES, got $angleDegrees"
        }
    }

    private fun requireRevolveProfileArguments(
        profile: List<ProfilePoint>,
        angleDegrees: Double,
        axis: RevolveAxis,
    ) {
        validateProfile(profile, MAX_REVOLVE_PROFILE_POINTS)
        requireRevolveAngle(angleDegrees)
        val radial: (ProfilePoint) -> Double = if (axis == RevolveAxis.Y) { p -> p.x } else { p -> p.y }
        require(profile.none { radial(it) > 0.0 } || profile.none { radial(it) < 0.0 }) {
            "profile crosses the rotation axis ${axis.name}: all " +
                "${if (axis == RevolveAxis.Y) "x" else "y"} coordinates must have the same sign"
        }
    }

    private fun requireRevolveRegionArguments(
        region: ProfileRegion,
        angleDegrees: Double,
        axis: RevolveAxis,
    ) {
        RegionValidation.validate(region)
        requireRevolveAngle(angleDegrees)
        val radial: (ProfilePoint) -> Double = if (axis == RevolveAxis.Y) { p -> p.x } else { p -> p.y }
        val samples = (listOf(region.outer) + region.holes).flatMap { RegionValidation.polygon(it) }
        require(samples.none { radial(it) > 1e-9 } || samples.none { radial(it) < -1e-9 }) {
            "region crosses the rotation axis ${axis.name}: all " +
                "${if (axis == RevolveAxis.Y) "x" else "y"} coordinates must have the same sign"
        }
    }

    /**
     * Volume and face count of every solid of [shape] (at most 1024 solids), in the order OCCT enumerates
     * them -- the per-body view of a multi-body shape (ADR-0032).
     *
     * @throws OcctUnavailableException if the native bridge is not available.
     */
    fun solidSummaries(shape: OcctShape): List<SolidSummary> {
        requireAvailable()
        val raw = shape.withHandle { OcctBridge.nativeSolidSummaries(it) }
        return List(raw.size / 2) { SolidSummary(raw[2 * it], raw[2 * it + 1].toInt()) }
    }

    private val SURFACE_TYPE_KEYS =
        listOf(
            "plane",
            "cylinder",
            "cone",
            "sphere",
            "torus",
            "bezierSurface",
            "bsplineSurface",
            "surfaceOfRevolution",
            "surfaceOfExtrusion",
            "offsetSurface",
            "other",
        )

    /**
     * Number of faces per surface kind, in a stable key vocabulary (`plane`, `cylinder`, `cone`, `sphere`,
     * `torus`, `bezierSurface`, `bsplineSurface`, `surfaceOfRevolution`, `surfaceOfExtrusion`, `offsetSurface`,
     * `other`); only kinds that occur are present. [dev.kstep.geometry.select.FaceKind] is deliberately not
     * widened for this: a B-spline face stays `FaceKind.OTHER` there.
     */
    fun surfaceTypeCounts(shape: OcctShape): Map<String, Int> {
        requireAvailable()
        val raw = shape.withHandle { OcctBridge.nativeShapeSurfaceTypeCounts(it) }
        check(raw.size == SURFACE_TYPE_KEYS.size) { "unexpected native surface type payload" }
        return SURFACE_TYPE_KEYS.indices.filter { raw[it] > 0 }.associate { SURFACE_TYPE_KEYS[it] to raw[it] }
    }

    private fun requireFilletArguments(
        edgeIndices: Collection<Int>,
        radius: Double,
    ) {
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
    }

    private fun requireChamferArguments(
        edgeIndices: Collection<Int>,
        distance: Double,
    ) {
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
