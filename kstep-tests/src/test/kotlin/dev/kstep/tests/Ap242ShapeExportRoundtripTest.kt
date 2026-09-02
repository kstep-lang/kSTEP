package dev.kstep.tests

import dev.kstep.core.ap242.applicationContext
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
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

private fun testHeader(): Part21Header =
    Part21Header(
        fileName = "bracket-with-geometry.step",
        timestamp = "2026-09-02T00:00:00",
        schemaIdentifiers = listOf("placeholder — overwritten by Ap242ShapeExporter"),
        description = listOf("kSTEP Geometrie Welle 4 end-to-end validation fixture"),
        author = listOf("Author"),
        organization = listOf("kSTEP"),
    )

/**
 * The end-to-end proof for kSTEP Geometrie Welle 4 (see ADR-0009): a real OCCT box merged with a
 * real, validated `kstep-core` AP242 product structure into one file, reimported, and checked
 * for the actual *link* between the two halves — not merely that both are present somewhere.
 */
class Ap242ShapeExportRoundtripTest :
    StringSpec({
        "a 20x30x40mm box merges with a validated product structure into one re-importable AP242 file".config(
            enabled = OcctKernel.availability() is OcctAvailability.Available,
        ) {
            val appCtx = applicationContext { application = "config control" }.getOrThrow()
            val prodCtx =
                productContext {
                    name = "engineering"
                    frameOfReference = appCtx
                    disciplineType = "mechanical"
                }.getOrThrow()
            val defCtx =
                productDefinitionContext {
                    name = "engineering"
                    frameOfReference = appCtx
                    lifeCycleStage = "design"
                }.getOrThrow()
            val prod =
                product("BRK-001") {
                    name = "Bracket"
                    frameOfReference = setOf(prodCtx)
                }.getOrThrow()
            val builtFormation = productDefinitionFormation("BRK-001-F") { ofProduct = prod }.getOrThrow()
            val pd =
                productDefinition("BRK-001-D") {
                    formation = builtFormation
                    frameOfReference = defCtx
                }.getOrThrow()

            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                val topology = box.topology

                val text = Ap242ShapeExporter.export(testHeader(), ShapeAssignment(pd, box, "bracket solid"))

                val outDir = File("build/ap242-shape-validation").apply { mkdirs() }
                File(outDir, "bracket-with-geometry.step").writeText(text)

                val result = Part21Reader.read(text, Part21ReadMode.TOLERANT)
                result.isFullySuccessful shouldBe true

                // The product structure is kSTEP's own, validated one.
                val readProductDefinitions = result.instances.values.filterIsInstance<ProductDefinition>()
                readProductDefinitions.size shouldBe 1
                val readPd = readProductDefinitions.single()
                readPd.id shouldBe "BRK-001-D"
                readPd.formation.ofProduct.id shouldBe "BRK-001"
                readPd.formation.ofProduct.name shouldBe "Bracket"

                // No leftover OCCT placeholder product structure.
                result.opaque.values
                    .filterIsInstance<Part21SimpleInstance>()
                    .none { it.entityName == "PRODUCT" } shouldBe
                    true
                result.opaque.values.filterIsInstance<Part21SimpleInstance>().none {
                    it.entityName ==
                        "PRODUCT_DEFINITION"
                } shouldBe
                    true
                text shouldNotContain "Open CASCADE STEP translator"

                // The link itself: PRODUCT_DEFINITION_SHAPE -> the SAME ProductDefinition object read above.
                val sdr =
                    result.opaque.values
                        .filterIsInstance<Part21SimpleInstance>()
                        .single { it.entityName == "SHAPE_DEFINITION_REPRESENTATION" }
                val pdsId = (sdr.args[0] as Part21Value.Ref).id
                val pds = result.opaque.getValue(pdsId) as Part21SimpleInstance
                pds.entityName shouldBe "PRODUCT_DEFINITION_SHAPE"
                val linkedPdId = (pds.args[2] as Part21Value.Ref).id
                (result.instances.getValue(linkedPdId) === readPd) shouldBe true

                val absrId = (sdr.args[1] as Part21Value.Ref).id
                val absr = result.opaque.getValue(absrId) as Part21SimpleInstance
                absr.entityName shouldBe "ADVANCED_BREP_SHAPE_REPRESENTATION"
                val items = absr.args[1]
                items.shouldBeInstanceOf<Part21Value.ListValue>()
                val hasSolidRef =
                    items.items.filterIsInstance<Part21Value.Ref>().any { ref ->
                        val target = result.opaque[ref.id] as? Part21SimpleInstance
                        target?.entityName == "MANIFOLD_SOLID_BREP"
                    }
                hasSolidRef shouldBe true

                // The geometry is genuinely OCCT's — counts match the shape's own topology.
                val opaqueSimple = result.opaque.values.filterIsInstance<Part21SimpleInstance>()
                opaqueSimple.count { it.entityName == "ADVANCED_FACE" } shouldBe topology.faces
                opaqueSimple.count { it.entityName == "VERTEX_POINT" } shouldBe topology.vertices
                opaqueSimple.count { it.entityName == "EDGE_CURVE" } shouldBe topology.edges

                text shouldContain Ap242ShapeExporter.AP242_SCHEMA_IDENTIFIER
                result.header.originatingSystem shouldBe "kSTEP"
            }
        }
    })
