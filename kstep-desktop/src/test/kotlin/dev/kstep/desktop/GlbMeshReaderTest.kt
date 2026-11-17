package dev.kstep.desktop

import dev.kstep.desktop.model.GlbMeshReader
import dev.kstep.desktop.model.GlbReadException
import dev.kstep.desktop.model.TooManyTrianglesException
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.gltf.GlbWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.seconds

class GlbMeshReaderTest :
    StringSpec({
        // Two single-triangle parts with distinct colours; the normals are the face normals (+Z and +X).
        fun part(
            offset: Double,
            normal: DoubleArray,
        ): TriangleMesh {
            val c =
                if (normal[2] != 0.0) {
                    doubleArrayOf(offset, 0.0, 0.0, offset + 1.0, 0.0, 0.0, offset, 1.0, 0.0)
                } else {
                    doubleArrayOf(offset, 0.0, 0.0, offset, 1.0, 0.0, offset, 0.0, 1.0)
                }
            return TriangleMesh(c, DoubleArray(9) { normal[it % 3] })
        }

        val red = MeshColor(0.9, 0.1, 0.1)
        val green = MeshColor(0.1, 0.8, 0.2)
        val twoParts =
            MeshComposition.mergeColored(
                listOf(
                    PlacedMesh(part(0.0, doubleArrayOf(0.0, 0.0, 1.0)), color = red),
                    PlacedMesh(part(5.0, doubleArrayOf(1.0, 0.0, 0.0)), color = green),
                ),
            )
        val valid = GlbWriter.write(twoParts).bytes

        fun jsonOf(glb: ByteArray): String {
            val len = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            return String(glb, 20, len, Charsets.UTF_8).trimEnd()
        }

        fun binOf(glb: ByteArray): ByteArray {
            val jsonLen = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
            return glb.copyOfRange(20 + jsonLen + 8, glb.size)
        }

        /** Builds a GLB from a JSON text and a BIN payload, with the writer's chunk padding. */
        fun build(
            json: String,
            bin: ByteArray?,
        ): ByteArray {
            val jb = json.toByteArray(Charsets.UTF_8)
            val jp = jb + ByteArray((4 - jb.size % 4) % 4) { 0x20 }
            val bp = bin?.let { it + ByteArray((4 - it.size % 4) % 4) }
            val total = 12 + 8 + jp.size + (bp?.let { 8 + it.size } ?: 0)
            val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            out.putInt(0x46546C67).putInt(2).putInt(total)
            out.putInt(jp.size).putInt(0x4E4F534A).put(jp)
            if (bp != null) out.putInt(bp.size).putInt(0x004E4942).put(bp)
            return out.array()
        }

        fun mutate(change: (String) -> String): ByteArray = build(change(jsonOf(valid)), binOf(valid))

        fun rejects(
            bytes: ByteArray,
            max: Int = 130_000,
        ) {
            shouldThrow<GlbReadException> { GlbMeshReader.read(bytes, max) }
        }

        "the writer's coloured GLB round-trips with positions, normals and colours" {
            val mesh = GlbMeshReader.read(valid)!!
            mesh.mesh.triangleCount shouldBe 2
            twoParts.mesh.coordinates.forEachIndexed { i, v -> mesh.mesh.coordinates[i] shouldBe (v plusOrMinus 1e-6) }
            twoParts.mesh.vertexNormals!!.forEachIndexed { i, v ->
                mesh.mesh.vertexNormals!![i] shouldBe
                    (v plusOrMinus 1e-6)
            }
            mesh.colorAt(0).r shouldBe (red.r plusOrMinus 1e-6)
            mesh.colorAt(1).g shouldBe (green.g plusOrMinus 1e-6)
            (mesh.colorAt(0) != mesh.colorAt(1)) shouldBe true
        }

        "alpha survives (VEC4)" {
            val translucent =
                MeshComposition.mergeColored(
                    listOf(
                        PlacedMesh(part(0.0, doubleArrayOf(0.0, 0.0, 1.0)), color = red),
                        PlacedMesh(part(5.0, doubleArrayOf(1.0, 0.0, 0.0)), color = MeshColor(0.1, 0.8, 0.2, 0.5)),
                    ),
                )
            val mesh = GlbMeshReader.read(GlbWriter.write(translucent).bytes)!!
            mesh.colorAt(0).a shouldBe (1.0 plusOrMinus 1e-6)
            mesh.colorAt(1).a shouldBe (0.5 plusOrMinus 1e-6)
        }

        "a GLB without colours reads as the neutral colour" {
            val mesh = GlbMeshReader.read(GlbWriter.write(part(0.0, doubleArrayOf(0.0, 0.0, 1.0))).bytes)!!
            mesh.mesh.triangleCount shouldBe 1
            mesh.colorAt(0) shouldBe MeshColor.NEUTRAL
        }

        "the empty scene reads as null" {
            GlbMeshReader.read(GlbWriter.writeEmptyScene().bytes) shouldBe null
        }

        "bad magic, bad version, wrong length field" {
            rejects(valid.copyOf().also { it[0] = 0 })
            rejects(valid.copyOf().also { it[4] = 1 })
            rejects(
                valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, valid.size + 4) },
            )
            rejects(
                valid.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(8, valid.size - 4) },
            )
        }

        "every truncation fails and never hangs".config(timeout = 60.seconds) {
            var cut = 0
            while (cut < valid.size) {
                rejects(valid.copyOf(cut))
                cut += 7
            }
        }

        "a chunk that is not 4-byte aligned is refused" {
            rejects(
                valid.copyOf().also { b ->
                    ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).let {
                        it.putInt(
                            12,
                            it.getInt(12) - 1,
                        )
                    }
                },
            )
        }

        "a buffer view outside the buffer, and a length that does not fit the count" {
            rejects(
                mutate { it.replace(Regex(""""buffers":\[\{"byteLength":\d+"""), """"buffers":[{"byteLength":4""") },
            )
            rejects(
                mutate { json ->
                    val m = Regex(""""byteLength":(\d+)""").find(json)!!
                    json.replaceRange(m.range, """"byteLength":${m.groupValues[1].toInt() - 12}""")
                },
            )
        }

        "a vertex count that is not divisible by 3" {
            rejects(
                mutate {
                    it.replace(""""count":6""", """"count":5""")
                },
            )
        }

        "more triangles than the limit are refused before anything is read" {
            val e =
                shouldThrow<TooManyTrianglesException> {
                    GlbMeshReader.read(mutate { it.replace(""""count":6""", """"count":9999999""") }, 100)
                }
            e.limit shouldBe 100
            shouldThrow<TooManyTrianglesException> { GlbMeshReader.read(valid, 1) }
        }

        "NaN and infinite positions, a colour of 1.5" {
            val bin = binOf(valid)
            val nan = bin.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(0, Float.NaN) }
            rejects(build(jsonOf(valid), nan))
            val inf =
                bin.copyOf().also {
                    ByteBuffer
                        .wrap(
                            it,
                        ).order(ByteOrder.LITTLE_ENDIAN)
                        .putFloat(4, Float.POSITIVE_INFINITY)
                }
            rejects(build(jsonOf(valid), inf))
            val colorStart = 6 * 12 + 6 * 12
            val bright =
                bin.copyOf().also { b ->
                    ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).let {
                        for (v in 0 until
                            3) {
                            it.putFloat(colorStart + v * 12, 1.5f)
                        }
                    }
                }
            rejects(build(jsonOf(valid), bright))
        }

        "the corners of one triangle must share a colour" {
            val bin = binOf(valid)
            val colorStart = 6 * 12 + 6 * 12
            val mixed =
                bin.copyOf().also {
                    ByteBuffer
                        .wrap(
                            it,
                        ).order(ByteOrder.LITTLE_ENDIAN)
                        .putFloat(colorStart + 12, 0.3f)
                }
            rejects(build(jsonOf(valid), mixed))
        }

        "indices, two primitives, a foreign rotation, extra node transforms" {
            rejects(mutate { it.replace(""""mode":4""", """"mode":4,"indices":3""") })
            rejects(mutate { it.replace(""""material":0}]""", """"material":0},{"mode":4,"attributes":{}}]""") })
            rejects(mutate { it.replace(Regex(""""rotation":\[[^\]]*]"""), """"rotation":[0,0,0,1]""") })
            rejects(mutate { it.replace(""""mesh":0""", """"mesh":0,"scale":[1000,1000,1000]""") })
        }

        "a JSON chunk over 4 MiB is refused" {
            val big = " ".repeat(4 * 1024 * 1024 + 4)
            rejects(build("""{"asset":{"version":"2.0"},"pad":"$big"}""", null))
        }

        "deeply nested JSON is refused without recursion" {
            rejects(build("[".repeat(5000) + "]".repeat(5000), null))
            rejects(build("""{"a":${"[".repeat(100)}${"]".repeat(100)}}""", null))
        }

        "a second BIN chunk or data after the chunks is refused" {
            rejects(valid + ByteArray(8))
            val extra = valid + ByteArray(8)
            ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN).putInt(8, extra.size)
            rejects(extra)
        }

        "a read error is a GlbReadException with a message" {
            shouldThrow<GlbReadException> { GlbMeshReader.read(ByteArray(3)) }.message.shouldBeInstanceOf<String>()
        }

        "a byteOffset that would overflow the range check is rejected" {
            val bytes =
                mutate {
                    val m = Regex(""""byteOffset":(\d+)""").findAll(it).last()
                    it.replaceRange(m.range, """"byteOffset":9223372036854775804""")
                }
            shouldThrow<GlbReadException> { GlbMeshReader.read(bytes) }.message shouldContain "exceeds the buffer"
        }
    })
