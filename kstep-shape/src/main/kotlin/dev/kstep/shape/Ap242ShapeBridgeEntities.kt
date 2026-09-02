package dev.kstep.shape

import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value

/**
 * The handwritten bridge/product-structure-completion instances [Ap242ShapeExporter] adds
 * around kSTEP's own `PRODUCT_DEFINITION` and the geometry subgraph [Ap242GeometryExtraction]
 * kept. Handwritten (not codegen-typed `kstep-core` entities) because typing them would need
 * SELECT-type support in `dev.kstep.express.codegen.TypeMapping`, which V1 does not have — see
 * ADR-0009's Folge-Wellen table, Welle 4d.
 */
internal object Ap242ShapeBridgeEntities {
    /**
     * `PRODUCT_DEFINITION_SHAPE(name : label, description : OPTIONAL text,
     * definition : characterized_definition)`. The real `definition` attribute is a SELECT type;
     * this wave only ever emits the `product_definition` member of that SELECT.
     */
    fun productDefinitionShape(
        id: Int,
        name: String,
        productDefinitionId: Int,
    ): Part21SimpleInstance =
        Part21SimpleInstance(
            id = id,
            entityName = "PRODUCT_DEFINITION_SHAPE",
            args =
                listOf(
                    Part21Value.Str(name),
                    Part21Value.Unset,
                    Part21Value.Ref(productDefinitionId),
                ),
        )

    /** `SHAPE_DEFINITION_REPRESENTATION(definition : property_definition, used_representation : representation)`. */
    fun shapeDefinitionRepresentation(
        id: Int,
        productDefinitionShapeId: Int,
        representationId: Int,
    ): Part21SimpleInstance =
        Part21SimpleInstance(
            id = id,
            entityName = "SHAPE_DEFINITION_REPRESENTATION",
            args = listOf(Part21Value.Ref(productDefinitionShapeId), Part21Value.Ref(representationId)),
        )

    /**
     * `APPLICATION_PROTOCOL_DEFINITION(status, application_interpreted_model_schema_name,
     * application_protocol_year, application)`. Values mirror OCCT's own AP242 output.
     */
    fun applicationProtocolDefinition(
        id: Int,
        applicationContextId: Int,
    ): Part21SimpleInstance =
        Part21SimpleInstance(
            id = id,
            entityName = "APPLICATION_PROTOCOL_DEFINITION",
            args =
                listOf(
                    Part21Value.Str("international standard"),
                    Part21Value.Str("ap242_managed_model_based_3d_engineering"),
                    Part21Value.Num("2013"),
                    Part21Value.Ref(applicationContextId),
                ),
        )

    /**
     * `PRODUCT_RELATED_PRODUCT_CATEGORY(name, description : OPTIONAL, products : SET[1:?] OF product)`.
     * Without this instance, common STEP viewers do not treat the product as a part.
     */
    fun productRelatedProductCategory(
        id: Int,
        productId: Int,
    ): Part21SimpleInstance =
        Part21SimpleInstance(
            id = id,
            entityName = "PRODUCT_RELATED_PRODUCT_CATEGORY",
            args =
                listOf(
                    Part21Value.Str("part"),
                    Part21Value.Unset,
                    Part21Value.ListValue(listOf(Part21Value.Ref(productId))),
                ),
        )
}
