package dev.kstep.desktop.model

import dev.kstep.geometry.ColoredMesh
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.RenderLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** The GLB is malformed or not in the exact subset `GlbWriter` produces (ADR-0051). */
open class GlbReadException(
    message: String,
) : RuntimeException(message)

/** The GLB claims more triangles than allowed (checked before anything is allocated). */
class TooManyTrianglesException(
    val triangles: Long,
    val limit: Int,
) : GlbReadException("GLB has $triangles triangles, more than the limit of $limit")

/**
 * Reads the GLB that `kstep render --format glb` writes into a [ColoredMesh] -- and nothing else (ADR-0051, F1).
 *
 * The GLB comes from a subprocess that evaluated an untrusted script, so this reader is a parser for untrusted input and
 * fails closed: it accepts exactly the subset `GlbWriter` writes (one node with the fixed Z-up rotation, one mesh with one
 * non-indexed triangle-list primitive, float `POSITION`/`NORMAL` and optional float `COLOR_0` as `VEC3`/`VEC4`, one
 * colour per triangle), checks every length with `Long` arithmetic before using it and refuses with a
 * [GlbReadException] on anything else. The buffer positions are kSTEP's own Z-up coordinates, so nothing is converted.
 */
object GlbMeshReader {
    private const val MAGIC = 0x46546C67
    private const val VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val MAX_JSON_BYTES = 4 * 1024 * 1024
    private const val MAX_JSON_DEPTH = 32
    private const val FLOAT_COMPONENT = 5126
    private const val ROTATION_TOLERANCE = 1e-6
    private val HALF_ROOT_TWO = sqrt(0.5)
    private val WRITER_ROTATION = doubleArrayOf(-HALF_ROOT_TWO, 0.0, 0.0, HALF_ROOT_TWO)

    /** Returns the mesh, or `null` for a GLB without geometry (the writer's empty scene). */
    fun read(
        bytes: ByteArray,
        maxTriangles: Int = RenderLimits.EXPORT_MAX_TRIANGLES,
    ): ColoredMesh? {
        try {
            return readChecked(bytes, maxTriangles)
        } catch (e: GlbReadException) {
            throw e
        } catch (e: IndexOutOfBoundsException) {
            throw GlbReadException("GLB is truncated or inconsistent")
        } catch (e: RuntimeException) {
            throw GlbReadException("GLB cannot be read (${e::class.simpleName})")
        }
    }

    private fun fail(message: String): Nothing = throw GlbReadException(message)

    private fun readChecked(
        bytes: ByteArray,
        maxTriangles: Int,
    ): ColoredMesh? {
        if (bytes.size < HEADER_BYTES) fail("GLB is shorter than its header")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != MAGIC) fail("not a GLB (bad magic)")
        if (buffer.getInt(4) != VERSION) fail("unsupported GLB version")
        if (u32(buffer, 8) != bytes.size.toLong()) fail("GLB length field does not match the data")

        val jsonChunk = chunk(buffer, bytes.size, HEADER_BYTES.toLong()) ?: fail("GLB has no JSON chunk")
        if (jsonChunk.type != CHUNK_JSON) fail("first GLB chunk is not JSON")
        if (jsonChunk.length > MAX_JSON_BYTES) fail("GLB JSON chunk is larger than $MAX_JSON_BYTES bytes")
        var next = jsonChunk.end
        var bin: Chunk? = null
        if (next < bytes.size) {
            val c = chunk(buffer, bytes.size, next) ?: fail("truncated GLB chunk")
            if (c.type != CHUNK_BIN) fail("second GLB chunk is not BIN")
            bin = c
            next = c.end
        }
        if (next != bytes.size.toLong()) fail("unexpected data after the last GLB chunk")

