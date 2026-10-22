package dev.kstep.express.validation

/**
 * Which hard-wired schema functions the expression builder and codegen may translate (ADR-0023).
 * kSTEP has no general EXPRESS `FUNCTION` interpreter; the functions of the AP242 MIM schema that
 * the V1 excerpt's rules and `DERIVE` clauses use are implemented natively and named explicitly.
 */
enum class SchemaFunctionIntrinsicSet {
    /** No schema function is recognised (every function call stays unsupported). */
    NONE,

    /** `get_name_value`, `get_description_value`, `get_id_value`, `acyclic_product_definition_relationship`. */
    AP242,
}

/** The fixed registry of AP242 schema-function intrinsics. */
object SchemaFunctionIntrinsics {
    /** `get_*_value(SELF)`: the attribute entity and the role attribute that references the target. */
    data class AttributeValueFunction(
        val functionName: String,
        val attributeEntity: String,
        val roleAttribute: String,
    )

    val ATTRIBUTE_VALUE_FUNCTIONS: Map<String, AttributeValueFunction> =
        listOf(
            AttributeValueFunction("get_name_value", "name_attribute", "named_item"),
            AttributeValueFunction("get_description_value", "description_attribute", "described_item"),
            AttributeValueFunction("get_id_value", "id_attribute", "identified_item"),
        ).associateBy { it.functionName }

    const val ACYCLIC_FUNCTION = "acyclic_product_definition_relationship"

    /** Names of all intrinsics (lower case); a schema declaring one of these itself is refused by codegen. */
    val ALL_NAMES: Set<String> = ATTRIBUTE_VALUE_FUNCTIONS.keys + ACYCLIC_FUNCTION

    /**
     * EXPRESS `get_name_value(v)` and its siblings: `attribute_value` of the single `<attributeEntity>`
     * that references [target] through [roleAttribute]; `null` (EXPRESS `?`) when there are zero or
     * several such instances. Throws [IndeterminateDerivedValueException] when the population is
     * incomplete for [target]. The AP242 functions return `label`/`text`/`identifier`, all STRING.
     */
    fun getAttributeValue(
        scope: DerivedValueScope,
        target: Any,
        attributeEntity: String,
        roleAttribute: String,
    ): String? {
        if (scope.describe(target) == null) {
            throw WhereRuleEvaluationException(
                "the target of ${clipForMessage(
                    attributeEntity,
                )}.${clipForMessage(roleAttribute)} is not part of the " +
                    "population; a population-dependent derived value can only be read for a member of it",
            )
        }
        if (!scope.isComplete(target)) {
            throw IndeterminateDerivedValueException(
                attributeEntity,
                roleAttribute,
                "an instance referencing the target could not be reconstructed",
            )
        }
        val usages = scope.usedIn(target, attributeEntity, roleAttribute)
        if (usages.size != 1) return null
        val value =
            scope.describe(usages[0].referrer)?.attributeValues?.get("attribute_value")
                ?: throw WhereRuleEvaluationException(
                    "'${clipForMessage(attributeEntity)}' instance has no attribute_value in its description",
                )
        return when (value) {
            is WhereRuleValue.StringValue -> value.value
            else -> throw WhereRuleEvaluationException(
                "'${clipForMessage(attributeEntity)}.attribute_value' is not a string",
            )
        }
    }
}
