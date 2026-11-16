package dev.kstep.render

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.gltf.GlbWriter
import dev.kstep.render.image.TriangleRasterizer
import dev.kstep.render.mesh.ProjectedTriangle
import dev.kstep.render.svg.TriangleSvgWriter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Translucent colours in the three render outputs (Welle Y, ADR-0044 amendment); skeleton checks, no font involved. */
class TranslucentRenderTest :
    StringSpec({
        fun tri(
            color: MeshColor,
            x: Double = 0.0,
        ) = ProjectedTriangle(x, 0.0, x + 40.0, 0.0, x, 40.0, 1.0, 1.0, color)

        "SVG: only translucent triangles get an opacity attribute, opaque polygons are byte-identical" {
            val opaque = TriangleSvgWriter.render(listOf(tri(MeshColor(0.0, 0.0, 1.0))), 100, 100)
            opaque shouldBe TriangleSvgWriter.render(listOf(tri(MeshColor(0.0, 0.0, 1.0, 1.0))), 100, 100)
            opaque.contains("opacity") shouldBe false
            val svg =
                TriangleSvgWriter.render(
                    listOf(tri(MeshColor(0.0, 0.0, 1.0)), tri(MeshColor(0.0, 0.0, 1.0, 0.5), 50.0)),
                    100,
                    100,
                )
            svg.split("<polygon").drop(1).map { it.contains("opacity=\"0.5\"") } shouldBe listOf(false, true)
            TriangleSvgWriter.render(listOf(tri(MeshColor(0.0, 0.0, 1.0, 0.0))), 100, 100) shouldContain "opacity=\"0\""
        }

        "PNG: a translucent triangle is lighter than the opaque one, an opaque scene is unchanged" {
            val opaque = TriangleRasterizer.render(listOf(tri(MeshColor(0.0, 0.0, 1.0))), 100, 100)
            val translucent = TriangleRasterizer.render(listOf(tri(MeshColor(0.0, 0.0, 1.0, 0.5))), 100, 100)
            val o = opaque.getRGB(10, 10) and 0xFF
            val t = translucent.getRGB(10, 10)
            // opaque: blue channel 255 (shade 1), red 0; translucent: blended with white, red about 128
            (((t shr 16) and 0xFF) in 100..160) shouldBe true
            ((opaque.getRGB(10, 10) shr 16) and 0xFF) shouldBe 0
            o shouldBe 255
            // background stays white
            (translucent.getRGB(90, 90) and 0xFFFFFF) shouldBe 0xFFFFFF
        }

        "PNG: two adjacent translucent triangles do not blend twice along their shared edge" {
            // a 40 x 40 square split along its diagonal, blue at alpha 0.5 over white
            val a = ProjectedTriangle(0.0, 0.0, 40.0, 0.0, 0.0, 40.0, 1.0, 1.0, MeshColor(0.0, 0.0, 1.0, 0.5))
            val b = ProjectedTriangle(40.0, 0.0, 40.0, 40.0, 0.0, 40.0, 1.0, 1.0, MeshColor(0.0, 0.0, 1.0, 0.5))
            val image = TriangleRasterizer.render(listOf(a, b), 60, 60)
            val inner = (image.getRGB(10, 10) shr 16) and 0xFF
            // every pixel along the diagonal x + y = 40 (and its neighbours) equals the interior value
            for (d in -1..1) {
                for (x in 5..35) {
                    ((image.getRGB(x, 40 - x + d) shr 16) and 0xFF) shouldBe inner
                }
            }
        }

        "SVG: translucent polygons carry no stroke, so shared edges are not composited twice" {
            val svg =
                TriangleSvgWriter.render(
                    listOf(tri(MeshColor(0.0, 0.0, 1.0, 0.5)), tri(MeshColor(0.0, 0.0, 1.0), 50.0)),
                    100,
                    100,
                )
            val polygons = svg.split("<polygon").drop(1)
            polygons[0].contains("stroke") shouldBe false
            polygons[1].contains("stroke") shouldBe true
        }

        "GLB: COLOR_0 is VEC3 without translucency and VEC4 with alphaMode BLEND with it" {
            val mesh =
                TriangleMesh(
                    doubleArrayOf(
                        0.0,
                        0.0,
                        0.0,
                        1.0,
                        0.0,
                        0.0,
                        0.0,
                        1.0,
                        0.0,
                        0.0,
                        0.0,
                        1.0,
                        1.0,
                        0.0,
                        1.0,
                        0.0,
                        1.0,
                        1.0,
                    ),
                )

            fun json(bytes: ByteArray) =
                Json
                    .parseToJsonElement(
                        String(
                            bytes,
                            20,
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(12),
                            Charsets.UTF_8,
                        ).trimEnd(),
                    ).jsonObject

            val opaque =
                GlbWriter.write(
                    MeshComposition.mergeColored(listOf(PlacedMesh(mesh, color = MeshColor(0.0, 0.0, 1.0)))),
                )
            val o = json(opaque.bytes)
            o["accessors"]!!
                .jsonArray[2]
                .jsonObject["type"]!!
                .jsonPrimitive.content shouldBe "VEC3"
            o["materials"]!!.jsonArray[0].jsonObject.containsKey("alphaMode") shouldBe false

            val translucent =
                GlbWriter.write(
                    MeshComposition.mergeColored(listOf(PlacedMesh(mesh, color = MeshColor(0.0, 0.0, 1.0, 0.5)))),
                )
            val j = json(translucent.bytes)
            j["accessors"]!!
                .jsonArray[2]
                .jsonObject["type"]!!
                .jsonPrimitive.content shouldBe "VEC4"
            j["materials"]!!
                .jsonArray[0]
                .jsonObject["alphaMode"]!!
                .jsonPrimitive.content shouldBe "BLEND"
            val buf = ByteBuffer.wrap(translucent.bytes).order(ByteOrder.LITTLE_ENDIAN)
            val binStart = 20 + buf.getInt(12) + 8
            val vertexCount = translucent.vertexCount
            val colorsStart = binStart + vertexCount * 24
            for (v in 0 until vertexCount) {
                (0 until 4).map { buf.getFloat(colorsStart + v * 16 + it * 4) } shouldBe listOf(0f, 0f, 1f, 0.5f)
            }
            // the byteLength of the colour buffer view grew to four floats per vertex
            j["bufferViews"]!!
                .jsonArray[2]
                .jsonObject["byteLength"]!!
                .jsonPrimitive.content
                .toInt() shouldBe
                vertexCount * 16
            (translucent.bytes.size % 4) shouldBe 0
        }

        "GLB: a mix of opaque and translucent triangles writes alpha 1 for the opaque ones" {
            val m = TriangleMesh(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0))
            val colored =
                MeshComposition.mergeColored(
                    listOf(
                        PlacedMesh(m, color = MeshColor(1.0, 0.0, 0.0)),
                        PlacedMesh(m, color = MeshColor(0.0, 0.0, 1.0, 0.25)),
                    ),
                )
            val res = GlbWriter.write(colored)
            val buf = ByteBuffer.wrap(res.bytes).order(ByteOrder.LITTLE_ENDIAN)
            val colorsStart = 20 + buf.getInt(12) + 8 + res.vertexCount * 24

            fun alpha(v: Int) = buf.getFloat(colorsStart + v * 16 + 12)
            (0 until 3).map { alpha(it) } shouldBe listOf(1f, 1f, 1f)
            (3 until 6).map { alpha(it) } shouldBe listOf(0.25f, 0.25f, 0.25f)
        }
    })
