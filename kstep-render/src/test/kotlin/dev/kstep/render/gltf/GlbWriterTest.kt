package dev.kstep.render.gltf

import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.TriangleMesh
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** One triangle: (0,0,0), (1,0,0), (0,1,0) -- CCW winding around +Z, matching the reference
 *  prototype used to design [GlbWriter] (see docs/adr/ADR-0016-gltf-glb-export.adoc, section 0). */
private fun singleTriangleMesh(): TriangleMesh =
    TriangleMesh(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0))

/** A parsed GLB container: the raw header fields plus the JSON document and (if present) the
 *  BIN chunk bytes, decoded WITHOUT going through [GlbWriter] itself -- this is the independent
 *  reader half of the round trip these tests check. */
private class ParsedGlb(
    val magic: Int,
    val version: Int,
    val totalLength: Int,
    val jsonChunkLength: Int,
    val jsonChunkType: Int,
    val json: kotlinx.serialization.json.JsonObject,
    val jsonPaddingBytes: ByteArray,
    val binChunkLength: Int?,
    val binChunkType: Int?,
    val binChunk: ByteArray?,
)

private fun parseGlb(bytes: ByteArray): ParsedGlb {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val magic = buffer.int
    val version = buffer.int
    val totalLength = buffer.int
    val jsonChunkLength = buffer.int
    val jsonChunkType = buffer.int
    val jsonBytes = ByteArray(jsonChunkLength)
    buffer.get(jsonBytes)
    // Trailing 0x20 padding bytes: strip from the end to find the real JSON text length, then
    // keep the stripped tail itself for the padding-byte assertion.
    var realLength = jsonBytes.size
    while (realLength > 0 && jsonBytes[realLength - 1] == 0x20.toByte()) realLength--
    val jsonText = String(jsonBytes, 0, realLength, Charsets.UTF_8)
    val paddingBytes = jsonBytes.copyOfRange(realLength, jsonBytes.size)
    val json = Json.parseToJsonElement(jsonText).jsonObject

    var binChunkLength: Int? = null
    var binChunkType: Int? = null
    var binChunk: ByteArray? = null
    if (buffer.hasRemaining()) {
        binChunkLength = buffer.int
        binChunkType = buffer.int
        val bin = ByteArray(binChunkLength)
        buffer.get(bin)
        binChunk = bin
    }
    return ParsedGlb(
        magic = magic,
        version = version,
        totalLength = totalLength,
        jsonChunkLength = jsonChunkLength,
        jsonChunkType = jsonChunkType,
        json = json,
        jsonPaddingBytes = paddingBytes,
        binChunkLength = binChunkLength,
        binChunkType = binChunkType,
        binChunk = binChunk,
    )
}

private fun readFloatsLe(bytes: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(bytes.size / 4)
    for (i in out.indices) out[i] = buffer.float
    return out
}

