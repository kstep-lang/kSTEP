package dev.kstep.shape

import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.style.StepColour
import dev.kstep.shape.PlacementFixtures.pd
import dev.kstep.shape.PlacementFixtures.usage
import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val subHeader =
    Part21Header(
        fileName = "sub.step",
        timestamp = "2026-01-01T00:00:00",
        schemaIdentifiers = listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF"),
        description = emptyList(),
        author = emptyList(),
        organization = emptyList(),
    )

private val blue = StepColour.rgb(0.0, 0.0, 1.0)
private val red = StepColour.rgb(1.0, 0.0, 0.0)

/**
 * ASM holds SUB twice (U-1, U-2); SUB holds BOX (solid #101) as U-3 and PLATE (solids #201, #202, #101 again to test the
 * deduplication) as U-4. Only BOX and PLATE have a shape.
 */
private class SubScenario {
    val asm = pd("ASM")
    val sub = pd("SUB")
    val box = pd("BOX")
    val plate = pd("PLATE")
    val u1 = usage("U-1", asm, sub)
    val u2 = usage("U-2", asm, sub)
    val u3 = usage("U-3", sub, box)
    val u4 = usage("U-4", sub, plate)
    val plan = AssemblyGeometryGraph.plan(listOf(u1, u2, u3, u4), listOf(box, plate), emptyList())
    val solidsOf: (ProductDefinition) -> List<Int> = {
        when {
            it === box -> listOf(101)
            it === plate -> listOf(201, 202, 101)
            else -> emptyList()
        }
    }
    private val pdIds = mapOf(asm to 1, sub to 2, box to 3, plate to 4)
    private val usageIds = mapOf(u1 to 5, u2 to 6, u3 to 7, u4 to 8)
    private val reps =
        mapOf(
            100 to
                Part21SimpleInstance(
                    100,
                    "ADVANCED_BREP_SHAPE_REPRESENTATION",
                    listOf(
                        Part21Value.Str(""),
                        Part21Value.ListValue(listOf(Part21Value.Ref(101))),
                        Part21Value.Ref(900),
                    ),
                ),
            200 to
                Part21SimpleInstance(
                    200,
                    "ADVANCED_BREP_SHAPE_REPRESENTATION",
                    listOf(
                        Part21Value.Str(""),
                        Part21Value.ListValue(listOf(Part21Value.Ref(201), Part21Value.Ref(202))),
                        Part21Value.Ref(900),
                    ),
                ),
        )
    val refs =
        Ap242PlacementBridge
            .build(
                plan = plan,
                startId = 1000,
                shapedRepresentationId = {
                    when {
                        it === box -> 100
                        it === plate -> 200
                        else -> null
                    }
                },
                representation = { reps.getValue(it) },
                contextId = { 900 },
                productDefinitionId = { pdIds.getValue(it) },
                usageId = { usageIds.getValue(it) },
            ).usageRefs

    fun planned(vararg styles: UsageStyle) = Ap242InstanceStyleBridge.plan(styles.toList(), plan, listOf(box, plate))

    fun build(
        planned: List<PlannedUsageStyle>,
        maxItems: Int = Ap242InstanceStyleBridge.MAX_SUBASSEMBLY_STYLED_ITEMS,
        startId: Int = 3000,
    ): List<Part21EntityInstance> =
        Ap242InstanceStyleBridge.buildSubassembly(
            planned = planned,
            refs = refs,
            startId = startId,
            leafSolidsOf = { Ap242InstanceStyleBridge.leafSolidsBelow(it, plan, solidsOf) },
            contextIdOf = { 900 },
            childOf = { r -> plan.usages.first { it.usage === r.usage }.child },
            maxStyledItems = maxItems,
        )
}

private fun List<Part21EntityInstance>.names() = map { (it as Part21SimpleInstance).entityName }

private fun List<Part21EntityInstance>.styledSolids() =
    filterIsInstance<Part21SimpleInstance>()
        .filter {
            it.entityName == "STYLED_ITEM"
        }.map { (it.args[2] as Part21Value.Ref).id }

