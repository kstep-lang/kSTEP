package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.part
import dev.kstep.desktop.pick.TestScenes.result
import dev.kstep.desktop.run.PartListParser
import dev.kstep.desktop.run.PartListResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** ADR-0053: the part list comes from a script's output and is validated against the GLB mesh, fail closed. */
class PartListParserTest :
    StringSpec({
        // part 0: 3 triangles (colour 0), part 1: 2 triangles (colour 1) -> 5 triangles
        val coords0 =
            TestScenes.triangle(0.0, 0.0, 0.0) + TestScenes.triangle(1.0, 0.0, 0.0) + TestScenes.triangle(2.0, 0.0, 0.0)
        val coords1 = TestScenes.triangle(50.0, 0.0, 0.0) + TestScenes.triangle(60.0, 0.0, 0.0)
        val mesh = TestScenes.mesh(coords0, coords1)

        fun n(v: Int) = JsonPrimitive(v)

        fun good0() = part(JsonPrimitive("Alpha"), n(0), n(3), color = JsonPrimitive("#0A0000"))

        fun good1() = part(JsonPrimitive("Beta"), n(3), n(2), color = JsonPrimitive("#140000"))

        fun parse(vararg parts: JsonElement) = PartListParser.parse(result(*parts), mesh)

        fun invalid(r: PartListResult) = r.shouldBeInstanceOf<PartListResult.Invalid>()

        "an honest list is valid and carries derived bounds and centroid" {
            val table = parse(good0(), good1()).shouldBeInstanceOf<PartListResult.Valid>().table
            table.parts.map { it.name } shouldBe listOf("Alpha", "Beta")
            table.parts[0].triangleCount shouldBe 3
            table.partOfTriangle(0) shouldBe 0
            table.partOfTriangle(2) shouldBe 0
            table.partOfTriangle(3) shouldBe 1
            table.partOfTriangle(5) shouldBe -1
            table.partOfTriangle(-1) shouldBe -1
            table.indexOfName("Beta") shouldBe 1
            table.indexOfName("none") shouldBe -1
            val a = table.parts[0]
            a.bboxMin.toList() shouldBe listOf(0.0, 0.0, 0.0)
            a.bboxMax.toList() shouldBe listOf(12.0, 10.0, 0.0)
            a.placement.origin.toList() shouldBe listOf(5.0, 6.0, 7.0)
            a.placement.axes.toList() shouldBe listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            a.colorHex shouldBe "#0A0000"
        }

        "the centroid is area weighted" {
            // one big triangle (area 50, centroid (10/3, 10/3)) and one tiny far away (area 0.005)
            val big = TestScenes.triangle(0.0, 0.0, 0.0)
            val tiny = doubleArrayOf(100.0, 100.0, 0.0, 101.0, 100.0, 0.0, 100.0, 100.01, 0.0)
            val m = TestScenes.mesh(big + tiny)
            val json = result(part(JsonPrimitive("X"), n(0), n(2), color = JsonPrimitive("#0A0000")))
            val info =
                PartListParser
                    .parse(json, m)
                    .shouldBeInstanceOf<PartListResult.Valid>()
                    .table.parts[0]
            info.centroid[0].shouldBe((10.0 / 3.0).plusOrMinus(0.1))
            // the plain mean of the two triangle centroids would be about 51.7
        }

        "a missing parts field is absent, not invalid" {
            PartListParser.parse(buildJsonObject { }, mesh) shouldBe PartListResult.Absent
            PartListParser.parse(buildJsonObject { put("parts", JsonNull) }, mesh) shouldBe PartListResult.Absent
            PartListParser.parse(result(), mesh) shouldBe PartListResult.Absent
        }

        "a part list without geometry is invalid, an empty one is absent" {
            invalid(PartListParser.parse(result(good0()), null))
            PartListParser.parse(result(), null) shouldBe PartListResult.Absent
        }

        "parts is not an array" {
            invalid(PartListParser.parse(buildJsonObject { put("parts", JsonPrimitive("x")) }, mesh))
            invalid(PartListParser.parse(buildJsonObject { put("parts", buildJsonObject { }) }, mesh))
        }

        "partsOmitted makes the list invalid so nothing fails silently" {
            val omitted =
                buildJsonObject {
                    put("parts", JsonNull)
                    put("partsOmitted", buildJsonObject { put("reason", JsonPrimitive("too_many_parts")) })
                }
            invalid(PartListParser.parse(omitted, mesh)).reason shouldBe "more than 4096 parts"
        }

        "more than 4096 entries are refused" {
            val many = List(4097) { good0() }
            invalid(PartListParser.parse(result(*many.toTypedArray()), mesh))
        }

        "range tampering is refused" {
            // sum too small, too large, overlap, gap, first != 0, negative, zero count
            invalid(parse(good0()))
            invalid(parse(good0(), part(JsonPrimitive("Beta"), n(3), n(3), color = JsonPrimitive("#140000"))))
            invalid(parse(good0(), part(JsonPrimitive("Beta"), n(2), n(2), color = JsonPrimitive("#140000"))))
            invalid(parse(good0(), part(JsonPrimitive("Beta"), n(4), n(1), color = JsonPrimitive("#140000"))))
            invalid(parse(part(JsonPrimitive("Alpha"), n(1), n(2)), good1()))
            invalid(parse(part(JsonPrimitive("Alpha"), n(-1), n(3)), good1()))
            invalid(parse(good0(), part(JsonPrimitive("Beta"), n(3), n(0), color = JsonPrimitive("#140000"))))
            invalid(parse(good0(), part(JsonPrimitive("Beta"), n(3), n(-2), color = JsonPrimitive("#140000"))))
        }

        "huge numbers do not overflow the range arithmetic" {
            invalid(
                parse(good0(), part(JsonPrimitive("Beta"), JsonPrimitive(Int.MAX_VALUE), JsonPrimitive(Int.MAX_VALUE))),
            )
            invalid(parse(part(JsonPrimitive("A"), n(0), JsonPrimitive(Int.MAX_VALUE))))
        }

        "numbers must be JSON integers, not strings or fractions" {
            invalid(parse(part(JsonPrimitive("Alpha"), JsonPrimitive("0"), n(3)), good1()))
            invalid(parse(part(JsonPrimitive("Alpha"), n(0), JsonPrimitive("3")), good1()))
            invalid(parse(part(JsonPrimitive("Alpha"), n(0), JsonPrimitive(2.5)), good1()))
        }

        "the placement must be 12 finite, bounded numbers" {
            fun withPlacement(p: JsonElement) = part(JsonPrimitive("Alpha"), n(0), n(3), placement = p)
            val twelve = TestScenes.IDENTITY_PLACEMENT.map { JsonPrimitive(it) }
            invalid(parse(withPlacement(JsonArray(twelve.take(11))), good1()))
            invalid(parse(withPlacement(JsonArray(twelve + JsonPrimitive(0.0))), good1()))
            invalid(
                parse(
                    withPlacement(
                        JsonArray(
                            twelve.mapIndexed { i, v ->
                                if (i ==
                                    3
                                ) {
                                    JsonPrimitive(Double.NaN)
                                } else {
                                    v
                                }
                            },
                        ),
                    ),
                    good1(),
                ),
            )
            invalid(
                parse(
                    withPlacement(
                        JsonArray(
                            twelve.mapIndexed { i, v ->
                                if (i ==
                                    3
                                ) {
                                    JsonPrimitive(Double.POSITIVE_INFINITY)
                                } else {
                                    v
                                }
                            },
                        ),
                    ),
                    good1(),
                ),
            )
            invalid(
                parse(
                    withPlacement(
                        JsonArray(
                            twelve.mapIndexed { i, v ->
                                if (i ==
                                    3
                                ) {
                                    JsonPrimitive(1e10)
                                } else {
                                    v
                                }
                            },
                        ),
                    ),
                    good1(),
                ),
            )
            invalid(
                parse(
                    withPlacement(
                        JsonArray(
                            twelve.mapIndexed { i, v ->
                                if (i ==
                                    3
                                ) {
                                    JsonPrimitive("1")
                                } else {
                                    v
                                }
                            },
                        ),
                    ),
                    good1(),
                ),
            )
            invalid(parse(withPlacement(JsonPrimitive("x")), good1()))
        }

        "a deeply nested placement is refused without recursion" {
            var nested: JsonElement = JsonPrimitive(1.0)
            repeat(200) { nested = JsonArray(listOf(nested)) }
            invalid(parse(part(JsonPrimitive("Alpha"), n(0), n(3), placement = nested), good1()))
        }

        "names are validated: empty, too long, wrong type, only control characters" {
            invalid(parse(part(JsonPrimitive(""), n(0), n(3)), good1()))
            invalid(parse(part(JsonPrimitive("n".repeat(257)), n(0), n(3)), good1()))
            invalid(parse(part(JsonPrimitive(5), n(0), n(3)), good1()))
            invalid(parse(part(JsonNull, n(0), n(3)), good1()))
            invalid(parse(part(JsonPrimitive("\u0007\u001B\u0000"), n(0), n(3)), good1()))
        }

        "a name of 256 characters is accepted" {
            parse(part(JsonPrimitive("n".repeat(256)), n(0), n(3)), good1()).shouldBeInstanceOf<PartListResult.Valid>()
        }

        "control, ANSI and bidi characters in a name are replaced, the part stays valid" {
            val raw = "Bo\u0007lt \u001B[31mred\u001B[0m ‮txt⁦x⁩ line\nbreak"
            val table =
                parse(part(JsonPrimitive(raw), n(0), n(3)), good1()).shouldBeInstanceOf<PartListResult.Valid>().table
            val name = table.parts[0].name
            name.none { it.code < 0x20 || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 } shouldBe true
            name.contains("red") shouldBe true
            name.contains("\u001B") shouldBe false
            name.contains('�') shouldBe true
        }

        "a 10 MB name is refused without reading it further" {
            invalid(parse(part(JsonPrimitive("x".repeat(10_000_000)), n(0), n(3)), good1()))
        }

        "colours must look like #RRGGBB" {
            for (bad in listOf("#12345", "red", "#GGGGGG", "0A0000", "#0A00000", "")) {
                invalid(parse(part(JsonPrimitive("Alpha"), n(0), n(3), color = JsonPrimitive(bad)), good1()))
            }
            invalid(parse(part(JsonPrimitive("Alpha"), n(0), n(3), color = JsonPrimitive(10)), good1()))
        }

        "the colour must match the geometry" {
            invalid(parse(part(JsonPrimitive("Alpha"), n(0), n(3), color = JsonPrimitive("#FF0000")), good1()))
            // within one 8 bit step is fine
            parse(part(JsonPrimitive("Alpha"), n(0), n(3), color = JsonPrimitive("#0B0000")), good1())
                .shouldBeInstanceOf<PartListResult.Valid>()
        }

        "an entry that is not an object is refused" {
            invalid(PartListParser.parse(result(good0(), JsonPrimitive(1)), mesh))
            invalid(PartListParser.parse(result(good0(), JsonArray(emptyList())), mesh))
        }

        "unknown fields are ignored" {
            val extra =
                JsonObject(good0() + ("surprise" to JsonPrimitive("x")))
            parse(extra, good1()).shouldBeInstanceOf<PartListResult.Valid>()
        }
    })
