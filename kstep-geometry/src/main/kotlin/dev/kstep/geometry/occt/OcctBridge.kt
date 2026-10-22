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

    /**
     * Returns a rigidly transformed COPY of the shape (`BRepBuilderAPI_Transform`, copy=true).
     *
     * @param matrix row-major 3x4 `[r00 r01 r02 tx, r10 r11 r12 ty, r20 r21 r22 tz]`; the rotation
     *   block must be orthonormal with determinant > 0 (no scaling, shear or reflection).
     * @throws NullPointerException if `matrix` is null.
     * @throws IllegalArgumentException if `matrix` does not have 12 finite entries, the translation
     *   exceeds [dev.kstep.geometry.OcctKernel.MAX_ABS_COORDINATE], or the rotation block is not a
     *   proper rotation.
     * @throws IllegalStateException if `handle` is unknown.
     */
    external fun nativeTransformShape(
        handle: Long,
        matrix: DoubleArray,
    ): Long

    /**
     * Builds a cylinder (`BRepPrimAPI_MakeCylinder`) whose base circle is centred at `(ox, oy, oz)`
     * with axis direction `(dx, dy, dz)` (need not be normalized, must not be null).
     *
     * @throws IllegalArgumentException if radius/height are not finite and within
     *   `[MIN_DIMENSION, MAX_DIMENSION]`, the origin is not finite/within range, or the direction
     *   is not finite or shorter than [dev.kstep.geometry.OcctKernel.MIN_DIRECTION_LENGTH].
     */
    external fun nativeMakeCylinder(
        radius: Double,
        height: Double,
        ox: Double,
        oy: Double,
        oz: Double,
        dx: Double,
        dy: Double,
        dz: Double,
    ): Long

    /**
     * Boolean operation `left <op> right` (`op`: 0 = fuse, 1 = cut, 2 = common) producing a single
     * solid.
     *
     * @throws IllegalArgumentException if `op` is unknown, an operand exceeds
     *   [dev.kstep.geometry.OcctKernel.MAX_BOOLEAN_INPUT_FACES] faces or contains no solid.
     * @throws IllegalStateException if a handle is unknown, OCCT reports an error, or the result has
     *   more than one disjoint solid.
     * @throws dev.kstep.geometry.OcctEmptyResultException if the result contains no volume.
     */
    external fun nativeBooleanOp(
        op: Int,
        left: Long,
        right: Long,
    ): Long

    /**
     * Revolves a closed planar XY profile about a coordinate axis through the origin
     * (`BRepPrimAPI_MakeRevol`; `axis`: 0 = X, 1 = Y).
     *
     * @throws IllegalArgumentException for an invalid profile, angle outside
     *   `[MIN_REVOLVE_ANGLE_DEGREES, 360]`, unknown axis, a profile crossing the axis, or a
     *   degenerate result.
     */
    external fun nativeRevolveProfile(
        profileXy: DoubleArray,
        angleDegrees: Double,
        axis: Int,
    ): Long

    /**
     * Chamfers (symmetric distance) the given edges, 0-based into the same
     * `TopExp::MapShapes(TopAbs_EDGE)` ordering [nativeFilletEdges] uses.
     */
    external fun nativeChamferEdges(
        handle: Long,
        edgeIndices: IntArray,
        distance: Double,
    ): Long

    /**
     * Triangulates the surface of the shape (`BRepMesh_IncrementalMesh`) and returns a
     * header-prefixed, index-free triangle soup with an OPTIONAL per-vertex-normal block: winding
     * normalized to point outward, vertices in world coordinates.
     *
     * DELIBERATELY WITHOUT a quality parameter: the linear deflection is derived natively from
     * the shape's bounding-box diagonale (x 0.005, clamped to `[1e-5, 1e3]`). The only input is
     * therefore the already-hardened handle -- a caller cannot force a `deflection=1e-9` DoS. A
     * quality knob is Folge-Welle V-8's job, once something actually needs it.
     *
     * The triangulation is discarded again natively via `BRepTools::Clean` (RAII) right after
     * extraction, so the shape does not grow with every call -- this makes the call repeatable
     * and idempotent, but not free (re-meshed every time).
     *
     * Per-vertex normals were added in a later wave (see
     * docs/adr/ADR-0018-smooth-vertex-normals.adoc): the native side runs
     * `BRepLib_ToolTriangulatedShape::ComputeNormals` per face (only where OCCT's own mesher did
     * not already attach normals) and, if and ONLY if *every* face in the shape ends up with
     * normals, extracts a per-vertex normal for each triangle corner alongside its position --
     * all-or-nothing, exactly like [dev.kstep.geometry.TriangleMesh.vertexNormals]'s own KDoc
     * documents. `ComputeNormals` itself does NOT respect `TopAbs_REVERSED` (empirically
     * verified against OCCT 7.9.2 -- a REVERSED and a FORWARD face of the same box produce
     * IDENTICAL raw normals), so a REVERSED face's normals are negated natively, the same way its
     * triangle winding already is.
     *
     * @return a `DoubleArray` laid out as: `result[0]` = triangle count `N` (exact, as a
     *   `Double`); `result[1 .. 9N]` = positions, 9 doubles per triangle (unchanged content and
     *   order from before this header element existed); `result[9N+1 .. 18N]` = per-vertex
     *   normals, 9 doubles per triangle, present ONLY if the shape's normals were fully computed.
     *   `result.size == 1` means zero triangles (still a valid, non-exceptional result). This
     *   header element exists because, without it, `9*N` doubles (no normals) and `18*N` doubles
     *   (normals) collide for any even `N` -- see [dev.kstep.geometry.OcctShape]'s
     *   `decodeTriangles` for the decoder and docs/adr/ADR-0018-smooth-vertex-normals.adoc's
     *   Stolperfallen for the ambiguity this closes.
     * @throws IllegalArgumentException if the shape would triangulate to more than
     *   [dev.kstep.geometry.OcctKernel.MAX_TRIANGLES] triangles.
     * @throws IllegalStateException if `handle` is unknown, or if `BRepMesh_IncrementalMesh`
     *   did not fully triangulate the shape (its own `IsDone()` is `false`, or at least one face
     *   ends up with no triangulation) -- checked explicitly so a partially-meshed shape fails
     *   loudly instead of silently returning an incomplete triangle soup. Both cases are wrapped
     *   by [dev.kstep.geometry.OcctShape.triangulate] into [dev.kstep.geometry.OcctGeometryException].
     */
    external fun nativeShapeTriangles(handle: Long): DoubleArray

    // ---------------------------------------------------------------------------------------
    // Welle K: STEP import via XCAF (docs/adr/ADR-0030-read-geometry.adoc)
    // ---------------------------------------------------------------------------------------

    /**
     * Reads a STEP file (as BYTES -- OCCT never opens a path itself) into an XCAF document and
     * returns a session handle describing its product structure. Release with [nativeReleaseImport].
     *
     * @param limits `[maxEntities, maxNodes, maxComponents, maxParts, maxFacesTotal, maxNameChars]`,
     *   all positive; hand-synchronized with [dev.kstep.geometry.StepImportLimits].
     * @throws NullPointerException if an argument is null.
     * @throws IllegalArgumentException if `limits` is malformed, the bytes are not a readable STEP
     *   file, or a limit is exceeded (message starts with `limit:`).
     * @throws IllegalStateException if OCCT's transfer into the document fails.
     */
    external fun nativeReadStepXcaf(
        content: ByteArray,
        limits: IntArray,
    ): Long

    /** 6 ints per node: kind (0 part, 1 assembly, 2 component), parent, referred, freeRoot, rigid, shapeSlot. */
    external fun nativeImportNodeInts(session: Long): IntArray

    /** 12 doubles per node: row-major 3x4 local matrix (identity for non-components). */
    external fun nativeImportNodeMatrices(session: Long): DoubleArray

    external fun nativeImportNodeName(
        session: Long,
        index: Int,
    ): String

    /** `[entityCount, unknownEntityCount, freeRootCount, nodeCount]`. */
    external fun nativeImportStats(session: Long): IntArray

    /** Distinct unknown entity type names, `\n`-joined, ASCII `[A-Z0-9_]` only. */
    external fun nativeImportUnknownTypes(session: Long): String

    /** Registers a COPY of the part's shape as a normal shape handle (caller releases it). */
    external fun nativeImportPartShape(
        session: Long,
        nodeIndex: Int,
    ): Long

    external fun nativeReleaseImport(session: Long)

    /** Number of live import sessions; diagnostic, used by the leak-probe test. */
    external fun nativeImportSessionCount(): Int

    /** `[minX, minY, minZ, maxX, maxY, maxZ]`, or an empty array if the shape has no extent. */
    external fun nativeShapeBoundingBox(handle: Long): DoubleArray

    // ---------------------------------------------------------------------------------------
    // Welle L: edge/face queries, edge-history-tracking variants, sketch regions
    // (docs/adr/ADR-0031-sketch-dsl-and-edge-selection.adoc)
    // ---------------------------------------------------------------------------------------

    /**
     * One record of `EDGE_RECORD = 20` doubles per edge, in `TopExp::MapShapes(TopAbs_EDGE)` order:
     * `kind` (0 line, 1 circle, 2 other), `length`, start xyz, end xyz, midpoint xyz, direction xyz
     * (line: unit direction; circle: axis), circle centre xyz, radius, `seam`, `degenerated`.
     * Throws `IllegalArgumentException` above 20 000 edges / 10 000 faces.
     */
    external fun nativeShapeEdgeInfo(handle: Long): DoubleArray

    /**
     * One record of `FACE_RECORD = 12` doubles per face (`MapShapes(TopAbs_FACE)` order): `kind`
     * (0 plane, 1 cylinder, 2 other), area, centroid xyz, normal xyz (plane: outward; cylinder:
     * axis), axis origin xyz, radius.
     */
    external fun nativeShapeFaceInfo(handle: Long): DoubleArray

    /** CSR face-to-edge adjacency: `[faceCount, offsets (faceCount + 1)..., edge indices...]`. */
    external fun nativeShapeFaceEdges(handle: Long): IntArray

    /**
     * Same as [nativeFilletEdges]; additionally stores the encoded edge history (see
     * `dev.kstep.geometry.decodeEdgeHistory`) in `outHistory[0]`.
     */
    external fun nativeFilletEdgesTracked(
        handle: Long,
        edgeIndices: IntArray,
        radius: Double,
        outHistory: Array<IntArray?>,
    ): Long

    /** Same as [nativeChamferEdges] plus the encoded edge history in `outHistory[0]`. */
    external fun nativeChamferEdgesTracked(
        handle: Long,
        edgeIndices: IntArray,
        distance: Double,
        outHistory: Array<IntArray?>,
    ): Long

    /** Same as [nativeBooleanOp] plus the encoded edge history (two operands) in `outHistory[0]`. */
    external fun nativeBooleanOpTracked(
        op: Int,
        left: Long,
        right: Long,
        outHistory: Array<IntArray?>,
    ): Long

    /** Same as [nativeTransformShape] plus the encoded edge history in `outHistory[0]`. */
    external fun nativeTransformShapeTracked(
        handle: Long,
        matrix: DoubleArray,
        outHistory: Array<IntArray?>,
    ): Long

    /**
     * Extrudes a planar region (outer loop + holes) along +Z. `loopSizes[l]` is the segment count
     * of loop `l` (loop 0 is the outer contour); `segKinds[s]` is 0 line, 1 arc, 2 circle;
     * `segData` holds 6 doubles per segment (line `x1 y1 x2 y2`, arc `x1 y1 xm ym x2 y2`, circle
     * `cx cy r`).
     */
    external fun nativeExtrudeRegion(
        loopSizes: IntArray,
        segKinds: IntArray,
        segData: DoubleArray,
        height: Double,
    ): Long

    /** Revolves a planar region about the X (0) or Y (1) axis; same region encoding as [nativeExtrudeRegion]. */
    external fun nativeRevolveRegion(
        loopSizes: IntArray,
        segKinds: IntArray,
        segData: DoubleArray,
        angleDegrees: Double,
        axis: Int,
    ): Long

    /**
     * Combines 1..32 registered single-solid shapes into one compound of independent copies; touching or
     * overlapping parts are rejected with `IllegalArgumentException` (ADR-0032).
     */
    external fun nativeMakeCompound(handles: LongArray): Long

    /** `[volume, faceCount]` for every solid of the shape (at most 1024 solids), ADR-0032. */
    external fun nativeSolidSummaries(handle: Long): DoubleArray
}
