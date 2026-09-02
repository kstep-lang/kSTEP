package dev.kstep.shape

import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21RawDocument
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import dev.kstep.step21.Part21WriteException
import dev.kstep.step21.Part21Writer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

private fun testHeader(): Part21Header =
    Part21Header(fileName = "x.step", timestamp = "2026-09-02T00:00:00", schemaIdentifiers = listOf("S"))

private fun instance(
    id: Int,
    name: String,
    args: List<Part21Value> = emptyList(),
) = Part21SimpleInstance(id, name, args)

/**
 * Same shape as `Ap242GeometryExtractionTest.minimalValidDocument`: SDR(#1) -> ABSR(#3) ->
 * [MSB(#4) -> CLOSED_SHELL(#5)], CONTEXT(#7).
 */
private fun occtShapedDocument(): Part21RawDocument =
    Part21RawDocument(
        testHeader(),
        listOf(
            instance(1, "SHAPE_DEFINITION_REPRESENTATION", listOf(Part21Value.Ref(2), Part21Value.Ref(3))),
            instance(2, "PRODUCT_DEFINITION_SHAPE", listOf(Part21Value.Str(""), Part21Value.Unset, Part21Value.Ref(6))),
            instance(
                3,
                "ADVANCED_BREP_SHAPE_REPRESENTATION",
                listOf(Part21Value.Str(""), Part21Value.ListValue(listOf(Part21Value.Ref(4))), Part21Value.Ref(7)),
            ),
            instance(4, "MANIFOLD_SOLID_BREP", listOf(Part21Value.Str(""), Part21Value.Ref(5))),
            instance(5, "CLOSED_SHELL", listOf(Part21Value.Str(""), Part21Value.ListValue(emptyList()))),
            instance(6, "PRODUCT_DEFINITION", listOf(Part21Value.Str("x"))),
            instance(7, "GEOMETRIC_REPRESENTATION_CONTEXT", listOf(Part21Value.Num("3"))),
        ),
    )

private data class TestProductStructure(
    val applicationContext: ApplicationContext,
    val product: Product,
    val productDefinition: ProductDefinition,
)

private fun buildProductStructure(): TestProductStructure {
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
    return TestProductStructure(appCtx, prod, pd)
}

/**
 * Replays `Ap242ShapeExporter.export`'s exact merge arithmetic (`Part21Writer.emit` ->
 * `Part21Document.renumbered` offset by `structure.nextId - 1` -> the four
 * [Ap242ShapeBridgeEntities] -> `Part21Document.concat`) against a hand-built [Part21RawDocument]
 * standing in for OCCT's own STEP output, so this coverage runs on a machine without the native
 * OCCT bridge — the only other exerciser of this arithmetic,
 * `dev.kstep.tests.Ap242ShapeExportRoundtripTest`, is entirely `OcctKernel.availability()`-gated.
 */
class Ap242ShapeMergeTest :
    StringSpec({
        "the full merge renumbers the geometry subgraph past the structure and wires the bridge without id collisions" {
            val structureFixture = buildProductStructure()
            val (appCtx, prod, pd) = structureFixture

            val geometry = Ap242GeometryExtraction.extract(occtShapedDocument())
            // Pre-merge sanity, mirroring Ap242GeometryExtractionTest: geometry keeps only the
            // ABSR/MSB/CLOSED_SHELL/CONTEXT closure, none of OCCT's own placeholder product structure.
            geometry.instances.map { it.id } shouldBe listOf(3, 4, 5, 7)
            geometry.rootRepresentationId shouldBe 3

            val structure = Part21Writer.emit(listOf(pd), startId = 1)
            val offset = structure.nextId - 1

            val geometryRenumbered: List<Part21EntityInstance> =
                Part21Document(testHeader(), geometry.instances).renumbered(offset).instances
            val rootRepresentationId = geometry.rootRepresentationId + offset

            val nextFreeId = geometryRenumbered.maxOf { it.id } + 1
            val bridge =
                listOf(
                    Ap242ShapeBridgeEntities.productDefinitionShape(nextFreeId, "bracket solid", structure.idOf(pd)),
                    Ap242ShapeBridgeEntities.shapeDefinitionRepresentation(
                        nextFreeId + 1,
                        nextFreeId,
                        rootRepresentationId,
                    ),
                    Ap242ShapeBridgeEntities.applicationProtocolDefinition(nextFreeId + 2, structure.idOf(appCtx)),
                    Ap242ShapeBridgeEntities.productRelatedProductCategory(nextFreeId + 3, structure.idOf(prod)),
                )

            val structureDoc = Part21Document(testHeader(), structure.instances)
            val merged = Part21Document.concat(structureDoc, geometryRenumbered, bridge)

            // No id is reused across the three groups — every one of structure + geometry +
            // bridge survives into the merged document (concat would otherwise have thrown).
            merged.instances.size shouldBe structure.instances.size + geometry.instances.size + bridge.size
            merged.instances
                .map { it.id }
                .distinct()
                .size shouldBe merged.instances.size

            // Geometry ids strictly follow the structure's ids — offset is exactly nextId - 1.
            geometryRenumbered.map { it.id } shouldBe listOf(3 + offset, 4 + offset, 5 + offset, 7 + offset)

            val text = merged.render()
            val reimported = Part21Reader.read(text, Part21ReadMode.TOLERANT)
            reimported.isFullySuccessful shouldBe true

            // The actual link: SHAPE_DEFINITION_REPRESENTATION -> PRODUCT_DEFINITION_SHAPE -> the
            // SAME ProductDefinition object kSTEP built, by identity.
            val sdr =
                reimported.opaque.values
                    .filterIsInstance<Part21SimpleInstance>()
                    .single { it.entityName == "SHAPE_DEFINITION_REPRESENTATION" }
            val pdsId = (sdr.args[0] as Part21Value.Ref).id
            val pds = reimported.opaque.getValue(pdsId) as Part21SimpleInstance
            pds.entityName shouldBe "PRODUCT_DEFINITION_SHAPE"
            val linkedPdId = (pds.args[2] as Part21Value.Ref).id
            val readPd = reimported.instances.getValue(structure.idOf(pd))
            (reimported.instances.getValue(linkedPdId) === readPd) shouldBe true

            val absrId = (sdr.args[1] as Part21Value.Ref).id
            absrId shouldBe rootRepresentationId
        }

        "concat's duplicate-id guard fires if geometry is merged without first being renumbered past the structure" {
            val pd = buildProductStructure().productDefinition

            val geometry = Ap242GeometryExtraction.extract(occtShapedDocument())
            val structure = Part21Writer.emit(listOf(pd), startId = 1)
            val structureDoc = Part21Document(testHeader(), structure.instances)

            // Sanity: the structure really does use an id that also appears, unrenumbered, in
            // geometry — otherwise this test would prove nothing about the guard.
            structure.instances.map { it.id } shouldContain 3
            geometry.instances.map { it.id } shouldContain 3

            shouldThrow<Part21WriteException> {
                Part21Document.concat(structureDoc, geometry.instances)
            }
        }
    })
