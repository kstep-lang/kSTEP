package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue

private const val ENTITY_NAME = "person_and_organization"
private val WHERE_RULES =
    listOf(
        WhereRuleSpec(
            label = "wr1",
            expressionText = "NOT ((SELF.the_person = '') AND (SELF.the_organization = ''))",
        ),
    )

/**
 * `dev.kstep.express` AP242-subset `person_and_organization` entity — `the_person`,
 * `the_organization`, both `STRING`, none `OPTIONAL`.
 *
 * Unlike the other five V1 entities, this one has no natural single "identity" attribute
 * (and its WHERE rule requires "at least one of the two set", not a specific one of them),
 * so neither attribute is a required top-level parameter — both are lambda-`var`s on the
 * builder.
 *
 * The constructor is `internal` — see [Product]'s equivalent doc note for why: only the
 * [personAndOrganization] builder function may produce an instance, so it always passes
 * through WHERE-rule validation first. `@ConsistentCopyVisibility` keeps the generated
 * `copy()` `internal` too.
 */
@ConsistentCopyVisibility
data class PersonAndOrganization internal constructor(
    val thePerson: String,
    val theOrganization: String,
)

class PersonAndOrganizationBuilder internal constructor() {
    var thePerson: String = ""
    var theOrganization: String = ""
}

fun personAndOrganization(
    block: PersonAndOrganizationBuilder.() -> Unit = {
    },
): ValidationResult<PersonAndOrganization> {
    val builder = PersonAndOrganizationBuilder().apply(block)
    val attributeValues =
        mapOf(
            "the_person" to WhereRuleValue.StringValue(builder.thePerson),
            "the_organization" to WhereRuleValue.StringValue(builder.theOrganization),
        )
    val violations = WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(PersonAndOrganization(builder.thePerson, builder.theOrganization))
    } else {
        ValidationResult.Invalid(violations)
    }
}
