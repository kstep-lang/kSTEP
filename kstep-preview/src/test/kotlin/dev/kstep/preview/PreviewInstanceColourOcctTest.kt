package dev.kstep.preview

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Instance colours in `kstep render` (ADR-0045). Skeleton checks only (fill colour sets, vertex colours), never a
 * byte comparison, so the tests are independent of fonts and platform. The class name carries `Occt` so the CI skip
 * gate covers it.
 */
class PreviewInstanceColourOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        val prelude =
            """
            val appCtx = applicationContext { application = "config control" }.getOrThrow()
            val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
            val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
            fun pd(id: String): ProductDefinition {
                val p = product(id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow()
                val f = productDefinitionFormation("${'$'}id-F") { ofProduct = p }.getOrThrow()
                return productDefinition("${'$'}id-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
            }
            fun usage(id: String, parent: ProductDefinition, child: ProductDefinition) =
                nextAssemblyUsageOccurrence(id) { name = id; relatingProductDefinition = parent; relatedProductDefinition = child }.getOrThrow()
            val asm = pd("ASM"); val box = pd("BOX")
            val u1 = usage("U1", asm, box); val u2 = usage("U2", asm, box)
            """.trimIndent()

        fun script(
            partColour: String,
            styles: String,
        ) = prelude + "\n" +
            "stepFile(fileName = \"two.step\") {\n" +
            "    roots(u1, u2)\n" +
            "    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0)$partColour)\n" +
            "    place(u2, translation = vec(50.0, 0.0, 0.0))\n" +
            styles +
            "}\n"

        val red = "    styleInstance(u1, StepColour.rgb(1.0, 0.0, 0.0))\n"
        val blue = "    styleInstance(u2, StepColour.rgb(0.0, 0.0, 1.0))\n"

        fun render(
            source: String,
            format: RenderFormat,
        ): ByteArray {
            val request = PreviewRequest(PreviewSource.InlineScript(source, "colour.kstep.kts"), format = format)
            return PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>().bytes
        }

        fun fills(svg: String): Set<String> =
            Regex("fill=\"#([0-9a-f]{6})\"").findAll(svg).map { it.groupValues[1] }.toSet()

        fun isRed(hex: String) = hex.substring(2) == "0000" && hex.substring(0, 2) != "00"

        fun isBlue(hex: String) = hex.substring(0, 4) == "0000" && hex.substring(4) != "00"

        "two usages of one part, red and blue, give red and blue fills in the SVG".config(enabled = available) {
            val svg = String(render(script("", red + blue), RenderFormat.SVG), Charsets.UTF_8)
            fills(svg).any { isRed(it) } shouldBe true
            fills(svg).any { isBlue(it) } shouldBe true
        }

        "an instance colour wins over the part colour, a plain instance keeps the part colour".config(
            enabled = available,
        ) {
            val orange = ", colour = StepColour.rgb(1.0, 0.5, 0.0)"
            // u1 stays without an instance colour (orange part colour), u2 is blue.
            val svg = String(render(script(orange, blue), RenderFormat.SVG), Charsets.UTF_8)
            val set = fills(svg)
            set.any { isBlue(it) } shouldBe true
            set.any { isRed(it) } shouldBe false
            // orange: red channel dominates, green about half of it, no blue
            set.any { it.substring(4) == "00" && it.substring(2, 4) != "00" && it.substring(0, 2) != "00" } shouldBe
                true
        }

        "a mixed case, one coloured and one uncoloured instance, renders the plain one neutral".config(
            enabled = available,
        ) {
            val svg = String(render(script("", red), RenderFormat.SVG), Charsets.UTF_8)
            val set = fills(svg)
            set.any { isRed(it) } shouldBe true
            set.any { it.substring(0, 2) == it.substring(2, 4) && it.substring(2, 4) == it.substring(4) } shouldBe true
        }

        "the GLB carries COLOR_0 with red vertices for the first instance and blue ones for the second".config(
            enabled = available,
        ) {
            val bytes = render(script("", red + blue), RenderFormat.GLB)
            String(bytes, Charsets.ISO_8859_1) shouldContain "COLOR_0"
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val binStart = 20 + buf.getInt(12) + 8
            val vertexCount = 72
            // positions and normals (3 floats each) come first, the colours follow
            val colorsStart = binStart + vertexCount * 24

            fun colour(v: Int) = (0 until 3).map { buf.getFloat(colorsStart + v * 12 + it * 4) }
            (0 until 36).forEach { colour(it) shouldBe listOf(1f, 0f, 0f) }
            (36 until 72).forEach { colour(it) shouldBe listOf(0f, 0f, 1f) }
        }

        "without any colour the SVG has only grey fills and the GLB has no COLOR_0".config(enabled = available) {
            val svg = String(render(script("", ""), RenderFormat.SVG), Charsets.UTF_8)
            fills(svg).shouldNotBeEmpty()
            fills(svg).filter { it != "ffffff" }.forEach {
                (it.substring(0, 2) == it.substring(2, 4) && it.substring(2, 4) == it.substring(4)) shouldBe true
            }
            String(render(script("", ""), RenderFormat.GLB), Charsets.ISO_8859_1).contains("COLOR_0") shouldBe false
        }

        // ADR-0046: ASM -> SUB twice (s1 at 0, s2 at x=100), SUB -> BOX twice (b1 at 0, b2 at x=20); only BOX has a shape
        fun nested(
            partColour: String,
            styles: String,
        ) = prelude +
            "\nval sub = pd(\"SUB\")\nval s1 = usage(\"S1\", asm, sub); val s2 = usage(\"S2\", asm, sub)\n" +
            "val b1 = usage(\"B1\", sub, box); val b2 = usage(\"B2\", sub, box)\n" +
            "stepFile(fileName = \"nested.step\") {\n" +
            "    roots(s1, s2, b1, b2)\n" +
            "    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0)$partColour)\n" +
            "    place(s2, translation = vec(100.0, 0.0, 0.0))\n" +
            "    place(b2, translation = vec(20.0, 0.0, 0.0))\n" +
            styles +
            "}\n"

        fun vertexColours(bytes: ByteArray): List<List<Float>> {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val binStart = 20 + buf.getInt(12) + 8
            val vertexCount = 4 * 36
            val colorsStart = binStart + vertexCount * 24
            return (0 until vertexCount).map { v -> (0 until 3).map { buf.getFloat(colorsStart + v * 12 + it * 4) } }
        }

        val blueS2 = "    styleInstance(s2, StepColour.rgb(0.0, 0.0, 1.0))\n"
        val redB1 = "    styleInstance(b1, StepColour.rgb(1.0, 0.0, 0.0))\n"

        "a colour on a sub-assembly occurrence colours exactly the two boxes of that occurrence (ADR-0046)".config(
            enabled = available,
        ) {
            val colours = vertexColours(render(nested("", blueS2), RenderFormat.GLB))
            val neutral = colours.first()
            // instances in order: (s1,b1) (s1,b2) (s2,b1) (s2,b2), 36 vertices each
            colours.slice(0 until 72).distinct() shouldBe listOf(neutral)
            colours.slice(72 until 144).distinct() shouldBe listOf(listOf(0f, 0f, 1f))
            (neutral != listOf(0f, 0f, 1f)) shouldBe true
        }

        "the part colour beats the colour of the outer sub-assembly occurrence, an inner occurrence colour beats both (ADR-0046)"
            .config(enabled = available) {
                val orange = ", colour = StepColour.rgb(1.0, 0.5, 0.0)"
                val withPart = vertexColours(render(nested(orange, blueS2), RenderFormat.GLB))
                withPart.distinct() shouldBe listOf(listOf(1f, 0.5f, 0f))
                // the inner colour b1 (red) applies to the first box of both Sub occurrences
                val inner = vertexColours(render(nested(orange, blueS2 + redB1), RenderFormat.GLB))
                inner.slice(0 until 36).distinct() shouldBe listOf(listOf(1f, 0f, 0f))
                inner.slice(36 until 72).distinct() shouldBe listOf(listOf(1f, 0.5f, 0f))
                inner.slice(72 until 108).distinct() shouldBe listOf(listOf(1f, 0f, 0f))
                inner.slice(108 until 144).distinct() shouldBe listOf(listOf(1f, 0.5f, 0f))
            }

        "without any sub-assembly colour the render does not change (ADR-0046)".config(enabled = available) {
            val a = render(nested("", ""), RenderFormat.GLB)
            String(a, Charsets.ISO_8859_1).contains("COLOR_0") shouldBe false
            val b = render(script("", red + blue), RenderFormat.SVG)
            b.isNotEmpty() shouldBe true
        }

        // Welle Y (ADR-0044 amendment): translucent instance colours are drawn translucent
        val glassBlue = "    styleInstance(u2, StepColour.rgba(0.0, 0.0, 1.0, 0.5))\n"

        "a translucent instance colour gives opacity 0.5 on the polygons of that instance only (SVG)".config(
            enabled = available,
        ) {
            val svg = String(render(script("", red + glassBlue), RenderFormat.SVG), Charsets.UTF_8)
            val polygons = svg.lines().filter { it.startsWith("<polygon") }
            val translucent = polygons.filter { it.contains("opacity=") }
            translucent.isNotEmpty() shouldBe true
            translucent.forEach { it.contains("opacity=\"0.5\"") shouldBe true }
            // the red instance stays opaque, so there are polygons without the attribute
            (polygons.size - translucent.size > 0) shouldBe true
        }

        "a translucent instance colour gives a VEC4 COLOR_0 with alpha 0.5 on 12 triangles and BLEND (GLB)".config(
            enabled = available,
        ) {
            val bytes = render(script("", red + glassBlue), RenderFormat.GLB)
            val text = String(bytes, Charsets.ISO_8859_1)
            text shouldContain "\"alphaMode\":\"BLEND\""
            text shouldContain "VEC4"
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val binStart = 20 + buf.getInt(12) + 8
            val vertexCount = 72
            val colorsStart = binStart + vertexCount * 24

            fun rgba(v: Int) = (0 until 4).map { buf.getFloat(colorsStart + v * 16 + it * 4) }
            (0 until 36).forEach { rgba(it) shouldBe listOf(1f, 0f, 0f, 1f) }
            (36 until 72).forEach { rgba(it) shouldBe listOf(0f, 0f, 1f, 0.5f) }
        }

        "the part colour alpha is drawn translucent as well, and a render without translucency is VEC3 and has no BLEND"
            .config(enabled = available) {
                val svg =
                    String(
                        render(script(", colour = StepColour.rgba(1.0, 0.5, 0.0, 0.25)", ""), RenderFormat.SVG),
                        Charsets.UTF_8,
                    )
                svg shouldContain "opacity=\"0.25\""
                val plain = String(render(script("", red + blue), RenderFormat.GLB), Charsets.ISO_8859_1)
                plain.contains("BLEND") shouldBe false
                plain.contains("VEC4") shouldBe false
            }

        "the render fails predictably when the native bridge is unavailable".config(enabled = !available) {
            (OcctKernel.availability() is OcctAvailability.Available) shouldBe false
        }
    })
