package dev.kstep.render.text

import dev.kstep.render.RenderLimits
import dev.kstep.render.parseXmlSecurely
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class TextCardRendererTest :
    StringSpec({
        "toSvg produces a well-formed document containing every line" {
            val lines = listOf("kSTEP preview -- hello.kstep.kts", "", "Model", "  roots 2")
            val svg = TextCardRenderer.toSvg(lines, 600, 400)
            svg shouldContain "<svg"
            svg shouldContain "</svg>"
            svg shouldContain "kSTEP preview -- hello.kstep.kts"
            svg shouldContain "roots 2"
            parseXmlSecurely(svg)
        }

        "toSvg escapes special characters in a line" {
            val svg = TextCardRenderer.toSvg(listOf("<script>alert(1)</script>"), 400, 200)
            svg.contains("<script>alert") shouldBe false
            svg shouldContain "&lt;script&gt;"
            parseXmlSecurely(svg)
        }

        "lines beyond MAX_CARD_LINES are truncated with an omitted-count marker" {
            val lines = (1..(RenderLimits.MAX_CARD_LINES + 10)).map { "line $it" }
            val svg = TextCardRenderer.toSvg(lines, 400, 2000)
            val lineCount = svg.split("<text").size - 1
            // MAX_CARD_LINES real lines + 1 summary line.
            lineCount shouldBe (RenderLimits.MAX_CARD_LINES + 1)
            svg shouldContain "more lines omitted"
        }

        "lines beyond canvas capacity are truncated even under MAX_CARD_LINES, no line drawn past canvas height" {
            // Default CLI preview height (768px) only fits ~42 lines at TOP_MARGIN_PX/LINE_HEIGHT_PX
            // spacing -- well under MAX_CARD_LINES (60). Every <text y="..."> must stay <= height,
            // including the omitted-count marker itself, or content silently renders off-canvas.
            val lines = (1..80).map { "line $it" }
            val svg = TextCardRenderer.toSvg(lines, 1024, 768)
            val yValues =
                Regex("""<text x="\d+" y="(\d+)"""").findAll(svg).map { it.groupValues[1].toInt() }.toList()
            yValues.isEmpty() shouldBe false
            yValues.all { it <= 768 } shouldBe true
            svg shouldContain "more lines omitted"
        }

        "a line longer than MAX_CARD_LINE_CHARS is truncated with a trailing marker" {
            val longLine = "x".repeat(RenderLimits.MAX_CARD_LINE_CHARS + 50)
            val svg = TextCardRenderer.toSvg(listOf(longLine), 400, 200)
            svg.contains("x".repeat(RenderLimits.MAX_CARD_LINE_CHARS + 50)) shouldBe false
            svg shouldContain "..."
        }

        "toImage renders an image of the requested dimensions, not entirely blank" {
            val image = TextCardRenderer.toImage(listOf("hello preview card"), 300, 150)
            image.width shouldBe 300
            image.height shouldBe 150
            var nonWhite = 0
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    if (image.getRGB(x, y) != -1) nonWhite++
                }
            }
            (nonWhite > 0) shouldBe true
        }

        "a canvas shorter than TOP_MARGIN_PX still draws visible content, not a blank image" {
            // RenderLimits.MIN_DIMENSION_PX (16) is below TextCardRenderer's internal
            // TOP_MARGIN_PX (20) -- regression coverage for the off-canvas truncation-marker bug
            // (canvasCapacity computed as 1 instead of 0 for a negative `height - TOP_MARGIN_PX`
            // dividend, and the single line drawn at the fixed y=TOP_MARGIN_PX regardless).
            for (height in 16..19) {
                val svg = TextCardRenderer.toSvg(listOf("line one", "line two", "line three"), 200, height)
                val yValues =
                    Regex("""<text x="\d+" y="(\d+)"""").findAll(svg).map { it.groupValues[1].toInt() }.toList()
                yValues.isEmpty() shouldBe false
                yValues.all { it in 0 until height } shouldBe true
                svg shouldContain "line one"

                val image = TextCardRenderer.toImage(listOf("line one", "line two", "line three"), 200, height)
                var nonWhite = 0
                for (y in 0 until image.height) {
                    for (x in 0 until image.width) {
                        if (image.getRGB(x, y) != -1) nonWhite++
                    }
                }
                (nonWhite > 0) shouldBe true
            }
        }

        "an empty line list still renders a valid, blank-of-text SVG" {
            val svg = TextCardRenderer.toSvg(emptyList(), 200, 100)
            svg shouldContain "<svg"
            svg.contains("<text") shouldBe false
            parseXmlSecurely(svg)
        }
    })
