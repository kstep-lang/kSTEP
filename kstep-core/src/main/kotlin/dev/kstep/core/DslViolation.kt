package dev.kstep.core

/**
 * A single structured validation failure from building a kSTEP DSL entity, analogous to
 * kUML's `KUML-E-xxx` structured errors. [ruleLabel]/[expressionText] are only populated for
 * [DslViolationCodes.WHERE_RULE_NOT_SATISFIED] violations; a structural violation
 * ([DslViolationCodes.MISSING_MANDATORY_REFERENCE] or
 * [DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE]) leaves them `null`.
 */
data class DslViolation(
    val code: String,
    val entityName: String,
    val ruleLabel: String?,
    val expressionText: String?,
    val message: String,
)

object DslViolationCodes {
    const val WHERE_RULE_NOT_SATISFIED = "KSTEP-W-001"
    const val MISSING_MANDATORY_REFERENCE = "KSTEP-M-001"

    // A non-OPTIONAL *primitive* attribute (STRING/label/identifier/text, not an entity-typed
    // reference) that was never assigned in the builder — the presence-sentinel sibling of
    // MISSING_MANDATORY_REFERENCE. Kept as a distinct code (not a reuse of M-001) so downstream
    // consumers that switch on `code` can still tell "you forgot a sub-object reference" apart
    // from "you forgot a mandatory scalar value" — see README Status for the wave that added it.
    const val MISSING_MANDATORY_ATTRIBUTE = "KSTEP-M-002"

    // An aggregation (SET/LIST/BAG/ARRAY) attribute that WAS set but violates its declared
    // lower bound — e.g. product.frame_of_reference : SET [1:?] OF product_context given an
    // explicit empty set. Distinct from MISSING_MANDATORY_REFERENCE ("never set at all"): a
    // caller who explicitly assigns `frameOfReference = emptySet()` gets this code, not M-001,
    // because the two are different mistakes with different fixes ("add at least one element"
    // vs. "you forgot to set this entirely"). Introduced kSTEP M2 Welle 10 alongside
    // kstep-core's move onto the codegen-generated AP242 types, whose SET-typed attributes are
    // the first ones this layer enforces a lower bound on.
    const val AGGREGATION_BOUND_VIOLATED = "KSTEP-A-001"

    // An INVERSE attribute whose evaluated cardinality violates its declared bounds, e.g.
    // application_context.context_elements : SET [1:?] with no context element referencing the
    // application_context. Only detectable on population level (ADR-0022), never in a builder.
    const val INVERSE_BOUND_VIOLATED = "KSTEP-I-001"

    // `acyclic_product_definition_relationship` failed: the usage closes a cycle in the assembly
    // structure (ADR-0023). Distinct from WHERE_RULE_NOT_SATISFIED so consumers can tell "a rule on this
    // one instance is false" from "the structure around it is cyclic"; the message carries the cycle path.
    const val ACYCLIC_RELATIONSHIP_VIOLATED = "KSTEP-W-002"

    // A UNIQUE rule is violated on population level: two or more instances agree on every listed value
    // (ADR-0023). An instance with an unset/indeterminate value takes no part in the comparison.
    const val UNIQUE_RULE_VIOLATED = "KSTEP-U-001"
}
