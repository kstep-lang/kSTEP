package dev.kstep.shape

import dev.kstep.geometry.style.StepColour
import dev.kstep.shape.PlacementFixtures.pd
import dev.kstep.shape.PlacementFixtures.usage
import dev.kstep.step21.Part21ComplexInstance
import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21InstancePart
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val styleHeader =
    Part21Header(
        fileName = "styled.step",
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
) = Part21SimpleInstance(
    id,
    "ADVANCED_BREP_SHAPE_REPRESENTATION",
    listOf(
        Part21Value.Str(""),
        Part21Value.ListValue(itemIds.map { Part21Value.Ref(it) }),
        Part21Value.Ref(contextId),
    ),
)

/** Assembly ASM holding box A (representation #100, solids #101 and #102) twice, as U-1 and U-2. */
private class StyleScenario(
    val solids: List<Int> = listOf(101),
) {
    val asm = pd("ASM")
    val a = pd("A")
    val u1 = usage("U-1", asm, a)
    val u2 = usage("U-2", asm, a)
    val plan = AssemblyGeometryGraph.plan(listOf(u1, u2), listOf(a), emptyList())
    private val rep = absr(100, solids, 900)
    private val pdIds = mapOf(asm to 1, a to 2)
    private val usageIds = mapOf(u1 to 3, u2 to 4)
    val built =
        Ap242PlacementBridge
            .build(
                plan = plan,
                startId = 1000,
                shapedRepresentationId = { if (it === a) 100 else null },
                representation = { rep },
                contextId = { 900 },
                productDefinitionId = { pdIds.getValue(it) },
                usageId = { usageIds.getValue(it) },
            )
    val refs = built.usageRefs

    /** The usages as the structure writer would emit them (ids 3 and 4), for round trips. */
    val usageEntities =
        listOf(u1 to 3, u2 to 4).map { (u, id) ->
            Part21SimpleInstance(
                id,
                "NEXT_ASSEMBLY_USAGE_OCCURRENCE",
                listOf(
                    Part21Value.Str(u.id),
                    Part21Value.Str(u.id),
                    Part21Value.Unset,
                    Part21Value.Ref(1),
                    Part21Value.Ref(2),
                    Part21Value.Unset,
                ),
            )
        }

    fun build(
        styles: List<UsageStyle>,
        startId: Int = 2000,
    ): List<Part21EntityInstance> =
        Ap242InstanceStyleBridge.build(
            planned = Ap242InstanceStyleBridge.plan(styles, plan, listOf(a)),
            refs = refs,
            startId = startId,
            solidIdsOfChild = { solids },
            contextIdOf = { 900 },
            childOf = { a },
        )
}

private val blue = StepColour.rgb(0.0, 0.0, 1.0)
private val red = StepColour.rgb(1.0, 0.0, 0.0)

