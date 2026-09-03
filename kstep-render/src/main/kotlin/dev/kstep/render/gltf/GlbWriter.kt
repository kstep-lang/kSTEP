package dev.kstep.render.gltf

import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.RenderLimits
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/**
 * Writes a [TriangleMesh] as binary glTF 2.0 (GLB) -- non-indexed, flat-shaded, one PBR
 * metallic-roughness material, no textures. `kstep render`'s `-f glb`/`-f gltf` container (see
 * `RenderFormat`) and `dev.kstep.cli.RenderCommand`.
 *
 * A hand-rolled writer, not a wrapper around OCCT's `RWGltf_CafWriter` -- see
 * docs/adr/ADR-0016-gltf-glb-export.adoc's Decision section for why: this writer needs no new
 * native code, and works on the same plain [TriangleMesh]/`DoubleArray` this whole module already
 * consumes, so it is testable without OCCT at all (unlike `RWGltf_CafWriter`, which would need an
 * `XCAFDoc` document this module has no reason to build).
 *
 * The exact binary/JSON layout below is verified against the official Khronos `gltf-validator`
 * (see docs/adr/ADR-0016-gltf-glb-export.adoc's Stolperfallen section) -- every structural choice
 * here (key presence, key order, chunk padding byte) reflects a measured validator result, not
 * just a reading of the spec text. The most important of those, because the validator does NOT
 * catch a regression here (see that ADR's Stolperfalle 1): `accessors[0].min`/`max` are computed
 * from the float32-narrowed position values actually written to the buffer, never from the source
 * [TriangleMesh]'s `Double`s.
 *
 * Coordinate system: kSTEP/OCCT model space is Z-up, glTF is Y-up. The conversion is a `-90`
 * degree rotation about X, applied as the scene [nodes] entry's `rotation` quaternion -- the
 * buffer data itself stays in model-space coordinates. This keeps `accessors[0].min`/`max` (which
 * the glTF spec defines as pre-node-transform, i.e. in the accessor's own local space) trivially
 * correct, and lets a caller compare buffer bytes directly against [TriangleMesh.coordinates]
 * without first undoing a transform.
 *
 * Winding: [TriangleMesh] already guarantees its stored winding makes `n = (v1-v0) x (v2-v0)`
 * point outward (see that class's KDoc) -- exactly glTF's own front-face convention (CCW,
 * right-hand rule) -- so this writer never flips a triangle. `materials[0].doubleSided` is set to
 * `false` explicitly (rather than left at its glTF-spec default of `false`) so a future winding
 * regression in [TriangleMesh] shows up as visibly missing/inside-out geometry in a glTF viewer,
 * instead of being silently hidden by two-sided rendering.
 */
object GlbWriter {
    /** `asset.generator`. Deliberately carries neither a version number nor a timestamp: this
     *  writer is byte-deterministic (identical [TriangleMesh]/[GlbExtras] input always produces
     *  identical output bytes), matching [dev.kstep.render.svg.TriangleSvgWriter]'s own
     *  determinism contract for SVG. */
    const val GENERATOR: String = "kSTEP (dev.kstep.render.gltf.GlbWriter)"

    // GLB container magic numbers (glTF 2.0 binary format, section "Binary glTF Layout").
    private const val GLB_MAGIC = 0x46546C67 // "glTF"
    private const val GLB_VERSION = 2
    private const val CHUNK_TYPE_JSON = 0x4E4F534A // "JSON"
    private const val CHUNK_TYPE_BIN = 0x004E4942 // "BIN\0"
    private const val GLB_HEADER_LENGTH = 12
    private const val CHUNK_HEADER_LENGTH = 8
    private const val JSON_CHUNK_PAD_BYTE: Byte = 0x20 // space -- required padding byte for the JSON chunk
    private const val BIN_CHUNK_PAD_BYTE: Byte = 0x00 // required padding byte for the BIN chunk

    private const val ALIGNMENT = 4
    private const val BYTES_PER_FLOAT = 4
    private const val COMPONENTS_PER_VEC3 = 3
    private const val VERTICES_PER_TRIANGLE = 3
    private const val ACCESSOR_COMPONENT_TYPE_FLOAT = 5126
    private const val BUFFER_VIEW_TARGET_ARRAY_BUFFER = 34962
    private const val PRIMITIVE_MODE_TRIANGLES = 4

