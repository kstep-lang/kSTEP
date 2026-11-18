package dev.kstep.cli

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.Placement
import dev.kstep.preview.RenderedPart
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The part list the desktop worker adds to the render result (ADR-0053). */
class PutPartsTest :
    StringSpec({
        fun part(
            name: String,
            first: Int = 0,
            count: Int = 1,
            color: MeshColor = MeshColor.NEUTRAL,
        ) = RenderedPart(name, first, count, Placement.IDENTITY, color)

        fun render(parts: List<RenderedPart>): JsonObject = buildJsonObject { putParts(parts) }

        "a part is written with range, a 12-number placement and an RGB hex colour" {
            val json = render(listOf(part("Bolt", 4, 8, MeshColor(1.0, 0.5, 0.0, 0.3))))
            val p = json["parts"]!!.jsonArray[0].jsonObject
            p["name"]!!.jsonPrimitive.content shouldBe "Bolt"
            p["firstTriangle"]!!.jsonPrimitive.int shouldBe 4
            p["triangleCount"]!!.jsonPrimitive.int shouldBe 8
            p["placement"]!!.jsonArray.size shouldBe 12
            p["color"]!!.jsonPrimitive.content shouldBe "#FF8000"
            json.containsKey("partsOmitted") shouldBe false
        }

        "a long name is cut to the limit without splitting a surrogate pair" {
            val limit = DesktopPartLimits.MAX_NAME_CHARS
            val name = "a".repeat(limit - 1) + "😀" // the pair would straddle the limit
            val written =
                render(listOf(part(name)))["parts"]!!
                    .jsonArray[0]
                    .jsonObject["name"]!!
                    .jsonPrimitive.content
            written.length shouldBe limit - 1
            written.last() shouldBe 'a'
            val plain = render(listOf(part("b".repeat(1000))))["parts"]!!.jsonArray[0].jsonObject
            plain["name"]!!.jsonPrimitive.content.length shouldBe limit
        }

        "more parts than the limit are replaced by partsOmitted" {
            val json = render(List(DesktopPartLimits.MAX_PARTS + 1) { part("p$it", it, 1) })
            json["parts"] shouldBe JsonNull
            val omitted = json["partsOmitted"]!!.jsonObject
            omitted["reason"]!!.jsonPrimitive.content shouldBe "too_many_parts"
            omitted["count"]!!.jsonPrimitive.int shouldBe DesktopPartLimits.MAX_PARTS + 1
        }

        "exactly the limit is still written" {
            val json = render(List(DesktopPartLimits.MAX_PARTS) { part("p$it", it, 1) })
            (json["parts"] as JsonArray).size shouldBe DesktopPartLimits.MAX_PARTS
        }
    })