class GlbWriterTest :
    StringSpec({
        // A1
        "the GLB header and chunk headers are well-formed for a one-triangle mesh" {
            val result = GlbWriter.write(singleTriangleMesh())
            val parsed = parseGlb(result.bytes)

            parsed.magic shouldBe 0x46546C67
            parsed.version shouldBe 2
            parsed.totalLength shouldBe result.bytes.size
            parsed.jsonChunkType shouldBe 0x4E4F534A
            (parsed.jsonChunkLength % 4) shouldBe 0
            parsed.binChunkType shouldBe 0x004E4942
            val binChunkLength = requireNotNull(parsed.binChunkLength)
            (binChunkLength % 4) shouldBe 0
        }

        // A2
        "the JSON chunk parses and is padded with spaces only" {
            val result = GlbWriter.write(singleTriangleMesh())
            val parsed = parseGlb(result.bytes)

            parsed.json["asset"]!!
                .jsonObject["version"]!!
                .jsonPrimitive.content shouldBe "2.0"
            parsed.jsonPaddingBytes.all { it == 0x20.toByte() } shouldBe true
        }

        // A3
        "triangle/vertex/accessor/bufferView counts agree" {
            val mesh =
                TriangleMesh(
                    singleTriangleMesh().coordinates + singleTriangleMesh().coordinates,
                )
            val result = GlbWriter.write(mesh)
            val parsed = parseGlb(result.bytes)

            result.triangleCount shouldBe 2
            result.vertexCount shouldBe 6
            result.droppedTriangleCount shouldBe 0
            val accessors = parsed.json["accessors"]!!.jsonArray
            accessors[0].jsonObject["count"]!!.jsonPrimitive.int shouldBe 6
            accessors[1].jsonObject["count"]!!.jsonPrimitive.int shouldBe 6
            val bufferViews = parsed.json["bufferViews"]!!.jsonArray
            bufferViews[0].jsonObject["byteLength"]!!.jsonPrimitive.int shouldBe 6 * 3 * 4
            bufferViews[1].jsonObject["byteLength"]!!.jsonPrimitive.int shouldBe 6 * 3 * 4
        }

        // A4
        "written positions round-trip exactly through float32 narrowing" {
            val mesh = singleTriangleMesh()
            val result = GlbWriter.write(mesh)
            val parsed = parseGlb(result.bytes)
            val positionBytes = parsed.binChunk!!.copyOfRange(0, 9 * 4)
            val positions = readFloatsLe(positionBytes)

            for (i in mesh.coordinates.indices) {
                positions[i].toDouble() shouldBe mesh.coordinates[i].toFloat().toDouble()
            }
        }

        // A5
        "a triangle on the +X face of a cube produces an outward-pointing unit normal" {
            // v0=(1,0,0) v1=(1,1,0) v2=(1,0,1) -- cross((v1-v0),(v2-v0)) = (1,0,0).
            val mesh = TriangleMesh(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 1.0, 0.0, 1.0))
            val result = GlbWriter.write(mesh)
            val parsed = parseGlb(result.bytes)
            val normalBytes = parsed.binChunk!!.copyOfRange(9 * 4, 18 * 4)
            val normals = readFloatsLe(normalBytes)

            normals[0].toDouble() shouldBe (1.0 plusOrMinus 1e-6)
            normals[1].toDouble() shouldBe (0.0 plusOrMinus 1e-6)
            normals[2].toDouble() shouldBe (0.0 plusOrMinus 1e-6)
            for (corner in 0 until 3) {
                val nx = normals[corner * 3].toDouble()
                val ny = normals[corner * 3 + 1].toDouble()
                val nz = normals[corner * 3 + 2].toDouble()
                sqrt(nx * nx + ny * ny + nz * nz) shouldBe (1.0 plusOrMinus 5e-3)
            }
        }

        // A6
        "a collinear (degenerate) triangle is dropped, not written" {
            // Three collinear points -- zero-area, zero-length cross product.
            val mesh = TriangleMesh(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 2.0, 0.0, 0.0))
            val result = GlbWriter.write(mesh)

            result.triangleCount shouldBe 0
            result.droppedTriangleCount shouldBe 1
            result.bytes.size shouldBe parseGlb(result.bytes).totalLength
        }

        // A7
        "triangles with NaN, infinite, or float32-unrepresentable coordinates are dropped" {
            val good = singleTriangleMesh().coordinates
            val withNaN = good.copyOf().also { it[0] = Double.NaN }
            val withInfinity = good.copyOf().also { it[1] = Double.POSITIVE_INFINITY }
            val tooLarge = good.copyOf().also { it[2] = 1e39 }

            fun oneDropped(mesh: TriangleMesh) {
                val result = GlbWriter.write(mesh)
                result.triangleCount shouldBe 0
                result.droppedTriangleCount shouldBe 1
            }
            oneDropped(TriangleMesh(withNaN))
            oneDropped(TriangleMesh(withInfinity))
            oneDropped(TriangleMesh(tooLarge))
        }

        // A8
        "accessors[0].min/max come from the float32-narrowed values, not the source doubles" {
            // A value that does NOT round-trip through float32 unchanged.
            val odd =
                doubleArrayOf(
                    0.1234567890123,
                    0.2345678901234,
                    0.3456789012345,
                    1.9876543210987,
                    0.0,
                    0.0,
                    0.0,
                    2.8765432109876,
                    0.0,
                )
            val mesh = TriangleMesh(odd)
            val result = GlbWriter.write(mesh)
            val parsed = parseGlb(result.bytes)
            val positionBytes = parsed.binChunk!!.copyOfRange(0, 9 * 4)
            val positions = readFloatsLe(positionBytes)

            fun axisValues(axis: Int) = (0 until 3).map { corner -> positions[corner * 3 + axis].toDouble() }
            val expectedMin = (0 until 3).map { axis -> axisValues(axis).min() }
            val expectedMax = (0 until 3).map { axis -> axisValues(axis).max() }
            val accessor0 = parsed.json["accessors"]!!.jsonArray[0].jsonObject
            val min = accessor0["min"]!!.jsonArray.map { it.jsonPrimitive.double }
            val max = accessor0["max"]!!.jsonArray.map { it.jsonPrimitive.double }
            min shouldBe expectedMin
            max shouldBe expectedMax
            // And explicitly NOT the raw doubles (the whole point of this test): at least one
            // axis differs between the float32-narrowed bound and the double source value.
            (min != listOf(odd[0], odd[1], odd[2]) || max != listOf(odd[3], odd[7], odd[2])) shouldBe true
        }

        // A9
        "an empty mesh writes a valid geometry-free document with no meshes/buffers/accessors/bufferViews" {
            val result = GlbWriter.write(TriangleMesh(DoubleArray(0)))
            val parsed = parseGlb(result.bytes)

            result.triangleCount shouldBe 0
            result.vertexCount shouldBe 0
            result.droppedTriangleCount shouldBe 0
            parsed.binChunkLength shouldBe null
            parsed.json.containsKey("meshes") shouldBe false
            parsed.json.containsKey("buffers") shouldBe false
            parsed.json.containsKey("accessors") shouldBe false
            parsed.json.containsKey("bufferViews") shouldBe false
            parsed.json["scenes"]!!.jsonArray.size shouldBe 1
            parsed.json["scenes"]!!
                .jsonArray[0]
                .jsonObject.keys
                .isEmpty() shouldBe true
        }

        "writeEmptyScene produces the identical shape directly" {
            val result = GlbWriter.writeEmptyScene()
            val parsed = parseGlb(result.bytes)
            parsed.json.containsKey("meshes") shouldBe false
            parsed.binChunkLength shouldBe null
        }

        // A10
        "a mesh above MAX_TRIANGLES is rejected before any allocation" {
            val overLimitTriangleCount = OcctKernel.MAX_TRIANGLES + 1
            val single = singleTriangleMesh().coordinates
            val coordinates = DoubleArray(overLimitTriangleCount * 9)
            for (t in 0 until overLimitTriangleCount) {
                System.arraycopy(single, 0, coordinates, t * 9, 9)
            }
            val tooMany = TriangleMesh(coordinates)
            shouldThrow<IllegalArgumentException> { GlbWriter.write(tooMany) }
        }

        // A11
        "extras summary lines are capped in count and per-line length" {
            val longLines = (1..500).map { "x".repeat(500) }
            val result = GlbWriter.write(singleTriangleMesh(), GlbExtras(summaryLines = longLines))
            val parsed = parseGlb(result.bytes)
            val summary =
                parsed.json["asset"]!!
                    .jsonObject["extras"]!!
                    .jsonObject["kstep"]!!
                    .jsonObject["summary"]!!
                    .jsonArray

            (summary.size <= 60) shouldBe true
            summary.forEach { line -> (line.jsonPrimitive.content.length <= 110) shouldBe true }
        }

        // A12
        "extras text is JSON-escaped, not embedded raw, and unicode survives round trip" {
            val payload = "</text><script>alert(1)</script> ünïcødé"
            val result = GlbWriter.write(singleTriangleMesh(), GlbExtras(scriptName = payload))
            val parsed = parseGlb(result.bytes)

            val kstepExtras =
                parsed.json["asset"]!!
                    .jsonObject["extras"]!!
                    .jsonObject["kstep"]!!
                    .jsonObject
            val roundTripped = kstepExtras["scriptName"]!!.jsonPrimitive.content
            roundTripped shouldBe payload
            // The raw markup/text must round-trip exactly through JSON string escaping -- a
            // proper JSON writer escapes control characters and quote/backslash and never lets
            // the payload break out of its own string literal. Successfully parsing the chunk
            // (parseGlb above) and getting the exact original payload back IS the positive
            // proof: an unescaped or mis-escaped payload would either fail to parse or come
            // back altered.
            result.bytes.isNotEmpty() shouldBe true
        }

        // A13
        "identical input produces byte-identical output (determinism)" {
            val mesh = singleTriangleMesh()
            val extras = GlbExtras(scriptName = "a.kstep.kts", summaryLines = listOf("one", "two"))
            val first = GlbWriter.write(mesh, extras)
            val second = GlbWriter.write(mesh, extras)

            first.bytes.contentEquals(second.bytes) shouldBe true
        }

        // A14
        "the node carries the Z-up-to-Y-up rotation quaternion" {
            val result = GlbWriter.write(singleTriangleMesh())
            val parsed = parseGlb(result.bytes)
            val rotationNode =
                parsed.json["nodes"]!!
                    .jsonArray[0]
                    .jsonObject["rotation"]!!
                    .jsonArray
            val rotation = rotationNode.map { it.jsonPrimitive.double }

            val r = sqrt(0.5)
            rotation[0] shouldBe (-r plusOrMinus 1e-15)
            rotation[1] shouldBe (0.0 plusOrMinus 1e-15)
            rotation[2] shouldBe (0.0 plusOrMinus 1e-15)
            rotation[3] shouldBe (r plusOrMinus 1e-15)

            // A -90 degree rotation about X maps model +Z (0,0,1) to world +Y (0,1,0). Verify by
            // applying the quaternion directly (q * v * q^-1 for a unit quaternion and pure
            // vector v), rather than re-deriving trig, so this stays a true independent check.
            val (qx, qy, qz, qw) = rotation
            val (vx, vy, vz) = listOf(0.0, 0.0, 1.0)
            // t = 2 * cross(q.xyz, v)
            val tx = 2 * (qy * vz - qz * vy)
            val ty = 2 * (qz * vx - qx * vz)
            val tz = 2 * (qx * vy - qy * vx)
            // v' = v + qw*t + cross(q.xyz, t)
            val rx = vx + qw * tx + (qy * tz - qz * ty)
            val ry = vy + qw * ty + (qz * tx - qx * tz)
            val rz = vz + qw * tz + (qx * ty - qy * tx)
            rx shouldBe (0.0 plusOrMinus 1e-9)
            ry shouldBe (1.0 plusOrMinus 1e-9)
            rz shouldBe (0.0 plusOrMinus 1e-9)
        }

        "materials/mode/doubleSided are set as documented" {
            val result = GlbWriter.write(singleTriangleMesh())
            val parsed = parseGlb(result.bytes)
            val primitive =
                parsed.json["meshes"]!!
                    .jsonArray[0]
                    .jsonObject["primitives"]!!
                    .jsonArray[0]
                    .jsonObject
            primitive["mode"]!!.jsonPrimitive.int shouldBe 4
            primitive["attributes"]!!.jsonObject["POSITION"]!!.jsonPrimitive.int shouldBe 0
            primitive["attributes"]!!.jsonObject["NORMAL"]!!.jsonPrimitive.int shouldBe 1
            val material = parsed.json["materials"]!!.jsonArray[0].jsonObject
            material["doubleSided"]!!.jsonPrimitive.boolean shouldBe false
        }
    })
