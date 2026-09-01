package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.Organization

private const val ENTITY_NAME = "organization"

/**
 * Ergonomic wrapper over the codegen-generated [Organization] (kSTEP M2 Welle 10 — see
 * [ApplicationContext]'s equivalent doc note). `name` is the one mandatory `label`; `id`
 * (`identifier`) and `description` (`text`) are both genuinely `OPTIONAL` in the real schema —
 * note `id` is optional here even though every one of the six V1 entities uses a mandatory `id`
 * as its natural identity attribute; `organization` does not. No WHERE rule on the real entity.
 */
class OrganizationBuilder internal constructor() {
    var id: String? = null
    var name: String? = null
    var description: String? = null
}

fun organization(block: OrganizationBuilder.() -> Unit = {}): ValidationResult<Organization> {
    val builder = OrganizationBuilder().apply(block)
    val violations =
        buildList {
            if (builder.name == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "name"))
        }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            Organization(id = builder.id, name = builder.name!!, description = builder.description),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