    // -90 degrees about X, as a quaternion (x, y, z, w): maps model-space +Z to glTF's +Y.
    // sqrt(0.5) computed once at class-init time, not hardcoded, so its bit pattern is exactly
    // what kotlin.math.sqrt produces on this JVM rather than a manually-transcribed literal.
    private val HALF_ROOT_TWO = sqrt(0.5)
    private val NODE_ROTATION_Z_UP_TO_Y_UP = doubleArrayOf(-HALF_ROOT_TWO, 0.0, 0.0, HALF_ROOT_TWO)

    // A neutral, slightly cool light grey -- the closest a PBR material has to the flat
    // mid-tone grey dev.kstep.render.image.TriangleRasterizer/TriangleSvgWriter shade unlit
    // triangles with. glTF viewers light this material themselves; there is no unlit/flat
    // equivalent in core glTF 2.0 without the KHR_materials_unlit extension, which this writer
    // does not use (kept out of scope -- see the ADR's Folge-Wellen table).
    private val BASE_COLOR_FACTOR = doubleArrayOf(0.72, 0.75, 0.78, 1.0)
    private const val METALLIC_FACTOR = 0.1
    private const val ROUGHNESS_FACTOR = 0.7

    // A triangle is dropped (never a hard failure) rather than written, if either its normal is
    // degenerate or any coordinate cannot round-trip through float32 -- see collectTriangles.
    private const val DEGENERATE_NORMAL_LENGTH_EPSILON = 1e-12
    private val FLOAT_MAX_ABS = Float.MAX_VALUE.toDouble()

