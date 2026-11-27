package dev.kstep.preview

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val PRELUDE =
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
    """

private fun lineOf(
    text: String,
    marker: String,
): Int = text.lines().indexOfFirst { marker in it } + 1

private const val ASSEMBLY_BODY =
    """
    val top = pd("TOP"); val sub = pd("SUB"); val x = pd("X"); val y = pd("Y")
    val s1 = usage("S1", top, sub); val s2 = usage("S2", top, sub); val uy = usage("UY", top, y); val ux = usage("UX", sub, x)
    stepFile(fileName = "lines.step") {
        roots(s1, s2, uy, ux)
        shape(x, OcctKernel.makeBox(10.0, 10.0, 10.0)) // SHAPE-X
        shape(y, OcctKernel.makeBox(20.0, 10.0, 10.0)) // SHAPE-Y
        place(s2, translation = vec(100.0, 0.0, 0.0)) // PLACE-S2
        place(uy, translation = vec(0.0, 50.0, 0.0)) // PLACE-UY
    }
    """

private const val SINGLE_BODY =
    """
    val solo = pd("SOLO")
    stepFile(fileName = "solo.step") {
        root(solo)
        shape(solo, OcctKernel.makeBox(10.0, 10.0, 10.0)) // SHAPE-SOLO
    }
    """

/** Script lines of the rendered parts (ADR-0091). Named after OCCT on purpose: CI's skip guard covers it. */
class PreviewSourceLinesOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun render(script: String) =
            PreviewRenderer
                .render(
                    PreviewRequest(PreviewSource.InlineScript(script, "lines.kstep.kts"), format = RenderFormat.GLB),
                ).shouldBeInstanceOf<PreviewOutcome.Rendered>()

        "an assembly instance reports its own place line, else the shape line of its part".config(enabled = available) {
            val script = PRELUDE + ASSEMBLY_BODY
            val parts = render(script).parts!!
            parts.map { it.name } shouldBe listOf("X", "X", "Y")
            // both X instances sit under usage UX, which has no place(...): the shape line of X; Y has place(uy)
            parts.map { it.sourceLine } shouldBe
                listOf(lineOf(script, "SHAPE-X"), lineOf(script, "SHAPE-X"), lineOf(script, "PLACE-UY"))
        }

        "a single shape reports its shape line".config(enabled = available) {
            val script = PRELUDE + SINGLE_BODY
            render(script).parts!!.single().sourceLine shouldBe lineOf(script, "SHAPE-SOLO")
        }
    })
