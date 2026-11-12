package dev.kstep.shape

import dev.kstep.step21.Part21ComplexInstance
import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21InstancePart
import dev.kstep.step21.Part21RawDocument
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun header(): Part21Header =
    Part21Header(fileName = "x.step", timestamp = "2026-09-02T00:00:00", schemaIdentifiers = listOf("S"))

private fun inst(
    id: Int,
    name: String,
    vararg args: Part21Value,
) = Part21SimpleInstance(id, name, args.toList())

private fun ref(id: Int) = Part21Value.Ref(id)

private fun str(text: String) = Part21Value.Str(text)

private fun list(vararg items: Part21Value) = Part21Value.ListValue(items.toList())

private const val SOLID = 4
private const val FACE = 8
private const val OTHER_SOLID = 20
private const val OTHER_FACE = 22
private const val EDGE = 53
private const val OTHER_EDGE = 63
private const val CONTEXT = 7
private const val PART_STYLE = 11
private const val OVERRIDE = 15

/**
 * SDR(#1) -> ABSR(#3) -> MSB(#4) -> shell(#5) -> ADVANCED_FACE(#8), and a second solid #20 with its own face #22.
 * Product structure #2/#6 hangs off the SDR only.
 */
private fun geometry(): List<Part21EntityInstance> =
    listOf(
        inst(1, "SHAPE_DEFINITION_REPRESENTATION", ref(2), ref(3)),
        inst(2, "PRODUCT_DEFINITION_SHAPE", str(""), Part21Value.Unset, ref(6)),
        inst(3, "ADVANCED_BREP_SHAPE_REPRESENTATION", str(""), list(ref(SOLID), ref(OTHER_SOLID)), ref(CONTEXT)),
        inst(SOLID, "MANIFOLD_SOLID_BREP", str(""), ref(5)),
        inst(5, "CLOSED_SHELL", str(""), list(ref(FACE))),
        inst(6, "PRODUCT_DEFINITION", str("x")),
        inst(CONTEXT, "GEOMETRIC_REPRESENTATION_CONTEXT", Part21Value.Num("3")),
        inst(FACE, "ADVANCED_FACE", str(""), list(ref(50)), ref(9), Part21Value.Enumeration("T")),
        inst(50, "FACE_OUTER_BOUND", str(""), ref(51), Part21Value.Enumeration("T")),
        inst(51, "EDGE_LOOP", str(""), list(ref(52))),
        inst(
            52,
            "ORIENTED_EDGE",
            str(""),
            Part21Value.Derived,
            Part21Value.Derived,
            ref(EDGE),
            Part21Value.Enumeration("F"),
        ),
        inst(EDGE, "EDGE_CURVE", str(""), ref(54), ref(54), ref(9), Part21Value.Enumeration("T")),
        inst(54, "VERTEX_POINT", str(""), ref(9)),
        inst(9, "PLANE", str(""), Part21Value.Unset),
        inst(OTHER_SOLID, "MANIFOLD_SOLID_BREP", str(""), ref(21)),
        inst(21, "CLOSED_SHELL", str(""), list(ref(OTHER_FACE))),
        inst(OTHER_FACE, "ADVANCED_FACE", str(""), list(ref(60)), ref(9), Part21Value.Enumeration("T")),
        inst(60, "FACE_OUTER_BOUND", str(""), ref(61), Part21Value.Enumeration("T")),
        inst(61, "EDGE_LOOP", str(""), list(ref(62))),
        inst(
            62,
            "ORIENTED_EDGE",
            str(""),
            Part21Value.Derived,
            Part21Value.Derived,
            ref(OTHER_EDGE),
            Part21Value.Enumeration("F"),
        ),
        inst(OTHER_EDGE, "EDGE_CURVE", str(""), ref(54), ref(54), ref(9), Part21Value.Enumeration("T")),
    )

/** The colour chain every styled item points at: PSA #12 -> COLOUR_RGB #13. */
private fun colour(): List<Part21EntityInstance> =
    listOf(
        inst(12, "PRESENTATION_STYLE_ASSIGNMENT", list(ref(13))),
        inst(13, "COLOUR_RGB", str(""), Part21Value.Num("1."), Part21Value.Num("0.5"), Part21Value.Num("0.")),
    )

