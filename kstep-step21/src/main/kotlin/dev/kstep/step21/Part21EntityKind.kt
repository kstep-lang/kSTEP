package dev.kstep.step21

/** The shape of one positional argument in a Part-21 `#N=ENTITY_NAME(...)` statement. */
internal enum class Part21ArgKind {
    STRING,
    REFERENCE,
    STRING_LIST,
    REFERENCE_LIST,
}

/**
 * One positional argument's full contract: its [kind], and whether the Part-21 `$` (unset)
 * token is legal there — mirroring the underlying EXPRESS attribute's `OPTIONAL`-ness. A
 * non-`optional` position that receives `$` (or vice versa) is a [Part21SyntaxException] from
 * [Part21Tokenizer], not something deferred to [Part21GraphResolver].
 */
internal data class Part21ArgSpec(
    val kind: Part21ArgKind,
    val optional: Boolean = false,
)

/**
 * Single source of truth for the entity-name spelling, positional-argument order/kind/
 * optionality, and (for `REFERENCE`/`REFERENCE_LIST` positions) required target entity kind of
 * each of the twelve `kstep-core` AP242 V1 types (kSTEP M2 Welle 10 — six V1 entities plus six
 * support entities the codegen-generated types now require: `application_context`,
 * `product_context`, `product_definition_context`, `approval_status`, `person`, `organization`).
 * Both [Part21Writer]'s per-type serialize dispatch and [Part21Reader]'s pass-1/pass-2 checks
 * read this table — neither hardcodes an entity name or argument order independently, so the
 * two directions cannot silently drift apart.
 *
 * Attribute order is taken directly from the real, codegen-generated
 * `dev.kstep.generated.ap242v1.*` primary-constructor parameter order (verified against
 * `ExpressKotlinCodeGenerator`'s own emission — attributes are emitted in
 * `ResolvedEntity.flattenedAttributes` order, i.e. real EXPRESS declaration order,
 * supertype-most-general-first), not `ap242-subset.exp` (kstep-tests fixture) as the pre-Welle-10
 * table was — `kstep-core`'s twelve entity types are now the codegen-generated shapes
 * themselves, so there is exactly one authoritative attribute order to track.
 *
 * Declaration order below is deliberately forward-clean: every [referenceTargets] entry only
 * ever points at an already-declared earlier constant, because the twelve types form a strict,
 * acyclic reference DAG. [PRODUCT_DEFINITION_FORMATION] is declared before [PRODUCT_DEFINITION]
 * specifically because the latter references the former — note this differs from the generated
 * Kotlin *source file*'s own (irrelevant, since Kotlin classes may forward-reference freely)
 * textual class order, which declares `ProductDefinition` before `ProductDefinitionFormation`.
 */
internal enum class Part21EntityKind(
    val entityName: String,
    val args: List<Part21ArgSpec>,
    val referenceTargets: Map<Int, Part21EntityKind> = emptyMap(),
) {
    APPLICATION_CONTEXT(
        "APPLICATION_CONTEXT",
        listOf(Part21ArgSpec(Part21ArgKind.STRING)), // application
    ),
    PRODUCT_CONTEXT(
        "PRODUCT_CONTEXT",
        listOf(
            Part21ArgSpec(Part21ArgKind.STRING), // name
            Part21ArgSpec(Part21ArgKind.REFERENCE), // frame_of_reference
            Part21ArgSpec(Part21ArgKind.STRING), // discipline_type
        ),
        mapOf(1 to APPLICATION_CONTEXT),
    ),
    PRODUCT_DEFINITION_CONTEXT(
        "PRODUCT_DEFINITION_CONTEXT",
        listOf(
            Part21ArgSpec(Part21ArgKind.STRING), // name
            Part21ArgSpec(Part21ArgKind.REFERENCE), // frame_of_reference
            Part21ArgSpec(Part21ArgKind.STRING), // life_cycle_stage
        ),
        mapOf(1 to APPLICATION_CONTEXT),
    ),
    APPROVAL_STATUS(
        "APPROVAL_STATUS",
        listOf(Part21ArgSpec(Part21ArgKind.STRING)), // name
    ),
    PERSON(
        "PERSON",
        listOf(
            Part21ArgSpec(Part21ArgKind.STRING), // id
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // last_name
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // first_name
            Part21ArgSpec(Part21ArgKind.STRING_LIST, optional = true), // middle_names
            Part21ArgSpec(Part21ArgKind.STRING_LIST, optional = true), // prefix_titles
            Part21ArgSpec(Part21ArgKind.STRING_LIST, optional = true), // suffix_titles
        ),
    ),
    ORGANIZATION(
        "ORGANIZATION",
        listOf(
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // id
            Part21ArgSpec(Part21ArgKind.STRING), // name
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // description
        ),
    ),
    PRODUCT(
        "PRODUCT",
        listOf(
            Part21ArgSpec(Part21ArgKind.STRING), // id
            Part21ArgSpec(Part21ArgKind.STRING), // name
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // description
            Part21ArgSpec(Part21ArgKind.REFERENCE_LIST), // frame_of_reference : SET [1:?]
        ),
        mapOf(3 to PRODUCT_CONTEXT),
    ),
    PRODUCT_DEFINITION_FORMATION(
        "PRODUCT_DEFINITION_FORMATION",
        listOf(
            Part21ArgSpec(Part21ArgKind.STRING), // id
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // description
            Part21ArgSpec(Part21ArgKind.REFERENCE), // of_product
        ),
        mapOf(2 to PRODUCT),
    ),
    PRODUCT_DEFINITION(
        "PRODUCT_DEFINITION",
        listOf(
            Part21ArgSpec(Part21ArgKind.STRING), // id
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // description
            Part21ArgSpec(Part21ArgKind.REFERENCE), // formation
            Part21ArgSpec(Part21ArgKind.REFERENCE), // frame_of_reference
        ),
        mapOf(2 to PRODUCT_DEFINITION_FORMATION, 3 to PRODUCT_DEFINITION_CONTEXT),
    ),
    NEXT_ASSEMBLY_USAGE_OCCURRENCE(
        "NEXT_ASSEMBLY_USAGE_OCCURRENCE",
        listOf(
            Part21ArgSpec(Part21ArgKind.STRING), // id
            Part21ArgSpec(Part21ArgKind.STRING), // name
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // description
            Part21ArgSpec(Part21ArgKind.REFERENCE), // relating_product_definition
            Part21ArgSpec(Part21ArgKind.REFERENCE), // related_product_definition
            Part21ArgSpec(Part21ArgKind.STRING, optional = true), // reference_designator
        ),
        mapOf(3 to PRODUCT_DEFINITION, 4 to PRODUCT_DEFINITION),
    ),
    APPROVAL(
        "APPROVAL",
        listOf(
            Part21ArgSpec(Part21ArgKind.REFERENCE), // status
            Part21ArgSpec(Part21ArgKind.STRING), // level
        ),
        mapOf(0 to APPROVAL_STATUS),
    ),
    PERSON_AND_ORGANIZATION(
        "PERSON_AND_ORGANIZATION",
        listOf(
            Part21ArgSpec(Part21ArgKind.REFERENCE), // the_person
            Part21ArgSpec(Part21ArgKind.REFERENCE), // the_organization
        ),
        mapOf(0 to PERSON, 1 to ORGANIZATION),
    ),
    ;

    companion object {
        val byEntityName: Map<String, Part21EntityKind> = entries.associateBy { it.entityName }
    }
}