    /**
     * Writes [mesh] as a binary glTF 2.0 document. A triangle whose normal is degenerate (zero
     * cross-product length -- a collinear or zero-area triangle) or that carries a non-finite or
     * float32-unrepresentable coordinate is silently dropped, not written and not an error (an
     * `ACCESSOR_VECTOR3_NON_UNIT`/`ACCESSOR_INVALID_FLOAT` validator error is exactly what
     * writing it verbatim would produce -- see docs/adr/ADR-0016-gltf-glb-export.adoc's
     * Stolperfallen). If every triangle is dropped (or [mesh] was already empty), this delegates
     * to [writeEmptyScene] -- the GLB expression of `kstep render`'s "Container-Regel" (see
     * docs/adr/ADR-0011-headless-preview-rendering.adoc): the container is always valid, only its
     * content varies.
     *
     * @throws IllegalArgumentException if `mesh.triangleCount` exceeds
     *   [dev.kstep.geometry.OcctKernel.MAX_TRIANGLES] -- checked BEFORE any output buffer is
     *   allocated, mirroring [dev.kstep.geometry.MeshComposition.merge]'s identical guard. Needed
     *   because [TriangleMesh] is a public type backed by a plain `DoubleArray`; the native
     *   triangle-count guard only bounds triangulation results actually produced by OCCT.
     */
    fun write(
        mesh: TriangleMesh,
        extras: GlbExtras = GlbExtras.EMPTY,
    ): GlbWriteResult {
        require(mesh.triangleCount <= OcctKernel.MAX_TRIANGLES) {
            "mesh.triangleCount must be at most ${OcctKernel.MAX_TRIANGLES}, got ${mesh.triangleCount}"
        }
        if (mesh.triangleCount > RenderLimits.GLB_TRIANGLE_WARN_THRESHOLD) {
            logger.warn {
                "GlbWriter: writing ${mesh.triangleCount} triangles, above the " +
                    "GLB_TRIANGLE_WARN_THRESHOLD of ${RenderLimits.GLB_TRIANGLE_WARN_THRESHOLD} -- output GLB " +
                    "will be large"
            }
        }

        val kept = collectTriangles(mesh)
        val droppedTriangleCount = mesh.triangleCount - kept.size
        if (kept.isEmpty()) {
            if (mesh.triangleCount > 0) {
                logger.warn {
                    "GlbWriter: all ${mesh.triangleCount} triangle(s) were dropped as degenerate or " +
                        "non-finite -- writing an empty scene instead"
                }
            }
            return buildEmptyScene(extras, droppedTriangleCount)
        }

        val vertexCount = kept.size * VERTICES_PER_TRIANGLE
        val positions = FloatArray(vertexCount * COMPONENTS_PER_VEC3)
        val normals = FloatArray(vertexCount * COMPONENTS_PER_VEC3)
        var vertex = 0
        for (triangle in kept) {
            for (corner in 0 until VERTICES_PER_TRIANGLE) {
                val base = vertex * COMPONENTS_PER_VEC3
                positions[base] = triangle.coordinates[corner * 3].toFloat()
                positions[base + 1] = triangle.coordinates[corner * 3 + 1].toFloat()
                positions[base + 2] = triangle.coordinates[corner * 3 + 2].toFloat()
                normals[base] = triangle.normal[0].toFloat()
                normals[base + 1] = triangle.normal[1].toFloat()
                normals[base + 2] = triangle.normal[2].toFloat()
                vertex++
            }
        }

        val (positionMin, positionMax) = float32Bounds(positions)
        val positionBytes = floatsToLittleEndianBytes(positions)
        val normalBytes = floatsToLittleEndianBytes(normals)
        val binChunk = padTo(positionBytes + normalBytes, ALIGNMENT, BIN_CHUNK_PAD_BYTE)

        val document =
            buildJsonObject {
                put("asset", assetJson(extrasJson(extras)))
                put("scene", 0)
                putJsonArray("scenes") { addJsonObject { putJsonArray("nodes") { add(0) } } }
                putJsonArray("nodes") {
                    addJsonObject {
                        put("mesh", 0)
                        putJsonArray("rotation") { NODE_ROTATION_Z_UP_TO_Y_UP.forEach { add(it) } }
                    }
                }
                putJsonArray("meshes") {
                    addJsonObject {
                        putJsonArray("primitives") {
                            addJsonObject {
                                putJsonObject("attributes") {
                                    put("POSITION", 0)
                                    put("NORMAL", 1)
                                }
                                put("mode", PRIMITIVE_MODE_TRIANGLES)
                                put("material", 0)
                            }
                        }
                    }
                }
                putJsonArray("materials") {
                    addJsonObject {
                        putJsonObject("pbrMetallicRoughness") {
                            putJsonArray("baseColorFactor") { BASE_COLOR_FACTOR.forEach { add(it) } }
                            put("metallicFactor", METALLIC_FACTOR)
                            put("roughnessFactor", ROUGHNESS_FACTOR)
                        }
                        put("doubleSided", false)
                    }
                }
                putJsonArray("accessors") {
                    addJsonObject {
                        put("bufferView", 0)
                        put("componentType", ACCESSOR_COMPONENT_TYPE_FLOAT)
                        put("count", vertexCount)
                        put("type", "VEC3")
                        putJsonArray("min") { positionMin.forEach { add(it) } }
                        putJsonArray("max") { positionMax.forEach { add(it) } }
                    }
                    addJsonObject {
                        put("bufferView", 1)
                        put("componentType", ACCESSOR_COMPONENT_TYPE_FLOAT)
                        put("count", vertexCount)
                        put("type", "VEC3")
                    }
                }
                putJsonArray("bufferViews") {
                    addJsonObject {
                        put("buffer", 0)
                        put("byteOffset", 0)
                        put("byteLength", positionBytes.size)
                        put("target", BUFFER_VIEW_TARGET_ARRAY_BUFFER)
                    }
                    addJsonObject {
                        put("buffer", 0)
                        put("byteOffset", positionBytes.size)
                        put("byteLength", normalBytes.size)
                        put("target", BUFFER_VIEW_TARGET_ARRAY_BUFFER)
                    }
                }
                putJsonArray("buffers") {
                    addJsonObject { put("byteLength", binChunk.size) }
                }
            }
        val jsonChunk = padTo(document.toString().toByteArray(Charsets.UTF_8), ALIGNMENT, JSON_CHUNK_PAD_BYTE)

        return GlbWriteResult(
            bytes = assembleContainer(jsonChunk, binChunk),
            triangleCount = kept.size,
            vertexCount = vertexCount,
            droppedTriangleCount = droppedTriangleCount,
        )
    }

