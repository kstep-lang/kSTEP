package dev.kstep.shape

import dev.kstep.step21.Part21Value
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The four handwritten bridge-entity builders [Ap242ShapeExporter.export] wires together
 * (`PRODUCT_DEFINITION_SHAPE`, `SHAPE_DEFINITION_REPRESENTATION`,
 * `APPLICATION_PROTOCOL_DEFINITION`, `PRODUCT_RELATED_PRODUCT_CATEGORY` — see ADR-0009's
 * "kstep-shape: ShapeAssignment + Ap242ShapeExporter" section for why these stay handwritten
 * rather than codegen-typed). Exercised directly here — the only production caller,
 * [Ap242ShapeExporter.export], is reachable only through its single OCCT-gated round-trip test
 * case (`Ap242ShapeExportRoundtripTest`), so this coverage is what actually runs on a machine
 * without the native OCCT bridge.
 */
class Ap242ShapeBridgeEntitiesTest :
    StringSpec({
        "productDefinitionShape builds the (name, unset description, product_definition ref) shape" {
            val instance =
                Ap242ShapeBridgeEntities.productDefinitionShape(
                    id = 42,
                    name = "bracket solid",
                    productDefinitionId = 9,
                )

            instance.id shouldBe 42
            instance.entityName shouldBe "PRODUCT_DEFINITION_SHAPE"
            instance.args shouldBe
                listOf(Part21Value.Str("bracket solid"), Part21Value.Unset, Part21Value.Ref(9))
        }

        "shapeDefinitionRepresentation builds the (definition, used_representation) ref pair" {
            val instance =
                Ap242ShapeBridgeEntities.shapeDefinitionRepresentation(
                    id = 43,
                    productDefinitionShapeId = 42,
                    representationId = 17,
                )

            instance.id shouldBe 43
            instance.entityName shouldBe "SHAPE_DEFINITION_REPRESENTATION"
            instance.args shouldBe listOf(Part21Value.Ref(42), Part21Value.Ref(17))
        }

        "applicationProtocolDefinition builds the fixed OCCT-mirroring status/schema/year args plus the app ref" {
            val instance = Ap242ShapeBridgeEntities.applicationProtocolDefinition(id = 44, applicationContextId = 7)

            instance.id shouldBe 44
            instance.entityName shouldBe "APPLICATION_PROTOCOL_DEFINITION"
            instance.args shouldBe
                listOf(
                    Part21Value.Str("international standard"),
                    Part21Value.Str("ap242_managed_model_based_3d_engineering"),
                    Part21Value.Num("2013"),
                    Part21Value.Ref(7),
                )
        }

        "productRelatedProductCategory builds the fixed name/unset-description args plus a single products entry" {
            val instance = Ap242ShapeBridgeEntities.productRelatedProductCategory(id = 45, productId = 9)

            instance.id shouldBe 45
            instance.entityName shouldBe "PRODUCT_RELATED_PRODUCT_CATEGORY"
            instance.args shouldBe
                listOf(
                    Part21Value.Str("part"),
                    Part21Value.Unset,
                    Part21Value.ListValue(listOf(Part21Value.Ref(9))),
                )
        }
    })
