package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue
import dev.kstep.generated.ap242v1.Person

private const val ENTITY_NAME = "person"

// The real AP242 `person.WR1` (ap242-v1-entities.exp), verbatim — the one WHERE rule in the
// whole V1 schema slice that is both genuinely real (not a kstep-core-synthesized
// approximation) AND within the newly-extended supported WHERE-expression subset: kSTEP M2
// Welle 10 added EXISTS() support (see WhereRuleExpressionBuilder/WhereRuleEvaluator)
// specifically so this rule no longer had to be skipped or faked.
private val WHERE_RULES =
    listOf(WhereRuleSpec(label = "WR1", expressionText = "EXISTS(last_name) OR EXISTS(first_name)"))

/**
 * Ergonomic wrapper over the codegen-generated [Person] (kSTEP M2 Welle 10 — see
 * [ApplicationContext]'s equivalent doc note). `id` is the one mandatory `identifier`; every
 * other attribute is genuinely `OPTIONAL` in the real schema — `last_name`/`first_name` are
 * plain `label`s, `middle_names`/`prefix_titles`/`suffix_titles` are `OPTIONAL LIST OF label`.
 * [WHERE_RULES] enforces the real `WR1`: at least one of `last_name`/`first_name` must actually
 * be set (an *empty string* does NOT satisfy `EXISTS` — only "was this attribute assigned at
 * all" does, which is exactly why the builder leaves both `null` by default rather than
 * defaulting to `""`).
 *
 * `middle_names`/`prefix_titles`/`suffix_titles` are each `OPTIONAL LIST [1:?] OF label`: the
 * attribute as a whole may be entirely absent (`null` — nothing to check), but *if* the caller
 * assigns a list at all, it must be non-empty — an explicitly-assigned empty list is the same
 * [dev.kstep.core.DslViolationCodes.AGGREGATION_BOUND_VIOLATED] that
 * [ProductBuilder.frameOfReference] enforces for its own `[1:?]` bound (see that class's KDoc).
 * `null` is never a bound violation here precisely because the attribute is `OPTIONAL` — unlike
 * `frame_of_reference`, "never set" is a valid, distinct state and does not raise
 * [dev.kstep.core.DslViolationCodes.MISSING_MANDATORY_REFERENCE].
 *
 * The three `List<String>?` properties are defensively copied (`.toList()`) when building the
 * generated [Person] — a caller who keeps a reference to the `MutableList` they passed in and
 * mutates it afterward must not be able to retroactively change an already-built, supposedly
 * immutable [Person] (see `EntityStore`'s own "Kotlin `data class` values are immutable"
 * promise, which a live-aliased mutable list would silently break).
 */
class PersonBuilder internal constructor() {
    var lastName: String? = null
    var firstName: String? = null
    var middleNames: List<String>? = null
    var prefixTitles: List<String>? = null
    var suffixTitles: List<String>? = null
}

fun person(
    id: String,
    block: PersonBuilder.() -> Unit = {},
): ValidationResult<Person> {
    val builder = PersonBuilder().apply(block)

    val structuralViolations =
        buildList {
            if (builder.middleNames?.isEmpty() == true) {
                add(aggregationBoundViolation(ENTITY_NAME, "middle_names", "[1:?]", 0))
            }
            if (builder.prefixTitles?.isEmpty() == true) {
                add(aggregationBoundViolation(ENTITY_NAME, "prefix_titles", "[1:?]", 0))
            }
            if (builder.suffixTitles?.isEmpty() == true) {
                add(aggregationBoundViolation(ENTITY_NAME, "suffix_titles", "[1:?]", 0))
            }
        }

    val attributeValues =
        mapOf(
            "last_name" to (builder.lastName?.let { WhereRuleValue.StringValue(it) } ?: WhereRuleValue.Unset),
            "first_name" to (builder.firstName?.let { WhereRuleValue.StringValue(it) } ?: WhereRuleValue.Unset),
        )
    val whereRuleViolations =
        WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }

    val violations = structuralViolations + whereRuleViolations
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            Person(
                id = id,
                lastName = builder.lastName,
                firstName = builder.firstName,
                middleNames = builder.middleNames?.toList(),
                prefixTitles = builder.prefixTitles?.toList(),
                suffixTitles = builder.suffixTitles?.toList(),
            ),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
