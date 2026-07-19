package dev.kstep.step21

/** The shape of one positional argument in a Part-21 `#N=ENTITY_NAME(...)` statement. */
internal enum class Part21ArgKind {
    STRING,
    REFERENCE,
}

/**
 * Single source of truth for the entity-name spelling, positional-argument order/kind, and
 * (for `REFERENCE` positions) required target entity kind of each of the six V1 `kstep-core`
 * types. Both [Part21Writer]'s per-type serialize dispatch and [Part21Reader]'s pass-1/pass-2
 * checks read this table — neither hardcodes an entity name or argument order independently,
 * so the two directions cannot silently drift apart.
 *
 * Attribute order is taken from `ap242-subset.exp` (kstep-tests fixture), not
 * `ap242-v1-entities.exp` — the latter is structurally incompatible with `kstep-core`'s
 * hand-authored shapes for two of the six entities (the real `approval` has no
 * `authorized_by`; the real `next_assembly_usage_occurrence` has zero own attributes, being a
 * pure `SUBTYPE OF (assembly_component_usage)`). `ap242-subset.exp`'s declaration order
 * matches `kstep-core`'s Kotlin primary-constructor parameter order exactly for all six types
 * — verified field-by-field, not assumed.
 *
 * Declaration order below is deliberately forward-clean: every [referenceTargets] entry only
 * ever points at an already-declared earlier constant, because the six types form a strict,
 * acyclic reference DAG (nothing ever references [NEXT_ASSEMBLY_USAGE_OCCURRENCE] or
 * [APPROVAL] back).
 */
internal enum class Part21EntityKind(
    val entityName: String,
    val argKinds: List<Part21ArgKind>,
    val referenceTargets: Map<Int, Part21EntityKind> = emptyMap(),
) {
    PRODUCT("PRODUCT", listOf(Part21ArgKind.STRING, Part21ArgKind.STRING, Part21ArgKind.STRING)),
    PERSON_AND_ORGANIZATION(
        "PERSON_AND_ORGANIZATION",
        listOf(Part21ArgKind.STRING, Part21ArgKind.STRING),
    ),
    PRODUCT_DEFINITION_FORMATION(
        "PRODUCT_DEFINITION_FORMATION",
        listOf(Part21ArgKind.STRING, Part21ArgKind.STRING, Part21ArgKind.REFERENCE),
        mapOf(2 to PRODUCT),
    ),
    PRODUCT_DEFINITION(
        "PRODUCT_DEFINITION",
        listOf(Part21ArgKind.STRING, Part21ArgKind.STRING, Part21ArgKind.REFERENCE),
        mapOf(2 to PRODUCT_DEFINITION_FORMATION),
    ),
    NEXT_ASSEMBLY_USAGE_OCCURRENCE(
        "NEXT_ASSEMBLY_USAGE_OCCURRENCE",
        listOf(
            Part21ArgKind.STRING,
            Part21ArgKind.STRING,
            Part21ArgKind.REFERENCE,
            Part21ArgKind.REFERENCE,
            Part21ArgKind.STRING,
        ),
        mapOf(2 to PRODUCT_DEFINITION, 3 to PRODUCT_DEFINITION),
    ),
    APPROVAL(
        "APPROVAL",
        listOf(Part21ArgKind.STRING, Part21ArgKind.STRING, Part21ArgKind.REFERENCE),
        mapOf(2 to PERSON_AND_ORGANIZATION),
    ),
    ;

    companion object {
        val byEntityName: Map<String, Part21EntityKind> = entries.associateBy { it.entityName }
    }
}