    /**
     * A valid, geometry-free GLB document -- `scenes: [{}]`, and no `meshes`/`buffers`/
     * `accessors`/`bufferViews` keys at all (an empty array for any of those, e.g. `meshes: []`,
     * is itself a validator error -- `EMPTY_ENTITY` -- so the keys are omitted entirely, not set
     * to empty arrays). This is the GLB expression of `kstep render`'s Container-Regel: a script
     * with no geometry (or one whose geometry could not be rendered this run) still gets a
     * well-formed `.glb` file, exit 0, exactly as it gets a text-card SVG/PNG/.txt today -- see
     * `dev.kstep.cli.RenderCommand`.
     */
    fun writeEmptyScene(extras: GlbExtras = GlbExtras.EMPTY): GlbWriteResult =
        buildEmptyScene(extras, droppedTriangleCount = 0)

    private fun buildEmptyScene(
        extras: GlbExtras,
        droppedTriangleCount: Int,
    ): GlbWriteResult {
        val document =
            buildJsonObject {
                put("asset", assetJson(extrasJson(extras)))
                putJsonArray("scenes") { addJsonObject { } }
            }
        val jsonChunk = padTo(document.toString().toByteArray(Charsets.UTF_8), ALIGNMENT, JSON_CHUNK_PAD_BYTE)
        return GlbWriteResult(
            bytes = assembleContainer(jsonChunk, binChunk = null),
            triangleCount = 0,
            vertexCount = 0,
            droppedTriangleCount = droppedTriangleCount,
        )
    }

    private fun assetJson(extras: JsonObject?): JsonObject =
        buildJsonObject {
            put("version", "2.0")
            put("generator", GENERATOR)
            if (extras != null) put("extras", extras)
        }

    /** Builds `asset.extras`, or `null` if [extras] has nothing to say -- an empty/absent
     *  `extras` key is preferable to an empty `{"kstep":{}}` shell for the (very common) case of
     *  a plain default-constructed [GlbExtras]. See [GlbExtras]'s own KDoc for why this goes
     *  through `kotlinx-serialization-json`, not [dev.kstep.render.svg.SvgEscaping]. */
    private fun extrasJson(extras: GlbExtras): JsonObject? {
        if (extras.scriptName == null && extras.summaryLines.isEmpty()) return null
        val kstep =
            buildJsonObject {
                extras.scriptName?.let { put("scriptName", it) }
                if (extras.summaryLines.isNotEmpty()) {
                    val capped =
                        extras.summaryLines
                            .take(RenderLimits.MAX_CARD_LINES)
                            .map { line -> line.take(RenderLimits.MAX_CARD_LINE_CHARS) }
                    putJsonArray("summary") { capped.forEach { add(it) } }
                }
            }
        return buildJsonObject { put("kstep", kstep) }
    }

    /** One triangle that survived [collectTriangles]'s finite/non-degenerate filter, still in
     *  full `Double` precision -- narrowed to `Float` only at the point each vertex is written
     *  into the output buffer, in [write]. */
    private class KeptTriangle(
        /** 9 doubles: `x0,y0,z0, x1,y1,z1, x2,y2,z2`, copied verbatim from the source
         *  [TriangleMesh]. */
        val coordinates: DoubleArray,
        /** The triangle's unit-length, outward-facing normal (3 doubles), shared by all three
         *  of its corners -- flat shading needs no per-vertex smoothing. */
        val normal: DoubleArray,
    )

