package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.part
import dev.kstep.desktop.pick.TestScenes.result
import dev.kstep.desktop.run.PartListParser
import dev.kstep.desktop.run.PartListResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** ADR-0091: `sourceLine` is optional, strictly an integer in 1..10_000_000, and a bad value fails the whole list. */
class PartListSourceLineTest :
    StringSpec({
        val mesh = TestScenes.mesh(TestScenes.triangle(0.0, 0.0, 0.0) + TestScenes.triangle(1.0, 0.0, 0.0))

        fun withLine(
            line: JsonElement?,
            name: String = "Alpha",
        ): JsonObject {
            val base = part(JsonPrimitive(name), JsonPrimitive(0), JsonPrimitive(2), color = JsonPrimitive("#0A0000"))
            return if (line == null) {
                base
            } else {
                buildJsonObject {
                    base.forEach { (k, v) -> put(k, v) }
                    put("sourceLine", line)
                }
            }
        }

        fun parse(line: JsonElement?) = PartListParser.parse(result(withLine(line)), mesh)

        "the field round-trips" {
            val table = parse(JsonPrimitive(17)).shouldBeInstanceOf<PartListResult.Valid>().table
            table.parts[0].sourceLine shouldBe 17
            val edge = parse(JsonPrimitive(10_000_000)).shouldBeInstanceOf<PartListResult.Valid>().table
            edge.parts[0].sourceLine shouldBe 10_000_000
        }

        "the old format without the field is valid and has no line" {
            val table = parse(null).shouldBeInstanceOf<PartListResult.Valid>().table
            table.parts[0].sourceLine shouldBe null
        }

        "invalid values make the whole list invalid" {
            val bad =
                listOf<JsonElement>(
                    JsonPrimitive(0),
                    JsonPrimitive(-1),
                    JsonPrimitive(10_000_001),
                    JsonPrimitive(1.5),
                    JsonPrimitive("3"),
                    JsonPrimitive(true),
                    JsonNull,
                    JsonPrimitive(1e3),
                    JsonPrimitive(2147483648L),
                )
            for (value in bad) parse(value).shouldBeInstanceOf<PartListResult.Invalid>()
        }

        "countOfName counts parts of one name" {
            val m = TestScenes.mesh(TestScenes.triangle(0.0, 0.0, 0.0) + TestScenes.triangle(1.0, 0.0, 0.0))
            val json =
                result(
                    part(JsonPrimitive("A"), JsonPrimitive(0), JsonPrimitive(1), color = JsonPrimitive("#0A0000")),
                    part(JsonPrimitive("A"), JsonPrimitive(1), JsonPrimitive(1), color = JsonPrimitive("#0A0000")),
                )
            val table = PartListParser.parse(json, m).shouldBeInstanceOf<PartListResult.Valid>().table
            table.countOfName("A") shouldBe 2
            table.countOfName("B") shouldBe 0
        }
    })