        val jsonStart = jsonChunk.dataStart.toInt()
        checkDepth(bytes, jsonStart, jsonStart + jsonChunk.length.toInt())
        val root =
            try {
                Json.parseToJsonElement(
                    String(bytes, jsonStart, jsonChunk.length.toInt(), Charsets.UTF_8),
                ) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: fail("GLB JSON is not an object")

        val meshes = root["meshes"] as? JsonArray
        if (meshes == null || meshes.isEmpty()) return null
        if (meshes.size != 1) fail("GLB has more than one mesh")
        checkNodes(root)

        val primitives = (meshes[0] as? JsonObject)?.get("primitives") as? JsonArray ?: fail("mesh has no primitives")
        if (primitives.size != 1) fail("mesh has not exactly one primitive")
        val primitive = primitives[0] as? JsonObject ?: fail("primitive is not an object")
        val mode = primitive["mode"]
        if (mode != null && (mode as? JsonPrimitive)?.intOrNull != 4) fail("primitive is not a triangle list")
        if ("indices" in primitive) fail("indexed primitives are not supported")
        val attributes = primitive["attributes"] as? JsonObject ?: fail("primitive has no attributes")
        if (!setOf("POSITION", "NORMAL", "COLOR_0").containsAll(attributes.keys)) fail("unsupported vertex attribute")
        val binBytes = bin ?: fail("GLB has no BIN chunk")
        val accessors = root["accessors"] as? JsonArray ?: fail("GLB has no accessors")
        val views = root["bufferViews"] as? JsonArray ?: fail("GLB has no bufferViews")
        val bufferLength =
            (
                ((root["buffers"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get(
                    "byteLength",
                ) as? JsonPrimitive
            )?.longOrNull()
                ?: fail("GLB has no buffer length")
        if (bufferLength < 0 || bufferLength > binBytes.length) fail("buffer is longer than the BIN chunk")

        val reader = AccessorReader(buffer, binBytes.dataStart, bufferLength, accessors, views, maxTriangles)
        val position = reader.read(attributes.index("POSITION"), setOf("VEC3"), 3)
        val normal = reader.read(attributes.index("NORMAL"), setOf("VEC3"), 3)
        val colour =
            attributes["COLOR_0"]?.let {
                reader.read(
                    attributes.index("COLOR_0"),
                    setOf("VEC3", "VEC4"),
                    null,
                )
            }

        val vertexCount = position.count
        if (normal.count != vertexCount ||
            (colour != null && colour.count != vertexCount)
        ) {
            fail("accessor counts differ")
        }
        if (vertexCount % 3L != 0L) fail("vertex count is not a multiple of 3")
        val triangles = vertexCount / 3
        if (triangles > maxTriangles) throw TooManyTrianglesException(triangles, maxTriangles)
        if (triangles == 0L) fail("GLB has an empty accessor")
        return assemble(position, normal, colour, triangles.toInt())
    }

    private fun assemble(
        position: FloatData,
        normal: FloatData,
        colour: FloatData?,
        triangles: Int,
    ): ColoredMesh {
        val parts = ArrayList<PlacedMesh>()
        var runStart = 0
        var runColour = colourOf(colour, 0)
        for (t in 1..triangles) {
            val c = if (t < triangles) colourOf(colour, t) else null
            if (t == triangles || c != runColour) {
                parts += run(position, normal, runStart, t, runColour)
                runStart = t
                if (c != null) runColour = c
            }
        }
        return MeshComposition.mergeColored(parts)
    }

    private fun run(
        position: FloatData,
        normal: FloatData,
        from: Int,
        to: Int,
        colour: MeshColor,
    ): PlacedMesh {
        val n = (to - from) * 9
        val coordinates = DoubleArray(n) { position.values[from * 9 + it].toDouble() }
        val normals = DoubleArray(n) { normal.values[from * 9 + it].toDouble() }
        return PlacedMesh(TriangleMesh(coordinates, normals), Placement.IDENTITY, colour)
    }

    /** The colour of triangle [t]; all three corners must carry the same one. */
    private fun colourOf(
        colour: FloatData?,
        t: Int,
    ): MeshColor {
        if (colour == null) return MeshColor.NEUTRAL
        val comps = colour.components
        val base = t * 3 * comps
        for (k in 0 until comps) {
            if (colour.values[base + k] !in 0f..1f) fail("colour component is outside 0..1")
        }
        for (corner in 1..2) {
            for (k in 0 until comps) {
                if (colour.values[base + k] !=
                    colour.values[base + corner * comps + k]
                ) {
                    fail("triangle corners differ in colour")
                }
            }
        }
        val a = if (comps == 4) colour.values[base + 3].toDouble() else 1.0
        return MeshColor(
            colour.values[base].toDouble(),
            colour.values[base + 1].toDouble(),
            colour.values[base + 2].toDouble(),
            a,
        )
    }

    private fun checkNodes(root: JsonObject) {
        val nodes = root["nodes"] ?: return
        val array = nodes as? JsonArray ?: fail("nodes is not an array")
        if (array.size != 1) fail("GLB has not exactly one node")
        val node = array[0] as? JsonObject ?: fail("node is not an object")
        if (!setOf("mesh", "rotation").containsAll(node.keys)) fail("node has an unsupported transform")
        val rotation = node["rotation"] as? JsonArray ?: return
        if (rotation.size != 4) fail("node rotation is not a quaternion")
        for (i in 0 until 4) {
            val v = (rotation[i] as? JsonPrimitive)?.doubleOrNull ?: fail("node rotation is not numeric")
            if (abs(v - WRITER_ROTATION[i]) >
                ROTATION_TOLERANCE
            ) {
                fail("node rotation is not the writer's Z-up rotation")
            }
        }
    }

    private fun JsonObject.index(key: String): Int =
        (this[key] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: fail("attribute $key has no accessor")

    private fun JsonPrimitive.longOrNull(): Long? = content.toLongOrNull()

    private class Chunk(
        val type: Int,
        val length: Long,
        val dataStart: Long,
    ) {
        val end: Long get() = dataStart + length
    }

    private fun u32(
        buffer: ByteBuffer,
        at: Int,
    ): Long = buffer.getInt(at).toLong() and 0xFFFFFFFFL

    private fun chunk(
        buffer: ByteBuffer,
        size: Int,
        at: Long,
    ): Chunk? {
        if (at + CHUNK_HEADER_BYTES > size) return null
        val length = u32(buffer, at.toInt())
        val type = buffer.getInt(at.toInt() + 4)
        if (length % 4 != 0L) fail("GLB chunk is not 4-byte aligned")
        val dataStart = at + CHUNK_HEADER_BYTES
        if (dataStart + length > size) fail("GLB chunk exceeds the file")
        return Chunk(type, length, dataStart)
    }

    /** Refuses a JSON text nested deeper than [MAX_JSON_DEPTH] before it is handed to a recursive parser. */
    private fun checkDepth(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ) {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in from until to) {
            val b = bytes[i].toInt()
            if (inString) {
                when {
                    escaped -> escaped = false
                    b == '\\'.code -> escaped = true
                    b == '"'.code -> inString = false
                }
            } else {
                when (b) {
                    '"'.code -> inString = true
                    '['.code, '{'.code -> if (++depth > MAX_JSON_DEPTH) fail("GLB JSON is nested too deeply")
                    ']'.code, '}'.code -> depth--
                }
            }
        }
    }

    private class FloatData(
        val values: FloatArray,
        val count: Long,
        val components: Int,
    )

    private class AccessorReader(
        private val buffer: ByteBuffer,
        private val binStart: Long,
        private val bufferLength: Long,
        private val accessors: JsonArray,
        private val views: JsonArray,
        private val maxTriangles: Int,
    ) {
        fun read(
            index: Int,
            types: Set<String>,
            fixedComponents: Int?,
        ): FloatData {
            val accessor = accessors.getOrNull(index) as? JsonObject ?: fail("accessor $index does not exist")
            if ((accessor["componentType"] as? JsonPrimitive)?.intOrNull !=
                FLOAT_COMPONENT
            ) {
                fail("accessor is not float")
            }
            if ((accessor["normalized"] as? JsonPrimitive)?.booleanOrNull ==
                true
            ) {
                fail("normalized accessors are not supported")
            }
            if ("sparse" in accessor) fail("sparse accessors are not supported")
            val accessorOffset = (accessor["byteOffset"] as? JsonPrimitive)?.intOrNull ?: 0
            if (accessorOffset != 0) fail("accessor byteOffset is not supported")
            val type = (accessor["type"] as? JsonPrimitive)?.content
            if (type !in types) fail("accessor has an unsupported type")
            val components = if (type == "VEC4") 4 else 3
            if (fixedComponents != null &&
                components != fixedComponents
            ) {
                fail("accessor has an unexpected component count")
            }
            val count = (accessor["count"] as? JsonPrimitive)?.content?.toLongOrNull() ?: fail("accessor has no count")
            if (count < 0) fail("accessor count is negative")
            // The triangle cap comes before any range check or allocation, so a hostile count costs nothing.
            if (count > maxTriangles.toLong() * 3) throw TooManyTrianglesException(count / 3, maxTriangles)
            val viewIndex = (accessor["bufferView"] as? JsonPrimitive)?.intOrNull ?: fail("accessor has no bufferView")
            val view = views.getOrNull(viewIndex) as? JsonObject ?: fail("bufferView $viewIndex does not exist")
            if (((view["buffer"] as? JsonPrimitive)?.intOrNull ?: -1) != 0) fail("bufferView does not use buffer 0")
            val elementBytes = components * 4L
            val stride = (view["byteStride"] as? JsonPrimitive)?.content?.toLongOrNull()
            if (stride != null && stride != elementBytes) fail("interleaved buffer views are not supported")
            val offset = (view["byteOffset"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val length =
                (view["byteLength"] as? JsonPrimitive)?.content?.toLongOrNull() ?: fail("bufferView has no byteLength")
            if (offset < 0 || length < 0 || offset % 4 != 0L) fail("bufferView range is invalid")
            if (offset > bufferLength || length > bufferLength - offset) fail("bufferView exceeds the buffer")
            if (length != count * elementBytes) fail("bufferView length does not match the accessor")
            // count*components floats fit an Int: the triangle cap is checked by the caller, but a hostile count could
            // still be huge here -- it cannot exceed the bytes that really exist (length <= bufferLength <= file size).
            val floatCount = (count * components).toInt()
            val values = FloatArray(floatCount)
            val start = (binStart + offset).toInt()
            for (i in 0 until floatCount) {
                val v = buffer.getFloat(start + i * 4)
                if (!v.isFinite()) fail("accessor contains a non-finite value")
                values[i] = v
            }
            return FloatData(values, count, components)
        }
    }
}