private fun partStyle(): Part21EntityInstance = inst(PART_STYLE, "STYLED_ITEM", str("color"), list(ref(12)), ref(SOLID))

private fun override(
    id: Int = OVERRIDE,
    target: Int = FACE,
    overridden: Part21Value = ref(PART_STYLE),
) = inst(id, "OVER_RIDING_STYLED_ITEM", str("overriding color"), list(ref(12)), ref(target), overridden)

private fun presentation(vararg items: Int) =
    inst(
        10,
        "MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION",
        str(""),
        list(*items.map { ref(it) }.toTypedArray()),
        ref(CONTEXT),
    )

private fun document(vararg style: Part21EntityInstance): Part21RawDocument =
    Part21RawDocument(header(), (geometry() + colour() + style.toList()).sortedBy { it.id })

private fun refused(vararg style: Part21EntityInstance): String =
    shouldThrow<ShapeExportException> { Ap242GeometryExtraction.extractStyled(document(*style)) }.message.orEmpty()

class Ap242GeometryExtractionStyledTest :
    StringSpec({
        "a part colour overridden on one face is merged with both styled items" {
            val result =
                Ap242GeometryExtraction.extractStyled(
                    document(presentation(PART_STYLE, OVERRIDE), partStyle(), override()),
                )

            val ids = result.instances.map { it.id }
            ids.filter { it == PART_STYLE || it == OVERRIDE } shouldBe listOf(PART_STYLE, OVERRIDE)
            ids shouldBe ids.sorted()
            (10 in ids) shouldBe true
            (6 in ids) shouldBe false
        }

        "an overriding item whose overridden style is a colour, a face, a solid or product structure is refused" {
            for (bad in listOf(13, FACE, SOLID)) {
                refused(partStyle(), override(overridden = ref(bad))) shouldContain "not a styled item"
            }
            // product structure is already refused by the closure walk, before the chain is looked at
            refused(partStyle(), override(overridden = ref(6))) shouldContain "refusing to merge it"
        }

        "an overriding item whose overridden style is not a reference is refused" {
            refused(partStyle(), override(overridden = Part21Value.Unset)) shouldContain "not a styled item"
        }

        "an overriding item that overrides itself is refused" {
            refused(partStyle(), override(overridden = ref(OVERRIDE))) shouldContain "loops"
        }

        "two overriding items overriding each other are refused" {
            refused(
                partStyle(),
                override(id = 15, overridden = ref(16)),
                override(id = 16, overridden = ref(15)),
            ) shouldContain "loops"
        }

        "an over-riding chain within the limit that ends at a styled solid is accepted" {
            val chain =
                (0 until 3).map { override(id = 100 + it, overridden = ref(if (it == 2) PART_STYLE else 101 + it)) }
            Ap242GeometryExtraction
                .extractStyled(document(partStyle(), *chain.toTypedArray()))
                .instances
                .map { it.id }
                .containsAll(listOf(100, 101, 102)) shouldBe true
        }

        "an over-riding chain longer than the limit is refused" {
            val chain =
                (0 until 20).map { override(id = 100 + it, overridden = ref(if (it == 19) PART_STYLE else 101 + it)) }
            refused(partStyle(), *chain.toTypedArray()) shouldContain "maximum length"
        }

        "an overriding item referencing an undefined instance is refused" {
            refused(partStyle(), override(overridden = ref(999))) shouldContain "is not defined"
        }

        "an overriding face that is not a face of the overridden solid is refused" {
            refused(partStyle(), override(target = OTHER_FACE)) shouldContain "does not contain its face"
        }

        "an overriding item attached to a solid is refused" {
            refused(partStyle(), override(target = SOLID)) shouldContain "not attached to a face"
        }

        "an overriding item attached to product structure is refused" {
            refused(partStyle(), override(target = 6)) shouldContain "refusing to merge it"
        }

        "an overriding item whose chain ends at a styled face instead of a solid is refused" {
            val onFace = inst(PART_STYLE, "STYLED_ITEM", str("color"), list(ref(12)), ref(FACE))
            refused(onFace, override()) shouldContain "does not end at a styled solid"
        }

        "a complex instance containing OVER_RIDING_STYLED_ITEM is refused" {
            val complex =
                Part21ComplexInstance(
                    OVERRIDE,
                    listOf(
                        Part21InstancePart("OVER_RIDING_STYLED_ITEM", emptyList()),
                        Part21InstancePart("STYLED_ITEM", emptyList()),
                    ),
                )
            refused(partStyle(), complex) shouldContain "complex instance"
        }

        "an overriding item with three arguments is refused" {
            val three = inst(OVERRIDE, "OVER_RIDING_STYLED_ITEM", str("x"), list(ref(12)), ref(FACE))
            refused(partStyle(), three) shouldContain "expected 4"
        }

        "a plain styled item with four arguments is refused" {
            val four = inst(PART_STYLE, "STYLED_ITEM", str("x"), list(ref(12)), ref(SOLID), ref(SOLID))
            refused(four) shouldContain "expected 3"
        }

        "a presentation representation listing a non-styled item is refused" {
            refused(presentation(PART_STYLE, 13), partStyle()) shouldContain "not a styled item"
        }

        "an unknown entity among the style entities is still refused" {
            for (name in listOf("DRAUGHTING_MODEL", "INVISIBILITY", "CONTEXT_DEPENDENT_OVER_RIDING_STYLED_ITEM")) {
                val unknown = inst(30, name, list(ref(PART_STYLE)))
                val message = refused(partStyle(), unknown, presentation(PART_STYLE, 30))
                message shouldContain "refusing to merge it"
            }
        }

        "a STYLED_ITEM attached to product structure is still refused" {
            refused(inst(PART_STYLE, "STYLED_ITEM", str("color"), list(ref(12)), ref(6))) shouldContain
                "refusing to merge it"
        }

        "a layer on a non-face is still refused" {
            refused(inst(40, "PRESENTATION_LAYER_ASSIGNMENT", str("L"), str("visible"), list(ref(6)))) shouldContain
                "refusing to merge it"
        }

        "a layer on a face is accepted" {
            val layer = inst(40, "PRESENTATION_LAYER_ASSIGNMENT", str("L"), str("visible"), list(ref(FACE)))
            Ap242GeometryExtraction
                .extractStyled(document(partStyle(), override(), layer))
                .instances
                .any { it.id == 40 } shouldBe true
        }

        "renumbering the merged style subgraph keeps both references of an overriding item consistent" {
            val subgraph =
                Ap242GeometryExtraction.extractStyled(
                    document(presentation(PART_STYLE, OVERRIDE), partStyle(), override()),
                )
            val offset = 1000

            val shifted = Part21Document(header(), subgraph.instances).renumbered(offset)

            val overriding = shifted.instances.single { it.id == OVERRIDE + offset } as Part21SimpleInstance
            (overriding.args[2] as Part21Value.Ref).id shouldBe FACE + offset
            (overriding.args[3] as Part21Value.Ref).id shouldBe PART_STYLE + offset
            val defined = shifted.instances.map { it.id }.toSet()
            shifted.instances.flatMap { it.referencedIds() }.all { it in defined } shouldBe true
            val reread = Part21Reader.readRawDocument(shifted.render(), Part21ReadMode.TOLERANT)
            reread.instances.map { it.id } shouldBe shifted.instances.map { it.id }
        }

        // ---- ADR-0042: transparency and edge colours, hand-written after the entity shapes measured with OCCT 7.9.3 ----

        "a part colour with transparency (rendering properties and SURFACE_STYLE_TRANSPARENT) is accepted" {
            val result = Ap242GeometryExtraction.extractStyled(document(*transparentChain()))
            result.instances.map { it.id }.containsAll(listOf(70, 71, 72, 73, 74, 75)) shouldBe true
        }

        "a transparency on an overriding face item is accepted" {
            val chain = transparentChain(target = FACE, overriding = true)
            val result = Ap242GeometryExtraction.extractStyled(document(partStyle(), *chain))
            result.instances.map { it.id }.containsAll(listOf(71, 75)) shouldBe true
        }

        "SURFACE_STYLE_TRANSPARENT outside 0..1, not a number or with two arguments is refused" {
            for (bad in listOf("1.5", "-0.1", "NaN", "abc")) {
                refused(
                    *transparentChain(transparency = inst(71, "SURFACE_STYLE_TRANSPARENT", Part21Value.Num(bad))),
                ) shouldContain
                    "0..1"
            }
            val two = inst(71, "SURFACE_STYLE_TRANSPARENT", Part21Value.Num("0.5"), Part21Value.Num("0.5"))
            refused(*transparentChain(transparency = two)) shouldContain "0..1"
            val reference = inst(71, "SURFACE_STYLE_TRANSPARENT", ref(13))
            refused(*transparentChain(transparency = reference)) shouldContain "0..1"
        }

        "SURFACE_STYLE_RENDERING_WITH_PROPERTIES listing something else than a transparency is refused" {
            val rendering =
                inst(
                    70,
                    "SURFACE_STYLE_RENDERING_WITH_PROPERTIES",
                    Part21Value.Enumeration("NORMAL_SHADING"),
                    ref(13),
                    list(ref(13)),
                )
            refused(*transparentChain(rendering = rendering)) shouldContain "not SURFACE_STYLE_TRANSPARENT"
            val shortArgs =
                inst(70, "SURFACE_STYLE_RENDERING_WITH_PROPERTIES", Part21Value.Enumeration("NORMAL_SHADING"), ref(13))
            refused(*transparentChain(rendering = shortArgs)) shouldContain "measured shape"
        }

        "an edge colour as a plain styled item on an edge curve is accepted" {
            val result = Ap242GeometryExtraction.extractStyled(document(*edgeColour(), edgeItem()))
            result.instances.map { it.id }.containsAll(listOf(80, 81, 82, 83)) shouldBe true
        }

        "an over-riding edge colour on a coloured solid is accepted and the edge belongs to the solid" {
            val result =
                Ap242GeometryExtraction.extractStyled(
                    document(presentation(PART_STYLE, 84), partStyle(), *edgeColour(), edgeOverride()),
                )
            result.instances.map { it.id }.contains(84) shouldBe true
        }

        "an over-riding edge colour whose edge lies in another solid is refused" {
            refused(partStyle(), *edgeColour(), edgeOverride(target = OTHER_EDGE)) shouldContain
                "does not contain its edge"
        }

        "an over-riding edge colour that is not on an edge curve is refused" {
            for (bad in listOf(FACE, SOLID)) {
                refused(partStyle(), *edgeColour(), edgeOverride(target = bad)) shouldContain "not attached to an edge"
            }
        }

        "a curve style on a face or a solid is refused" {
            for (bad in listOf(FACE, SOLID)) {
                refused(*edgeColour(), edgeItem(target = bad)) shouldContain "not attached to an edge"
            }
        }

        "a surface style on an edge curve is refused" {
            refused(inst(PART_STYLE, "STYLED_ITEM", str("color"), list(ref(12)), ref(EDGE))) shouldContain
                "neither a solid nor a face"
        }

        "a style mixing curve and surface styles in one styled item is refused" {
            val usage = inst(90, "SURFACE_STYLE_USAGE", Part21Value.Enumeration("BOTH"), ref(13))
            val mixed = inst(86, "PRESENTATION_STYLE_ASSIGNMENT", list(ref(80), ref(90)))
            val item = inst(87, "STYLED_ITEM", str("color"), list(ref(86)), ref(EDGE))
            refused(*edgeColour(), usage, mixed, item) shouldContain "mixes curve and surface styles"
        }

        "an edge styled item outside the exported geometry is refused" {
            refused(*edgeColour(), edgeItem(target = 6)) shouldContain "refusing to merge it"
            refused(*edgeColour(), edgeItem(target = 999)) shouldContain "not defined"
        }

        "a curve style with an unmeasured font, width or colour is refused" {
            refused(*edgeColour(fontName = "CURVE_STYLE_FONT"), edgeItem()) shouldContain "refusing to merge it"
            refused(*edgeColour(width = Part21Value.Num("0.1")), edgeItem()) shouldContain "measured shape"
            val wrongKeyword = Part21Value.Typed("LENGTH_MEASURE", listOf(Part21Value.Num("0.1")))
            refused(*edgeColour(width = wrongKeyword), edgeItem()) shouldContain "measured shape"
            val zeroWidth = Part21Value.Typed("POSITIVE_LENGTH_MEASURE", listOf(Part21Value.Num("0.")))
            refused(*edgeColour(width = zeroWidth), edgeItem()) shouldContain "measured shape"
            refused(*edgeColour(colourId = 12), edgeItem()) shouldContain "measured shape"
        }

        "an unmeasured curve entity among the style entities is refused" {
            for (name in listOf("CURVE_STYLE_FONT_PATTERN", "SURFACE_STYLE_BOUNDARY", "CURVE_STYLE_FONT")) {
                refused(*edgeColour(fontName = name), edgeItem()) shouldContain "refusing to merge it"
            }
        }

        "an over-riding edge item that loops is refused" {
            refused(partStyle(), *edgeColour(), edgeOverride(id = 84, overridden = ref(84))) shouldContain "loops"
        }

        "the OCCT measured edge colour shape (typed width) survives a Part 21 text round trip" {
            val text = Part21Document(header(), edgeColour().toList()).render()
            text shouldContain "POSITIVE_LENGTH_MEASURE(0.1)"
            val reread = Part21Reader.readRawDocument(text, Part21ReadMode.TOLERANT)
            val curveStyle = reread.instances.single { it.id == 80 } as Part21SimpleInstance
            curveStyle.args[2] shouldBe Part21Value.Typed("POSITIVE_LENGTH_MEASURE", listOf(Part21Value.Num("0.1")))
            curveStyle.referencedIds().sorted() shouldBe listOf(81, 82)
        }
    })

