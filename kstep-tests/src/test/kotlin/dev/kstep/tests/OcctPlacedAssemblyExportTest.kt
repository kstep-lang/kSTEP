package dev.kstep.tests

import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.core.getOrThrow
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.shape.Ap242PlacementReader
import dev.kstep.shape.Ap242ShapeExporter
import dev.kstep.shape.RigidPlacement
import dev.kstep.shape.Rotation
import dev.kstep.shape.ShapeAssignment
import dev.kstep.shape.ShapeExportException
import dev.kstep.shape.UsagePlacement
import dev.kstep.shape.Vec3
import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val header =
    Part21Header(
        fileName = "placed.step",
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

private fun usage(
    id: String,
    parent: ProductDefinition,
    child: ProductDefinition,
): NextAssemblyUsageOccurrence =
    nextAssemblyUsageOccurrence(id) {
        name = id
        relatingProductDefinition = parent
        relatedProductDefinition = child
    }.getOrThrow()

private fun count(
    text: String,
    token: String,
): Int = Regex(Regex.escape(token)).findAll(text).count()

/** Entity histogram: entity name -> number of instances (complex instances count each part). */
private fun histogram(text: String): Map<String, Int> =
    Part21Reader
        .readRawDocument(text, Part21ReadMode.TOLERANT)
        .instances
        .flatMap { i ->
            when (i) {
                is dev.kstep.step21.Part21SimpleInstance -> listOf(i.entityName)
                is dev.kstep.step21.Part21ComplexInstance -> i.parts.map { it.entityName }
            }
        }.groupingBy { it }
        .eachCount()

private val available get() = OcctKernel.availability() is OcctAvailability.Available

// The fixture of export-placed-assembly.kstep.kts, built through the API.
private class PlacedFixture : AutoCloseable {
    val asm = pd("ASM-001")
    val housing = pd("HSG-001")
    val bracket = pd("BRK-001")
    val screw = pd("SCR-001")
    val nauoH = usage("NAUO-H", asm, housing)
    val nauoB = usage("NAUO-B", asm, bracket)
    val nauoS1 = usage("NAUO-S1", asm, screw)
    val nauoS2 = usage("NAUO-S2", asm, screw)
    val roots: List<Any> = listOf(nauoH, nauoB, nauoS1, nauoS2)
    val shapes: List<OcctShape> =
        listOf(
            OcctKernel.makeBox(40.0, 40.0, 10.0),
            OcctKernel.makeBox(10.0, 20.0, 5.0),
            OcctKernel.makeBox(4.0, 4.0, 12.0),
        )
    val assignments =
        listOf(
            ShapeAssignment(housing, shapes[0]),
            ShapeAssignment(bracket, shapes[1]),
            ShapeAssignment(screw, shapes[2]),
        )
    val placementB = RigidPlacement(Vec3(30.0, 5.0, 10.0), Rotation.about(Vec3.Z, 90.0))
    val placementS1 = RigidPlacement(Vec3(20.0, 30.0, 10.0))
    val placementS2 = RigidPlacement(Vec3(8.0, 30.0, 10.0))
    val placements =
        listOf(
            UsagePlacement(nauoB, placementB),
            UsagePlacement(nauoS1, placementS1),
            UsagePlacement(nauoS2, placementS2),
        )

    fun export(placements: List<UsagePlacement> = this.placements): String =
        Ap242ShapeExporter.export(header, roots, assignments, placements)

    override fun close() = shapes.forEach { it.close() }
}

class OcctPlacedAssemblyExportTest :
    StringSpec({
        "a placed assembly writes one placement chain per usage and the numbers round-trip".config(
            enabled = available,
        ) {
            PlacedFixture().use { f ->
                val text = f.export()
                count(text, "CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(") shouldBe 4
                count(text, "ITEM_DEFINED_TRANSFORMATION(") shouldBe 4
                count(text, "REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION(") shouldBe 4
                val placements = Ap242PlacementReader.read(Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT))
                placements.keys shouldBe setOf("NAUO-H", "NAUO-B", "NAUO-S1", "NAUO-S2")
                placements.getValue("NAUO-H") shouldBe RigidPlacement.IDENTITY
                placements.getValue("NAUO-B") shouldBe f.placementB
                placements.getValue("NAUO-S1") shouldBe f.placementS1
                placements.getValue("NAUO-S2") shouldBe f.placementS2
            }
        }

        "the placed file is re-readable: typed structure intact, geometry and bridge opaque, no violations".config(
            enabled = available,
        ) {
            PlacedFixture().use { f ->
                val result = Part21Reader.read(f.export(), Part21ReadMode.TOLERANT, validateModel = true)
                result.violations shouldBe emptyMap()
                result.skipped shouldBe emptyMap()
                result.instances.values.count { it is ProductDefinition } shouldBe 4
                result.instances.values.count { it is NextAssemblyUsageOccurrence } shouldBe 4
                (result.opaque.size > 0) shouldBe true
                result.isModelValid shouldBe true
            }
        }

        "raw read followed by render reproduces the placed file byte for byte".config(enabled = available) {
            PlacedFixture().use { f ->
                val text = f.export()
                val raw = Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT)
                Part21Document(raw.header, raw.instances).render() shouldBe text
            }
        }

        "two exports of the same model are byte-identical".config(enabled = available) {
            PlacedFixture().use { f -> f.export() shouldBe f.export() }
        }

        "usages without place(...) are placed at the identity".config(enabled = available) {
            PlacedFixture().use { f ->
                val text = f.export(emptyList())
                count(text, "CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(") shouldBe 4
                Ap242PlacementReader
                    .read(Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT))
                    .values
                    .all { it.isIdentity } shouldBe true
            }
        }

        "exports without any assembly usage are unchanged by the placement logic".config(enabled = available) {
            val solo = pd("SOLO")
            OcctKernel.makeBox(2.0, 3.0, 4.0).use { box ->
                val a = ShapeAssignment(solo, box)
                val text = Ap242ShapeExporter.export(header, listOf(solo), listOf(a))
                count(text, "CONTEXT_DEPENDENT_SHAPE_REPRESENTATION(") shouldBe 0
                count(text, "ITEM_DEFINED_TRANSFORMATION(") shouldBe 0
                text shouldBe Ap242ShapeExporter.export(header, a)
            }
        }

        "an assembly of two shaped plain roots (no usage) has the pre-placement entity histogram".config(
            enabled = available,
        ) {
            val a = pd("PR-A")
            val b = pd("PR-B")
            OcctKernel.makeBox(1.0, 2.0, 3.0).use { s1 ->
                OcctKernel.makeBox(2.0, 2.0, 2.0).use { s2 ->
                    val text =
                        Ap242ShapeExporter.export(
                            header,
                            listOf(a, b),
                            listOf(ShapeAssignment(a, s1), ShapeAssignment(b, s2)),
                        )
                    val h = histogram(text)
                    h["CONTEXT_DEPENDENT_SHAPE_REPRESENTATION"] shouldBe null
                    h["ITEM_DEFINED_TRANSFORMATION"] shouldBe null
                    h["SHAPE_DEFINITION_REPRESENTATION"] shouldBe 2
                }
            }
        }

        "a duplicate placement fails closed with a clear message".config(enabled = available) {
            PlacedFixture().use { f ->
                val ex = shouldThrow<ShapeExportException> { f.export(f.placements + f.placements.first()) }
                ex.message!! shouldContain "placed more than once"
            }
        }

        "a placement on a usage outside the exported roots fails closed".config(enabled = available) {
            PlacedFixture().use { f ->
                val stray = usage("NAUO-X", f.asm, f.bracket)
                val ex =
                    shouldThrow<ShapeExportException> {
                        f.export(
                            listOf(UsagePlacement(stray, RigidPlacement.IDENTITY)),
                        )
                    }
                ex.message!! shouldContain "not registered as a root"
            }
        }

        "a part with its own geometry that also contains a component keeps both the shape and the placement".config(
            enabled = available,
        ) {
            val housing = pd("OG-H")
            val bracket = pd("OG-B")
            val u = usage("OG-U", housing, bracket)
            OcctKernel.makeBox(50.0, 40.0, 30.0).use { hb ->
                OcctKernel.makeBox(10.0, 20.0, 5.0).use { bb ->
                    val text =
                        Ap242ShapeExporter.export(
                            header,
                            listOf(u),
                            listOf(ShapeAssignment(housing, hb), ShapeAssignment(bracket, bb)),
                            listOf(UsagePlacement(u, RigidPlacement(Vec3(50.0, 0.0, 0.0)))),
                        )
                    val h = histogram(text)
                    h["MANIFOLD_SOLID_BREP"] shouldBe 2
                    h["CONTEXT_DEPENDENT_SHAPE_REPRESENTATION"] shouldBe 1
                    // no synthesized SHAPE_REPRESENTATION: both parts carry their own ADVANCED_BREP_SHAPE_REPRESENTATION
                    h["SHAPE_REPRESENTATION"] shouldBe null
                    Ap242PlacementReader
                        .read(Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT))
                        .getValue("OG-U")
                        .translation shouldBe Vec3(50.0, 0.0, 0.0)
                }
            }
        }

        "a usage cycle with geometry is rejected before OCCT is asked to do anything".config(enabled = available) {
            val x = pd("CY-X")
            val y = pd("CY-Y")
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { box ->
                val ex =
                    shouldThrow<ShapeExportException> {
                        Ap242ShapeExporter.export(
                            header,
                            listOf(usage("CY-1", x, y), usage("CY-2", y, x)),
                            listOf(ShapeAssignment(x, box)),
                            emptyList(),
                        )
                    }
                ex.message!! shouldContain "cycle"
            }
        }
    })
