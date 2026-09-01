package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.Organization
import dev.kstep.generated.ap242v1.Person
import dev.kstep.generated.ap242v1.PersonAndOrganization

private const val ENTITY_NAME = "person_and_organization"

/**
 * Ergonomic wrapper over the codegen-generated [PersonAndOrganization] (kSTEP M2 Welle 10 — see
 * [Product]'s equivalent doc note). `the_person : person` and `the_organization : organization`
 * are both mandatory entity references in the real schema — a correction from the pre-Welle-10
 * hand-authored shape, which modeled both as plain, non-`OPTIONAL` `String`s.
 *
 * Pre-Welle-10, this builder enforced a synthesized `wr1: NOT ((SELF.the_person = '') AND
 * (SELF.the_organization = ''))` — "at least one of the two must be non-blank" — as an
 * approximation of the real (unsupported, `SIZEOF`/`USEDIN`-based) `WR1`/`WR2`. That rule is
 * dropped entirely in this wave, not relabeled: now that both attributes are typed as mandatory
 * entity references rather than optional-by-convention `String`s, "at least one set" is no
 * longer a meaningful relaxation to approximate — both are simply required, enforced the same
 * structural way every other mandatory reference in this module is (a still-`null` builder
 * property is `KSTEP-M-001`).
 */
class PersonAndOrganizationBuilder internal constructor() {
    var thePerson: Person? = null
    var theOrganization: Organization? = null
}

fun personAndOrganization(
    block: PersonAndOrganizationBuilder.() -> Unit = {
    },
): ValidationResult<PersonAndOrganization> {
    val builder = PersonAndOrganizationBuilder().apply(block)

    val violations =
        buildList {
            if (builder.thePerson == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "the_person"))
            if (builder.theOrganization == null) {
                add(missingMandatoryReferenceViolation(ENTITY_NAME, "the_organization"))
            }
        }

    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            PersonAndOrganization(thePerson = builder.thePerson!!, theOrganization = builder.theOrganization!!),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