/**
 * A transparent surface colour reachable from a styled item #75 on [target]: usage #73 -> side style #72 -> rendering #70
 * -> transparency #71, in the shape OCCT writes (the colour #13 of [colour] is shared). Parts are replaceable so that
 * tests can break exactly one of them.
 */
private fun transparentChain(
    rendering: Part21EntityInstance =
        inst(
            70,
            "SURFACE_STYLE_RENDERING_WITH_PROPERTIES",
            Part21Value.Enumeration("NORMAL_SHADING"),
            ref(13),
            list(ref(71)),
        ),
    transparency: Part21EntityInstance = inst(71, "SURFACE_STYLE_TRANSPARENT", Part21Value.Num("0.5")),
    target: Int = SOLID,
    overriding: Boolean = false,
): Array<Part21EntityInstance> =
    arrayOf(
        rendering,
        transparency,
        inst(72, "SURFACE_SIDE_STYLE", str(""), list(ref(70))),
        inst(73, "SURFACE_STYLE_USAGE", Part21Value.Enumeration("BOTH"), ref(72)),
        inst(74, "PRESENTATION_STYLE_ASSIGNMENT", list(ref(73))),
        if (overriding) {
            inst(75, "OVER_RIDING_STYLED_ITEM", str("overriding color"), list(ref(74)), ref(target), ref(PART_STYLE))
        } else {
            inst(75, "STYLED_ITEM", str("color"), list(ref(74)), ref(target))
        },
    )

/** A measured edge colour: CURVE_STYLE #80 (font #81, typed width, colour), PSA #83. Red is a predefined colour #82. */
private fun edgeColour(
    fontName: String = "DRAUGHTING_PRE_DEFINED_CURVE_FONT",
    width: Part21Value = Part21Value.Typed("POSITIVE_LENGTH_MEASURE", listOf(Part21Value.Num("0.1"))),
    colourId: Int = 82,
): Array<Part21EntityInstance> =
    arrayOf(
        inst(80, "CURVE_STYLE", str(""), ref(81), width, ref(colourId)),
        inst(81, fontName, str("continuous")),
        inst(82, "DRAUGHTING_PRE_DEFINED_COLOUR", str("red")),
        inst(83, "PRESENTATION_STYLE_ASSIGNMENT", list(ref(80))),
    )

private fun edgeItem(
    id: Int = 85,
    target: Int = EDGE,
) = inst(id, "STYLED_ITEM", str("color"), list(ref(83)), ref(target))

private fun edgeOverride(
    id: Int = 84,
    target: Int = EDGE,
    overridden: Part21Value = ref(PART_STYLE),
) = inst(id, "OVER_RIDING_STYLED_ITEM", str("overriding color"), list(ref(83)), ref(target), overridden)
