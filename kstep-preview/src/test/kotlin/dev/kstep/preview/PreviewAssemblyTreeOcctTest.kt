package dev.kstep.preview

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val TREE_PRELUDE =
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

// TOP uses SUB twice (second one moved) and Y once; SUB holds X.
private val REPEATED_SUB =
    TREE_PRELUDE +
        """
        val top = pd("TOP"); val sub = pd("SUB"); val x = pd("X"); val y = pd("Y")
        val s1 = usage("S1", top, sub); val s2 = usage("S2", top, sub); val uy = usage("UY", top, y); val ux = usage("UX", sub, x)
        stepFile(fileName = "tree.step") {
            roots(s1, s2, uy, ux)
            shape(x, OcctKernel.makeBox(10.0, 10.0, 10.0))
            shape(y, OcctKernel.makeBox(20.0, 10.0, 10.0))
            place(s2, translation = vec(100.0, 0.0, 0.0))
            place(uy, translation = vec(0.0, 50.0, 0.0))
        }
        """

private val ONE_BOX =
    TREE_PRELUDE +
        """
        val solo = pd("SOLO")
        stepFile(fileName = "solo.step") {
            root(solo)
            shape(solo, OcctKernel.makeBox(10.0, 10.0, 10.0))
        }
        """

/**
 * The assembly hierarchy of a real render (ADR-0054). Named after OCCT on purpose: CI's skip guard
 * (`TEST-*Occt*.xml`) then covers it.
 */
class PreviewAssemblyTreeOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun render(
            script: String,
            format: RenderFormat = RenderFormat.GLB,
        ) = PreviewRenderer
            .render(PreviewRequest(PreviewSource.InlineScript(script, "tree.kstep.kts"), format = format))
            .shouldBeInstanceOf<PreviewOutcome.Rendered>()

        "a repeated sub-assembly gives two groups whose leaves point at the right parts".config(enabled = available) {
            val outcome = render(REPEATED_SUB)
            val tree = outcome.tree!!
            tree.nodes.map { "${it.name}:${it.parent}:${it.part}" } shouldBe
                listOf("TOP:-1:-1", "SUB:0:-1", "X:1:0", "SUB:0:-1", "X:3:1", "Y:0:2")
            val parts = outcome.parts!!
            parts.map { it.name } shouldBe listOf("X", "X", "Y")
            // leaves point at the part of their own name, parts are numbered in pre-order
            tree.nodes.filter { it.part >= 0 }.forEach { parts[it.part].name shouldBe it.name }
            tree.nodes.filter { it.part >= 0 }.map { it.part } shouldBe listOf(0, 1, 2)
        }

        "a single shape has parts but no tree".config(enabled = available) {
            val outcome = render(ONE_BOX)
            outcome.parts!!.size shouldBe 1
            outcome.tree.shouldBeNull()
        }

        "only a GLB carries parts and a tree".config(enabled = available) {
            for (format in listOf(RenderFormat.SVG, RenderFormat.PNG)) {
                val outcome = render(REPEATED_SUB, format)
                outcome.parts.shouldBeNull()
                outcome.tree.shouldBeNull()
            }
        }
    })