    /** Filters [mesh]'s triangle soup down to the triangles this writer can actually emit --
     *  see [write]'s KDoc for what "degenerate or non-finite" means and why a dropped triangle is
     *  not an error. */
    private fun collectTriangles(mesh: TriangleMesh): List<KeptTriangle> {
        val coordinates = mesh.coordinates
        val kept = ArrayList<KeptTriangle>(mesh.triangleCount)
        var offset = 0
        while (offset < coordinates.size) {
            val v0x = coordinates[offset]
            val v0y = coordinates[offset + 1]
            val v0z = coordinates[offset + 2]
            val v1x = coordinates[offset + 3]
            val v1y = coordinates[offset + 4]
            val v1z = coordinates[offset + 5]
            val v2x = coordinates[offset + 6]
            val v2y = coordinates[offset + 7]
            val v2z = coordinates[offset + 8]
            offset += 9

            val triangleCoordinates = doubleArrayOf(v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z)
            if (triangleCoordinates.any { !it.isFinite() || abs(it) > FLOAT_MAX_ABS }) continue

            val ux = v1x - v0x
            val uy = v1y - v0y
            val uz = v1z - v0z
            val wx = v2x - v0x
            val wy = v2y - v0y
            val wz = v2z - v0z
            val nx = uy * wz - uz * wy
            val ny = uz * wx - ux * wz
            val nz = ux * wy - uy * wx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (!length.isFinite() || length <= DEGENERATE_NORMAL_LENGTH_EPSILON) continue

            kept += KeptTriangle(triangleCoordinates, doubleArrayOf(nx / length, ny / length, nz / length))
        }
        return kept
    }

    /** Per-axis min/max over the ALREADY float32-narrowed [positions] -- see [write]'s KDoc and
     *  docs/adr/ADR-0016-gltf-glb-export.adoc's Stolperfalle 1 for why this must not be computed
     *  from the source `Double`s instead. Returned as `Double`s (matching the JSON number type
     *  `put` writes), each an EXACT `Float.toDouble()` widening, not a re-rounded value. */
    private fun float32Bounds(positions: FloatArray): Pair<DoubleArray, DoubleArray> {
        val min = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)
        val max = doubleArrayOf(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
        var offset = 0
        while (offset < positions.size) {
            for (axis in 0 until COMPONENTS_PER_VEC3) {
                val value = positions[offset + axis].toDouble()
                if (value < min[axis]) min[axis] = value
                if (value > max[axis]) max[axis] = value
            }
            offset += COMPONENTS_PER_VEC3
        }
        return min to max
    }

    private fun floatsToLittleEndianBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * BYTES_PER_FLOAT).order(ByteOrder.LITTLE_ENDIAN)
        for (value in values) buffer.putFloat(value)
        return buffer.array()
    }

    /** Pads [data] up to a multiple of [alignment] with [padByte] -- glTF's binary layout
     *  requires EVERY chunk length to be a multiple of 4, and requires the JSON chunk's padding
     *  byte to be `0x20` (space) and the BIN chunk's to be `0x00` (see [write]'s KDoc,
     *  Stolperfalle 6 in the ADR: swapping these two breaks a parser that reads the JSON chunk as
     *  a plain string). */
    private fun padTo(
        data: ByteArray,
        alignment: Int,
        padByte: Byte,
    ): ByteArray {
        val remainder = data.size % alignment
        if (remainder == 0) return data
        return data + ByteArray(alignment - remainder) { padByte }
    }

    /** Assembles the GLB container: a 12-byte header, the JSON chunk (header + already-padded
     *  bytes), and -- if [binChunk] is non-null -- the BIN chunk (header + already-padded bytes).
     *  [binChunk] is `null` only for [writeEmptyScene]/the degenerate-mesh path of [write]: a
     *  document with no `buffers` entry has no buffer data to attach at all, so the BIN chunk is
     *  omitted entirely rather than written as zero-length (glTF's binary layout treats the BIN
     *  chunk as optional for exactly this reason). */
    private fun assembleContainer(
        jsonChunk: ByteArray,
        binChunk: ByteArray?,
    ): ByteArray {
        val totalLength =
            GLB_HEADER_LENGTH + CHUNK_HEADER_LENGTH + jsonChunk.size +
                if (binChunk != null) CHUNK_HEADER_LENGTH + binChunk.size else 0
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(GLB_MAGIC)
        buffer.putInt(GLB_VERSION)
        buffer.putInt(totalLength)
        buffer.putInt(jsonChunk.size)
        buffer.putInt(CHUNK_TYPE_JSON)
        buffer.put(jsonChunk)
        if (binChunk != null) {
            buffer.putInt(binChunk.size)
            buffer.putInt(CHUNK_TYPE_BIN)
            buffer.put(binChunk)
        }
        return buffer.array()
    }
}
