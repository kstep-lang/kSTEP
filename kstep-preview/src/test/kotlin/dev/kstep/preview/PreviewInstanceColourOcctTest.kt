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

        "the render fails predictably when the native bridge is unavailable".config(enabled = !available) {
            (OcctKernel.availability() is OcctAvailability.Available) shouldBe false
        }
    })
