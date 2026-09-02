package dev.kstep.shape

import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21RawDocument
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun testHeader(): Part21Header =
    Part21Header(fileName = "x.step", timestamp = "2026-09-02T00:00:00", schemaIdentifiers = listOf("S"))

private fun instance(
    id: Int,
    name: String,
    args: List<Part21Value> = emptyList(),
) = Part21SimpleInstance(id, name, args)

/** A minimal, hand-built OCCT-shaped document: SDR(#1) -> ABSR(#3) -> [MSB(#4) -> CLOSED_SHELL(#5)], CONTEXT(#7). */
private fun minimalValidDocument(): Part21RawDocument =
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

class Ap242GeometryExtractionTest :
    StringSpec({
        "no SHAPE_DEFINITION_REPRESENTATION throws ShapeExportException" {
            val doc = Part21RawDocument(testHeader(), listOf(instance(1, "CARTESIAN_POINT")))
            val ex = shouldThrow<ShapeExportException> { Ap242GeometryExtraction.extract(doc) }
            ex.message shouldBe "OCCT wrote no SHAPE_DEFINITION_REPRESENTATION (no shape/product link found)"
        }

        "two SHAPE_DEFINITION_REPRESENTATION instances throw ShapeExportException" {
            val doc =
                Part21RawDocument(
                    testHeader(),
                    listOf(
                        instance(1, "SHAPE_DEFINITION_REPRESENTATION", listOf(Part21Value.Ref(2), Part21Value.Ref(3))),
                        instance(4, "SHAPE_DEFINITION_REPRESENTATION", listOf(Part21Value.Ref(5), Part21Value.Ref(6))),
                        instance(3, "ADVANCED_BREP_SHAPE_REPRESENTATION"),
                        instance(6, "ADVANCED_BREP_SHAPE_REPRESENTATION"),
                    ),
                )
            shouldThrow<ShapeExportException> { Ap242GeometryExtraction.extract(doc) }
        }

        "used_representation pointing at a non-representation entity throws ShapeExportException" {
            val doc =
                Part21RawDocument(
                    testHeader(),
                    listOf(
                        instance(1, "SHAPE_DEFINITION_REPRESENTATION", listOf(Part21Value.Ref(2), Part21Value.Ref(3))),
                        instance(3, "CARTESIAN_POINT", listOf(Part21Value.Str(""), Part21Value.ListValue(emptyList()))),
                    ),
                )
            val ex = shouldThrow<ShapeExportException> { Ap242GeometryExtraction.extract(doc) }
            ex.message!! shouldContain "CARTESIAN_POINT"
        }

        "a geometry closure reaching a product-structure entity throws instead of exporting silently" {
            val doc =
                Part21RawDocument(
                    testHeader(),
                    listOf(
                        instance(1, "SHAPE_DEFINITION_REPRESENTATION", listOf(Part21Value.Ref(2), Part21Value.Ref(3))),
                        instance(
                            3,
                            "ADVANCED_BREP_SHAPE_REPRESENTATION",
                            listOf(
                                Part21Value.Str(""),
                                Part21Value.ListValue(listOf(Part21Value.Ref(4))),
                                Part21Value.Ref(7),
                            ),
                        ),
                        instance(4, "MANIFOLD_SOLID_BREP", listOf(Part21Value.Str(""), Part21Value.Ref(5))),
                        // Leak: the shell wrongly references a product-structure PRODUCT instance.
                        instance(5, "CLOSED_SHELL", listOf(Part21Value.Str(""), Part21Value.Ref(6))),
                        instance(6, "PRODUCT", listOf(Part21Value.Str("BRK-001"))),
                        instance(7, "GEOMETRIC_REPRESENTATION_CONTEXT", listOf(Part21Value.Num("3"))),
                    ),
                )
            val ex = shouldThrow<ShapeExportException> { Ap242GeometryExtraction.extract(doc) }
            ex.message!! shouldContain "#6"
        }

        "a closure without any MANIFOLD_SOLID_BREP throws ShapeExportException" {
            val doc =
                Part21RawDocument(
                    testHeader(),
                    listOf(
                        instance(1, "SHAPE_DEFINITION_REPRESENTATION", listOf(Part21Value.Ref(2), Part21Value.Ref(3))),
                        instance(
                            3,
                            "ADVANCED_BREP_SHAPE_REPRESENTATION",
                            listOf(Part21Value.Str(""), Part21Value.ListValue(emptyList()), Part21Value.Ref(7)),
                        ),
                        instance(7, "GEOMETRIC_REPRESENTATION_CONTEXT", listOf(Part21Value.Num("3"))),
                    ),
                )
            shouldThrow<ShapeExportException> { Ap242GeometryExtraction.extract(doc) }
        }

        "a well-formed subgraph is extracted with the correct root id and deterministic document order" {
            val doc = minimalValidDocument()
            val result = Ap242GeometryExtraction.extract(doc)

            result.rootRepresentationId shouldBe 3
            result.instances.map { it.id } shouldContainExactly listOf(3, 4, 5, 7)
        }

        "SHAPE_REPRESENTATION (not just ADVANCED_BREP_SHAPE_REPRESENTATION) is also an accepted root" {
            val doc =
                Part21RawDocument(
                    testHeader(),
                    listOf(
                        instance(1, "SHAPE_DEFINITION_REPRESENTATION", listOf(Part21Value.Ref(2), Part21Value.Ref(3))),
                        instance(
                            3,
                            "SHAPE_REPRESENTATION",
                            listOf(
                                Part21Value.Str(""),
                                Part21Value.ListValue(listOf(Part21Value.Ref(4))),
                                Part21Value.Ref(7),
                            ),
                        ),
                        instance(4, "MANIFOLD_SOLID_BREP", listOf(Part21Value.Str(""), Part21Value.Ref(5))),
                        instance(5, "CLOSED_SHELL", listOf(Part21Value.Str(""), Part21Value.ListValue(emptyList()))),
                        instance(7, "GEOMETRIC_REPRESENTATION_CONTEXT", listOf(Part21Value.Num("3"))),
                    ),
                )
            val result = Ap242GeometryExtraction.extract(doc)
            result.rootRepresentationId shouldBe 3
        }
    })
