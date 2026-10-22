package dev.kstep.shape

import dev.kstep.shape.PlacementFixtures.pd
import dev.kstep.shape.PlacementFixtures.usage
import dev.kstep.step21.Part21ComplexInstance
import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val header =
    Part21Header(
        fileName = "placed.step",
        timestamp = "2026-01-01T00:00:00",
        schemaIdentifiers = listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"),
        description = emptyList(),
        author = emptyList(),
        organization = emptyList(),
    )

private fun absr(
    id: Int,
    itemIds: List<Int>,
    contextId: Int,
): Part21SimpleInstance =
    Part21SimpleInstance(
        id,
        "ADVANCED_BREP_SHAPE_REPRESENTATION",
        listOf(
            Part21Value.Str(""),
            Part21Value.ListValue(itemIds.map { Part21Value.Ref(it) }),
            Part21Value.Ref(contextId),
        ),
    )

private fun nauo(
    id: Int,
    name: String,
) = Part21SimpleInstance(
    id,
    "NEXT_ASSEMBLY_USAGE_OCCURRENCE",
    listOf(
        Part21Value.Str(name),
        Part21Value.Str(name),
        Part21Value.Unset,
        Part21Value.Ref(1),
        Part21Value.Ref(2),
        Part21Value.Unset,
    ),
)

/** Builds the bridge for a shape-less assembly with the two shaped children A (id 100) and B (id 200). */
private class Scenario {
    val asm = pd("ASM")
    val a = pd("A")
    val b = pd("B")
    val uA = usage("U-A", asm, a)
    val uB = usage("U-B", asm, b)
    val placementB = RigidPlacement(Vec3(30.0, 5.0, 10.0), Rotation.about(Vec3.Z, 90.0))
    val repA = absr(100, listOf(101), 900)
    val repB = absr(200, listOf(201), 900)
    val plan =
        AssemblyGeometryGraph.plan(
            listOf(uA, uB),
            listOf(a, b),
            listOf(UsagePlacement(uB, placementB)),
        )
    private val pdIds = mapOf(asm to 1, a to 2, b to 3)
    private val usageIds = mapOf(uA to 4, uB to 5)

    val result =
        Ap242PlacementBridge.build(
            plan = plan,
            startId = 1000,
            shapedRepresentationId = {
                if (it === a) {
                    100
                } else if (it === b) {
                    200
                } else {
                    null
                }
            },
            representation = { if (it == 100) repA else repB },
            contextId = { 900 },
            productDefinitionId = { pdIds.getValue(it) },
            usageId = { usageIds.getValue(it) },
        )
}

