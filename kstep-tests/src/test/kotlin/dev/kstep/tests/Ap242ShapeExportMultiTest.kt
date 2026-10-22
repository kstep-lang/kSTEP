package dev.kstep.tests

import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.shape.Ap242ShapeExporter
import dev.kstep.shape.ShapeAssignment
import dev.kstep.shape.ShapeExportException
import dev.kstep.step21.Part21Header
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val header =
    Part21Header(
        fileName = "multi.step",
        timestamp = "2026-01-01T00:00:00",
        schemaIdentifiers = listOf("placeholder"),
        description = emptyList(),
        author = emptyList(),
        organization = emptyList(),
    )

private val appCtx = applicationContext { application = "config control" }.getOrThrow()
private val prodCtx =
    productContext {
        name = "engineering"
        frameOfReference = appCtx
        disciplineType = "mechanical"
    }.getOrThrow()
private val defCtx =
    productDefinitionContext {
        name = "engineering"
        frameOfReference = appCtx
        lifeCycleStage = "design"
    }.getOrThrow()

private fun pd(id: String): ProductDefinition {
    val p =
        product(id) {
            name = id
            frameOfReference = setOf(prodCtx)
        }.getOrThrow()
    val f = productDefinitionFormation("$id-F") { ofProduct = p }.getOrThrow()
    return productDefinition("$id-D") {
        formation = f
        frameOfReference = defCtx
    }.getOrThrow()
}

private fun count(
    text: String,
    token: String,
): Int = Regex(Regex.escape(token)).findAll(text).count()

private val occtAvailable get() = OcctKernel.availability() is OcctAvailability.Available

class Ap242ShapeExportMultiTest :
    StringSpec({
        "the single-assignment export equals the multi variant with one root and one assignment".config(
            enabled = occtAvailable,
        ) {
            val def = pd("M-1")
            OcctKernel.makeBox(2.0, 3.0, 4.0).use { box ->
                val a = ShapeAssignment(def, box)
                Ap242ShapeExporter.export(header, a) shouldBe Ap242ShapeExporter.export(header, listOf(def), listOf(a))
            }
        }

        "two shapes on an assembly are merged with one application protocol definition".config(
            enabled = occtAvailable,
        ) {
            val housing = pd("M-H")
            val bracket = pd("M-B")
            val nauo =
                nextAssemblyUsageOccurrence("M-N") {
                    name = "n"
                    relatingProductDefinition = housing
                    relatedProductDefinition = bracket
                    referenceDesignator = "r"
                }.getOrThrow()
            OcctKernel.makeBox(5.0, 5.0, 5.0).use { b1 ->
                OcctKernel.makeBox(1.0, 2.0, 3.0).use { b2 ->
                    val text =
                        Ap242ShapeExporter.export(
                            header,
                            listOf(nauo),
                            listOf(ShapeAssignment(housing, b1), ShapeAssignment(bracket, b2)),
                        )
                    count(text, "NEXT_ASSEMBLY_USAGE_OCCURRENCE(") shouldBe 1
                    count(text, "SHAPE_DEFINITION_REPRESENTATION(") shouldBe 2
                    count(text, "APPLICATION_PROTOCOL_DEFINITION(") shouldBe 1
                    count(text, "ADVANCED_FACE(") shouldBe 12
                }
            }
        }

        "two assignments to the same product definition are rejected".config(enabled = occtAvailable) {
            val def = pd("M-D")
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { b1 ->
                OcctKernel.makeBox(2.0, 2.0, 2.0).use { b2 ->
                    shouldThrow<ShapeExportException> {
                        Ap242ShapeExporter.export(
                            header,
                            listOf(def),
                            listOf(ShapeAssignment(def, b1), ShapeAssignment(def, b2)),
                        )
                    }.message shouldContain "more than one shape"
                }
            }
        }

        "a product definition not reachable from the roots is rejected".config(enabled = occtAvailable) {
            val root = pd("M-R")
            val stray = pd("M-S")
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { box ->
                shouldThrow<ShapeExportException> {
                    Ap242ShapeExporter.export(header, listOf(root), listOf(ShapeAssignment(stray, box)))
                }.message shouldContain "not reachable"
            }
        }

        "an empty assignment list is rejected" {
            shouldThrow<ShapeExportException> {
                Ap242ShapeExporter.export(header, listOf(pd("M-E")), emptyList())
            }
        }

        "a closed shape raises IllegalStateException".config(enabled = occtAvailable) {
            val def = pd("M-C")
            val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
            box.close()
            shouldThrow<IllegalStateException> {
                Ap242ShapeExporter.export(header, listOf(def), listOf(ShapeAssignment(def, box)))
            }
        }
    })