class Ap242SubassemblyStyleBridgeTest :
    StringSpec({
        "a sub-assembly occurrence writes the chain, one STYLED_ITEM per distinct leaf solid, and its own MDGPR" {
            val s = SubScenario()
            val out = s.build(s.planned(UsageStyle(s.u2, blue)))
            // 9 chain entities + 4 distinct solids (101, 201, 202; 101 only once) = 3 items + MDGPR
            out.names().take(9) shouldBe
                listOf(
                    "COLOUR_RGB",
                    "FILL_AREA_STYLE_COLOUR",
                    "FILL_AREA_STYLE",
                    "SURFACE_STYLE_FILL_AREA",
                    "SURFACE_SIDE_STYLE",
                    "SURFACE_STYLE_USAGE",
                    "SHAPE_REPRESENTATION",
                    "SHAPE_DEFINITION_REPRESENTATION",
                    "PRESENTATION_STYLE_BY_CONTEXT",
                )
            out.styledSolids() shouldBe listOf(101, 201, 202)
            out.names().last() shouldBe "MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION"
            out.map { it.id } shouldBe (3000..3012).toList()
        }

        "the sub-assembly block writes the colour chain once per usage and shares nothing between usages" {
            val s = SubScenario()
            val out = s.build(s.planned(UsageStyle(s.u2, blue), UsageStyle(s.u1, red)))
            out.names().count { it == "COLOUR_RGB" } shouldBe 2
            out.names().count { it == "MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION" } shouldBe 1
            out.styledSolids() shouldBe listOf(101, 201, 202, 101, 201, 202)
        }

        "the part block ignores sub-assembly entries and the sub-assembly block ignores part entries" {
            val s = SubScenario()
            val mixed = s.planned(UsageStyle(s.u2, blue), UsageStyle(s.u3, red))
            mixed.map { it.kind } shouldBe listOf(StyledChildKind.SUBASSEMBLY, StyledChildKind.PART)
            val parts =
                Ap242InstanceStyleBridge.build(
                    planned = mixed,
                    refs = s.refs,
                    startId = 2000,
                    solidIdsOfChild = { s.solidsOf(it) },
                    contextIdOf = { 900 },
                    childOf = { r ->
                        s.plan.usages
                            .first { it.usage === r.usage }
                            .child
                    },
                )
            parts.styledSolids() shouldBe listOf(101)
            s.build(mixed).styledSolids() shouldBe listOf(101, 201, 202)
            Ap242InstanceStyleBridge.build(
                planned = s.planned(UsageStyle(s.u2, blue)),
                refs = s.refs,
                startId = 2000,
                solidIdsOfChild = { s.solidsOf(it) },
                contextIdOf = { 900 },
                childOf = { r ->
                    s.plan.usages
                        .first { it.usage === r.usage }
                        .child
                },
            ) shouldBe emptyList()
        }

        "the output is verified against its own allow-list and refers only to the leaf solids and placement entities" {
            val s = SubScenario()
            val out = s.build(s.planned(UsageStyle(s.u2, blue)))
            Ap242InstanceStyleBridge.SUBASSEMBLY_STYLE_ENTITY_NAMES shouldBe
                Ap242InstanceStyleBridge.INSTANCE_STYLE_ENTITY_NAMES
            val allowed = setOf(s.refs[1].placementShapeId, s.refs[1].placementAxisId, 900, 101, 201, 202)
            Ap242InstanceStyleBridge.verify(out, allowed, Ap242InstanceStyleBridge.SUBASSEMBLY_STYLE_ENTITY_NAMES)
            // a reference outside the collected leaf solids is refused
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.verify(
                    out,
                    allowed - 202,
                    Ap242InstanceStyleBridge.SUBASSEMBLY_STYLE_ENTITY_NAMES,
                )
            }.message shouldContain "#202"
            // a name that is not on the sub-assembly allow-list is refused
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.verify(
                    out,
                    allowed,
                    Ap242InstanceStyleBridge.SUBASSEMBLY_STYLE_ENTITY_NAMES - "STYLED_ITEM",
                )
            }.message shouldContain "STYLED_ITEM"
        }

        "a sub-assembly without any solid below it is rejected" {
            val s = SubScenario()
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.buildSubassembly(
                    planned = s.planned(UsageStyle(s.u2, blue)),
                    refs = s.refs,
                    startId = 3000,
                    leafSolidsOf = { Ap242InstanceStyleBridge.leafSolidsBelow(it, s.plan) { emptyList() } },
                    contextIdOf = { 900 },
                    childOf = { r ->
                        s.plan.usages
                            .first { it.usage === r.usage }
                            .child
                    },
                )
            }.message shouldContain "no solid below it"
        }

        "more styled items than the limit are rejected" {
            val s = SubScenario()
            shouldThrow<ShapeExportException> {
                s.build(s.planned(UsageStyle(s.u2, blue)), maxItems = 2)
            }.message shouldContain "too many styled items"
            s.build(s.planned(UsageStyle(s.u2, blue)), maxItems = 3).styledSolids().size shouldBe 3
            Ap242InstanceStyleBridge.MAX_SUBASSEMBLY_STYLED_ITEMS shouldBe 65_536
        }

        "ids near Int.MAX_VALUE fail closed instead of wrapping" {
            val s = SubScenario()
            shouldThrow<ShapeExportException> {
                s.build(
                    s.planned(UsageStyle(s.u2, blue)),
                    startId = Int.MAX_VALUE - 3,
                )
            }
        }

        "a second colour for one sub-assembly usage, a transparent colour and a foreign usage are rejected" {
            val s = SubScenario()
            shouldThrow<ShapeExportException> { s.planned(UsageStyle(s.u2, blue), UsageStyle(s.u2, red)) }
                .message shouldContain "more than one"
            shouldThrow<ShapeExportException> { s.planned(UsageStyle(s.u2, StepColour.rgba(0.0, 0.0, 1.0, 0.5))) }
                .message shouldContain "opaque"
            shouldThrow<ShapeExportException> { s.planned(UsageStyle(usage("U-X", s.asm, s.sub), blue)) }
                .message shouldContain "'U-X'"
        }

        "leaf solids of a diamond-shaped DAG are collected once" {
            val top = pd("TOP")
            val left = pd("LEFT")
            val right = pd("RIGHT")
            val bottom = pd("BOTTOM")
            val leaf = pd("LEAF")
            val usages =
                listOf(
                    usage("T-L", top, left),
                    usage("T-R", top, right),
                    usage("L-B", left, bottom),
                    usage("R-B", right, bottom),
                    usage("B-F", bottom, leaf),
                )
            val plan = AssemblyGeometryGraph.plan(usages, listOf(leaf), emptyList())
            Ap242InstanceStyleBridge.leafSolidsBelow(top, plan) {
                if (it ===
                    leaf
                ) {
                    listOf(7, 8)
                } else {
                    emptyList()
                }
            } shouldBe
                listOf(7, 8)
        }

        "the leaf walk is iterative: a very deep assembly does not overflow the stack" {
            val depth = 3000
            val defs = List(depth + 1) { pd("P$it") }
            val usages = List(depth) { usage("U$it", defs[it], defs[it + 1]) }
            val plan = AssemblyGeometryGraph.plan(usages, listOf(defs.last()), emptyList())
            Ap242InstanceStyleBridge.leafSolidsBelow(defs.first(), plan) {
                if (it ===
                    defs.last()
                ) {
                    listOf(1)
                } else {
                    emptyList()
                }
            } shouldBe
                listOf(1)
        }

        "a shaped sub-assembly keeps the part pattern (own solids only), its bytes are those of Welle V" {
            val asm = pd("ASM")
            val sub = pd("SUB")
            val box = pd("BOX")
            val u1 = usage("U-1", asm, sub)
            val u2 = usage("U-2", sub, box)
            val plan = AssemblyGeometryGraph.plan(listOf(u1, u2), listOf(sub, box), emptyList())
            val planned = Ap242InstanceStyleBridge.plan(listOf(UsageStyle(u1, blue)), plan, listOf(sub, box))
            planned.map { it.kind } shouldBe listOf(StyledChildKind.PART)
            Ap242InstanceStyleBridge.leafSolidsBelow(sub, plan) {
                if (it ===
                    sub
                ) {
                    listOf(5)
                } else {
                    listOf(6)
                }
            } shouldContain
                6
        }

        "the block renders as Part 21 text" {
            val s = SubScenario()
            val text = Part21Document(subHeader, s.build(s.planned(UsageStyle(s.u2, blue)))).render()
            text shouldContain "STYLED_ITEM('color',(#"
            text shouldContain ",#201)"
        }
    })