class Ap242PlacementBridgeTest :
    StringSpec({
        "entity builders write the exact Part 21 texts with REAL lexemes" {
            val ids = IdAllocator(10)
            val axis =
                Ap242PlacementBridge.axis2Placement3d(
                    ids,
                    RigidPlacement(Vec3(30.0, 5.0, 10.0), Rotation.about(Vec3.Z, 90.0)),
                )
            axis.axisId shouldBe 13
            val text = Part21Document(header, axis.instances).render()
            text shouldContain "#10=CARTESIAN_POINT('',(30.0,5.0,10.0));"
            text shouldContain "#11=DIRECTION('',(0.,0.,1.0));"
            text shouldContain "#12=DIRECTION('',(0.,1.0,0.));"
            text shouldContain "#13=AXIS2_PLACEMENT_3D('',#10,#11,#12);"
        }

        "the relationship is a complex instance with parts in alphabetical order" {
            val srr = Ap242PlacementBridge.shapeRepresentationRelationshipWithTransformation(7, 3, 4, 6)
            srr.parts.map { it.entityName } shouldBe
                listOf(
                    "REPRESENTATION_RELATIONSHIP",
                    "REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION",
                    "SHAPE_REPRESENTATION_RELATIONSHIP",
                )
            srr.parts.map { it.entityName } shouldBe srr.parts.map { it.entityName }.sorted()
            val text = Part21Document(header, listOf(srr)).render()
            text shouldContain "REPRESENTATION_RELATIONSHIP('','',#3,#4)"
            text shouldContain "REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION(#6)"
            text shouldContain "SHAPE_REPRESENTATION_RELATIONSHIP()"
        }

        "the remaining builders write OCCT's own names and the component-first operand order" {
            Ap242PlacementBridge.nauoShape(5, 9).args shouldBe
                listOf(Part21Value.Str("Placement"), Part21Value.Str("Placement of an item"), Part21Value.Ref(9))
            Ap242PlacementBridge.itemDefinedTransformation(5, 1, 2).args shouldBe
                listOf(Part21Value.Str(""), Part21Value.Str(""), Part21Value.Ref(1), Part21Value.Ref(2))
            Ap242PlacementBridge.contextDependentShapeRepresentation(5, 3, 4).args shouldBe
                listOf(Part21Value.Ref(3), Part21Value.Ref(4))
            Ap242PlacementBridge.shapeRepresentation(5, listOf(1, 2), 9).args shouldBe
                listOf(
                    Part21Value.Str(""),
                    Part21Value.ListValue(listOf(Part21Value.Ref(1), Part21Value.Ref(2))),
                    Part21Value.Ref(9),
                )
        }

        "build: ids are allocated sequentially from startId, deterministically" {
            val first = Scenario().result.added.map { it.id }
            val second = Scenario().result.added.map { it.id }
            first shouldBe second
            first.sorted() shouldBe first.sorted().distinct()
            first.min() shouldBe 1000
            first.max() shouldBe 1000 + first.size - 1
        }

        "build: shaped children keep their representation id and gain the placement axes as items" {
            val s = Scenario()
            s.result.replaced.keys shouldBe setOf(100, 200)
            val itemsOfA =
                (
                    s.result.replaced
                        .getValue(100)
                        .args[1] as Part21Value.ListValue
                ).items
            itemsOfA.size shouldBe 2 // original item + own origin axis
            itemsOfA.first() shouldBe Part21Value.Ref(101)
            // an originally empty-of-placement context argument is untouched
            s.result.replaced
                .getValue(100)
                .args[2] shouldBe Part21Value.Ref(900)
        }

        "build: a shape-less assembly gets a synthesized representation with its own product definition shape" {
            val s = Scenario()
            val names =
                s.result.added
                    .filterIsInstance<Part21SimpleInstance>()
                    .map { it.entityName }
            names.count { it == "SHAPE_REPRESENTATION" } shouldBe 1
            names.count { it == "SHAPE_DEFINITION_REPRESENTATION" } shouldBe 1
            names.count { it == "CONTEXT_DEPENDENT_SHAPE_REPRESENTATION" } shouldBe 2
            names.count { it == "ITEM_DEFINED_TRANSFORMATION" } shouldBe 2
            names.count { it == "PRODUCT_DEFINITION_SHAPE" } shouldBe 3 // 1 for the assembly, 2 for the usages
            s.result.added
                .filterIsInstance<Part21ComplexInstance>()
                .size shouldBe 2
            val sr =
                s.result.added.filterIsInstance<Part21SimpleInstance>().single {
                    it.entityName ==
                        "SHAPE_REPRESENTATION"
                }
            (sr.args[1] as Part21Value.ListValue).items.size shouldBe 2 // two placement axes
            sr.args[2] shouldBe Part21Value.Ref(900)
        }

        "build: relationships put the component first and the assembly second" {
            val s = Scenario()
            val sr =
                s.result.added.filterIsInstance<Part21SimpleInstance>().single {
                    it.entityName ==
                        "SHAPE_REPRESENTATION"
                }
            val srr =
                s.result.added
                    .filterIsInstance<Part21ComplexInstance>()
                    .first()
            val rr = srr.parts.first { it.entityName == "REPRESENTATION_RELATIONSHIP" }
            rr.args[2] shouldBe Part21Value.Ref(100) // component A's representation
            rr.args[3] shouldBe Part21Value.Ref(sr.id) // the assembly's representation
        }

        "rendered output survives a raw read and re-render byte for byte" {
            val s = Scenario()
            val all: List<Part21EntityInstance> = listOf(s.repA, s.repB) + s.result.added
            val text = Part21Document(header, all).render()
            val raw = Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT)
            Part21Document(header, raw.instances).render() shouldBe text
        }

        "the placement reader returns the written numbers" {
            val s = Scenario()
            val replaced = s.result.replaced
            val rest = s.result.added
            val nauoInstances =
                listOf(
                    nauo(4, "U-A"),
                    nauo(5, "U-B"),
                )
            val text = Part21Document(header, nauoInstances + replaced.values + rest).render()
            val raw = Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT)
            val read = Ap242PlacementReader.read(raw)
            read.keys shouldBe setOf("U-A", "U-B")
            read.getValue("U-A").isIdentity shouldBe true
            val b = read.getValue("U-B")
            b.translation.x shouldBe (30.0 plusOrMinus 1e-12)
            b.translation.y shouldBe (5.0 plusOrMinus 1e-12)
            b.translation.z shouldBe (10.0 plusOrMinus 1e-12)
            b.rotation.axis shouldBe Vec3(0.0, 0.0, 1.0)
            b.rotation.refDirection shouldBe Vec3(0.0, 1.0, 0.0)
        }

        "id allocation overflow is a ShapeExportException" {
            val ids = IdAllocator(Int.MAX_VALUE)
            ids.next() shouldBe Int.MAX_VALUE
            shouldThrow<ShapeExportException> { ids.next() }
        }

        "a representation without the expected argument shape cannot receive placement axes" {
            val s = Scenario()
            val broken = Part21SimpleInstance(100, "ADVANCED_BREP_SHAPE_REPRESENTATION", listOf(Part21Value.Str("")))
            val ex =
                shouldThrow<ShapeExportException> {
                    Ap242PlacementBridge.build(
                        plan = s.plan,
                        startId = 1000,
                        shapedRepresentationId = {
                            if (it === s.a) {
                                100
                            } else if (it === s.b) {
                                200
                            } else {
                                null
                            }
                        },
                        representation = { if (it == 100) broken else s.repB },
                        contextId = { 900 },
                        productDefinitionId = { 1 },
                        usageId = { 4 },
                    )
                }
            ex.message!! shouldContain "cannot attach placement axes"
        }

        "an axis list contains the placement of each usage" {
            val s = Scenario()
            s.result.added
                .filterIsInstance<Part21SimpleInstance>()
                .map { it.entityName } shouldContain
                "AXIS2_PLACEMENT_3D"
        }
    })