class Ap242InstanceStyleBridgeTest :
    StringSpec({
        "one coloured usage writes ten entities and one presentation representation in a fixed order" {
            val s = StyleScenario()
            val out = s.build(listOf(UsageStyle(s.u2, blue)))
            out.map { (it as Part21SimpleInstance).entityName } shouldBe
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
                    "STYLED_ITEM",
                    "MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION",
                )
            out.map { it.id } shouldBe (2000..2010).toList()
        }

        "the entities have the exact Part 21 texts of the verified pattern" {
            val s = StyleScenario()
            val u2 = s.refs[1]
            val text = Part21Document(styleHeader, s.build(listOf(UsageStyle(s.u2, blue)))).render()
            text shouldContain "#2000=COLOUR_RGB('',0.,0.,1.0);"
            text shouldContain "#2001=FILL_AREA_STYLE_COLOUR('',#2000);"
            text shouldContain "#2002=FILL_AREA_STYLE('',(#2001));"
            text shouldContain "#2003=SURFACE_STYLE_FILL_AREA(#2002);"
            text shouldContain "#2004=SURFACE_SIDE_STYLE('',(#2003));"
            text shouldContain "#2005=SURFACE_STYLE_USAGE(.BOTH.,#2004);"
            text shouldContain "#2006=SHAPE_REPRESENTATION('',(#${u2.placementAxisId}),#900);"
            text shouldContain "#2007=SHAPE_DEFINITION_REPRESENTATION(#${u2.placementShapeId},#2006);"
            text shouldContain "#2008=PRESENTATION_STYLE_BY_CONTEXT((#2005),#2006);"
            text shouldContain "#2009=STYLED_ITEM('color',(#2008),#101);"
            text shouldContain "#2010=MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION('',(#2009),#900);"
        }

        "the entities follow the order of the plan, not the order of registration" {
            val s = StyleScenario()
            val out = s.build(listOf(UsageStyle(s.u2, blue), UsageStyle(s.u1, red)))
            // first usage (U-1, red) comes first
            (out[0] as Part21SimpleInstance).args[1] shouldBe Part21Value.Num("1.0")
            out.size shouldBe 21
            out.last().id shouldBe 2020
        }

        "a child with several solids gets one styled item per solid under one shared context style" {
            val s = StyleScenario(solids = listOf(101, 102))
            val out = s.build(listOf(UsageStyle(s.u2, blue)))
            val styled = out.filterIsInstance<Part21SimpleInstance>().filter { it.entityName == "STYLED_ITEM" }
            styled.size shouldBe 2
            styled.map { it.args[2] } shouldBe listOf(Part21Value.Ref(101), Part21Value.Ref(102))
            styled.map { it.args[1] }.distinct().size shouldBe 1
        }

        "the reader returns the written colours per usage id" {
            val s = StyleScenario()
            val styled = s.build(listOf(UsageStyle(s.u2, blue), UsageStyle(s.u1, StepColour.rgb(0.25, 0.5, 0.75))))
            val text = Part21Document(styleHeader, s.usageEntities + s.built.added + styled).render()
            val read = Ap242InstanceStyleReader.read(Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT))
            read shouldBe mapOf("U-1" to StepColour.rgb(0.25, 0.5, 0.75), "U-2" to blue)
        }

        "the reader rejects a style whose context has no usage" {
            val s = StyleScenario()
            val styled =
                s.build(listOf(UsageStyle(s.u2, blue))).filterNot {
                    (it as Part21SimpleInstance).entityName ==
                        "SHAPE_DEFINITION_REPRESENTATION"
                }
            val text = Part21Document(styleHeader, s.usageEntities + s.built.added + styled).render()
            shouldThrow<IllegalArgumentException> {
                Ap242InstanceStyleReader.read(Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT))
            }
        }

        "no instance colours write nothing" {
            val s = StyleScenario()
            s.build(emptyList()) shouldBe emptyList()
        }

        "a usage that is not a geometric usage of the plan is rejected" {
            val s = StyleScenario()
            val other = usage("U-X", s.asm, s.a)
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.plan(listOf(UsageStyle(other, blue)), s.plan, listOf(s.a))
            }.message shouldContain "'U-X'"
        }

        "a child without a shape of its own is a sub-assembly and accepted (ADR-0046)" {
            val top = pd("TOP")
            val sub = pd("SUB")
            val leaf = pd("LEAF")
            val uTop = usage("U-TOP", top, sub)
            val uSub = usage("U-SUB", sub, leaf)
            val plan = AssemblyGeometryGraph.plan(listOf(uTop, uSub), listOf(leaf), emptyList())
            val planned = Ap242InstanceStyleBridge.plan(listOf(UsageStyle(uTop, blue)), plan, listOf(leaf))
            planned.map { it.kind } shouldBe listOf(StyledChildKind.SUBASSEMBLY)
            // the occurrence of the leaf itself is a part
            Ap242InstanceStyleBridge.plan(listOf(UsageStyle(uSub, blue)), plan, listOf(leaf)).map { it.kind } shouldBe
                listOf(StyledChildKind.PART)
        }

        "a second colour for one usage is rejected" {
            val s = StyleScenario()
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.plan(
                    listOf(UsageStyle(s.u1, blue), UsageStyle(s.u1, red)),
                    s.plan,
                    listOf(s.a),
                )
            }.message shouldContain "more than one instance colour"
        }

        "a translucent colour is accepted by the plan, including alpha 0 (Welle Y, replaces the old opacity refusal)" {
            val s = StyleScenario()
            for (a in listOf(0.5, 0.0, 0.25, -0.0)) {
                Ap242InstanceStyleBridge
                    .plan(listOf(UsageStyle(s.u1, StepColour.rgba(0.0, 0.0, 1.0, a))), s.plan, listOf(s.a))
                    .size shouldBe 1
            }
        }

        "StepColour refuses an invalid alpha before the bridge is reached" {
            for (a in listOf(Double.NaN, -0.01, 1.01, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                shouldThrow<dev.kstep.geometry.style.StyleException> { StepColour.rgba(0.0, 0.0, 1.0, a) }
            }
        }

        "a translucent usage writes twelve entities, a two-item side style and the verified transparency chain" {
            val s = StyleScenario()
            val out = s.build(listOf(UsageStyle(s.u2, StepColour.rgba(0.0, 0.0, 1.0, 0.5))))
            out.size shouldBe 13
            val text = Part21Document(styleHeader, out).render()
            text shouldContain "#2004=SURFACE_STYLE_TRANSPARENT(0.5);"
            text shouldContain "#2005=SURFACE_STYLE_RENDERING_WITH_PROPERTIES(.NORMAL_SHADING.,#2000,(#2004));"
            text shouldContain "#2006=SURFACE_SIDE_STYLE('',(#2003,#2005));"
            text shouldContain "#2007=SURFACE_STYLE_USAGE(.BOTH.,#2006);"
        }

        "alpha 1 writes exactly the bytes of the opaque block and a mixed block keeps the ids of the opaque usage" {
            val s = StyleScenario()
            val plain = Part21Document(styleHeader, s.build(listOf(UsageStyle(s.u2, blue)))).render()
            Part21Document(
                styleHeader,
                s.build(listOf(UsageStyle(s.u2, StepColour.rgba(0.0, 0.0, 1.0, 1.0)))),
            ).render() shouldBe
                plain
            val mixed = s.build(listOf(UsageStyle(s.u1, red), UsageStyle(s.u2, StepColour.rgba(0.0, 0.0, 1.0, 0.5))))
            val opaqueOnly = s.build(listOf(UsageStyle(s.u1, red)))
            mixed.take(10) shouldBe opaqueOnly.take(10)
            mixed.size shouldBe 10 + 12 + 1
        }

        "both allow-lists have thirteen names, the extraction allow-list has no PRESENTATION_STYLE_BY_CONTEXT" {
            Ap242InstanceStyleBridge.INSTANCE_STYLE_ENTITY_NAMES.size shouldBe 13
            Ap242InstanceStyleBridge.SUBASSEMBLY_STYLE_ENTITY_NAMES.size shouldBe 13
            Ap242InstanceStyleBridge.INSTANCE_STYLE_ENTITY_NAMES shouldContain "SURFACE_STYLE_TRANSPARENT"
            Ap242GeometryExtraction.STYLE_ENTITY_NAMES shouldNotContain "PRESENTATION_STYLE_BY_CONTEXT"
        }

        "the reader round-trips a translucent colour within 1e-6" {
            val s = StyleScenario()
            val c = StepColour.rgba(0.75, 0.3, 0.25, 0.3)
            val styled = s.build(listOf(UsageStyle(s.u2, c), UsageStyle(s.u1, blue)))
            val text = Part21Document(styleHeader, s.usageEntities + s.built.added + styled).render()
            val read = Ap242InstanceStyleReader.read(Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT))
            read.getValue("U-1") shouldBe blue
            (kotlin.math.abs(read.getValue("U-2").a - 0.3) < 1e-6) shouldBe true
            read.getValue("U-2").r shouldBe 0.75
        }

        "alpha -0.0 is accepted and writes a transparency of 1" {
            val s = StyleScenario()
            val text =
                Part21Document(
                    styleHeader,
                    s.build(listOf(UsageStyle(s.u2, StepColour.rgba(0.0, 0.0, 1.0, -0.0)))),
                ).render()
            text shouldContain "SURFACE_STYLE_TRANSPARENT(1.0);"
        }

        "the reader rejects hostile transparency chains" {
            val s = StyleScenario()
            val styled = s.build(listOf(UsageStyle(s.u2, StepColour.rgba(0.0, 0.0, 1.0, 0.5))))
            val good = Part21Document(styleHeader, s.usageEntities + s.built.added + styled).render()

            fun reads(text: String) =
                Ap242InstanceStyleReader.read(Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT))
            reads(good).getValue("U-2").a shouldBe 0.5
            val tampered =
                listOf(
                    good.replace("TRANSPARENT(0.5)", "TRANSPARENT(-0.5)"),
                    good.replace("TRANSPARENT(0.5)", "TRANSPARENT(1.5)"),
                    good.replace("TRANSPARENT(0.5)", "TRANSPARENT(1.E400)"),
                    good.replace("TRANSPARENT(0.5)", "TRANSPARENT(ABC)"),
                    good.replace("TRANSPARENT(0.5)", "TRANSPARENT(0.5,0.5)"),
                    good.replace("(.NORMAL_SHADING.,#2000,(#2004))", "(.NORMAL_SHADING.,#2000,(#2004,#2004))"),
                    good.replace("(.NORMAL_SHADING.,#2000,(#2004))", "(.NORMAL_SHADING.,#2000,#2004)"),
                    good.replace("(.NORMAL_SHADING.,#2000,", "(.NORMAL_SHADING.,#999,"),
                    good.replace("(#2003,#2005)", "(#2003,#2005,#2005)"),
                    good.replace("(.NORMAL_SHADING.", "(.FLAT_SHADING."),
                )
            for (t in tampered) {
                shouldThrow<Exception> { reads(t) }
            }
        }

        "a manipulated translucent block fails the self-check" {
            val s = StyleScenario()
            val out = s.build(listOf(UsageStyle(s.u2, StepColour.rgba(0.0, 0.0, 1.0, 0.5))))
            val allowed = setOf(s.refs[1].placementShapeId, s.refs[1].placementAxisId, 900, 101)
            Ap242InstanceStyleBridge.verify(out, allowed)
            val foreign =
                out.map {
                    if ((it as Part21SimpleInstance).entityName == "SURFACE_STYLE_TRANSPARENT") {
                        Part21SimpleInstance(it.id, "SURFACE_STYLE_RENDERING", listOf(Part21Value.Ref(7777)))
                    } else {
                        it
                    }
                }
            shouldThrow<ShapeExportException> { Ap242InstanceStyleBridge.verify(foreign, allowed) }
            shouldThrow<ShapeExportException> { Ap242InstanceStyleBridge.verify(out + out.first(), allowed) }
            val dangling =
                out.map {
                    if ((it as Part21SimpleInstance).entityName == "SURFACE_STYLE_TRANSPARENT") {
                        Part21SimpleInstance(it.id, it.entityName, listOf(Part21Value.Ref(7777)))
                    } else {
                        it
                    }
                }
            shouldThrow<ShapeExportException> { Ap242InstanceStyleBridge.verify(dangling, allowed) }
        }

        "more translucent styles than the limit are rejected" {
            val s = StyleScenario()
            val many =
                List(Ap242InstanceStyleBridge.MAX_INSTANCE_STYLES + 1) {
                    UsageStyle(s.u1, StepColour.rgba(0.0, 0.0, 1.0, 0.5))
                }
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.plan(many, s.plan, listOf(s.a))
            }.message shouldContain "too many instance colours"
        }

        "more than the limit of instance colours is rejected" {
            val s = StyleScenario()
            val many = List(Ap242InstanceStyleBridge.MAX_INSTANCE_STYLES + 1) { UsageStyle(s.u1, blue) }
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.plan(many, s.plan, listOf(s.a))
            }.message shouldContain "too many instance colours"
        }

        "ids near Int.MAX_VALUE fail closed instead of wrapping" {
            val s = StyleScenario()
            shouldThrow<ShapeExportException> {
                s.build(listOf(UsageStyle(s.u2, blue)), startId = Int.MAX_VALUE - 3)
            }
        }

        "a child without a solid is rejected" {
            val s = StyleScenario()
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.build(
                    planned = Ap242InstanceStyleBridge.plan(listOf(UsageStyle(s.u2, blue)), s.plan, listOf(s.a)),
                    refs = s.refs,
                    startId = 2000,
                    solidIdsOfChild = { emptyList() },
                    contextIdOf = { 900 },
                    childOf = { s.a },
                )
            }
        }

        "verify accepts the real output" {
            val s = StyleScenario()
            val out = s.build(listOf(UsageStyle(s.u2, blue)))
            Ap242InstanceStyleBridge.verify(out, setOf(s.refs[1].placementShapeId, s.refs[1].placementAxisId, 900, 101))
        }

        "verify rejects a foreign entity name (tamper)" {
            val s = StyleScenario()
            val out = s.build(listOf(UsageStyle(s.u2, blue))).toMutableList()
            out += Part21SimpleInstance(3000, "MAPPED_ITEM", emptyList())
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.verify(
                    out,
                    setOf(900, 101, s.refs[1].placementShapeId, s.refs[1].placementAxisId),
                )
            }.message shouldContain "MAPPED_ITEM"
        }

        "verify rejects a complex instance (tamper)" {
            val c = Part21ComplexInstance(3000, listOf(Part21InstancePart("STYLED_ITEM", emptyList())))
            shouldThrow<ShapeExportException> { Ap242InstanceStyleBridge.verify(listOf(c), emptySet()) }
        }

        "verify rejects a reference to an id that is not allowed (tamper)" {
            val s = StyleScenario()
            val out = s.build(listOf(UsageStyle(s.u2, blue)))
            // the real output refers to #900, #101 and the two placement ids; leave one of them out
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.verify(out, setOf(900, s.refs[1].placementAxisId, s.refs[1].placementShapeId))
            }.message shouldContain "#101"
        }

        "verify rejects a reference to an undefined id and a duplicate id (tamper)" {
            val dangling =
                Part21SimpleInstance(3000, "FILL_AREA_STYLE_COLOUR", listOf(Part21Value.Str(""), Part21Value.Ref(777)))
            shouldThrow<ShapeExportException> { Ap242InstanceStyleBridge.verify(listOf(dangling), emptySet()) }
            shouldThrow<ShapeExportException> {
                Ap242InstanceStyleBridge.verify(
                    listOf(dangling, dangling),
                    setOf(777),
                )
            }
        }

        "the allow-list of the OCCT extraction does not accept PRESENTATION_STYLE_BY_CONTEXT" {
            Ap242GeometryExtraction.STYLE_ENTITY_NAMES shouldNotContain "PRESENTATION_STYLE_BY_CONTEXT"
            Ap242InstanceStyleBridge.INSTANCE_STYLE_ENTITY_NAMES shouldContain "PRESENTATION_STYLE_BY_CONTEXT"
        }
    })
