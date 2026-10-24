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
        inst(FACE, "ADVANCED_FACE", str(""), list(), ref(9), Part21Value.Enumeration("T")),
        inst(9, "PLANE", str(""), Part21Value.Unset),
        inst(OTHER_SOLID, "MANIFOLD_SOLID_BREP", str(""), ref(21)),
        inst(21, "CLOSED_SHELL", str(""), list(ref(OTHER_FACE))),
        inst(OTHER_FACE, "ADVANCED_FACE", str(""), list(), ref(9), Part21Value.Enumeration("T")),
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
    })
