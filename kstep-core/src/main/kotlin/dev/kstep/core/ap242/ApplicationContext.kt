package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.ApplicationContext

private const val ENTITY_NAME = "application_context"

/**
 * Ergonomic wrapper over the codegen-generated [ApplicationContext] (kSTEP M2 Welle 10 —
 * `kstep-core` no longer hand-authors this entity's shape at all, only its validating builder;
 * see `docs/adr/ADR-0004-core-on-generated-types.adoc`). `application` is the one mandatory
 * `label` attribute; the real AP242 `application_context` has two WHERE rules
 * (`SIZEOF(USEDIN(...)) <= 1`, twice), both outside the supported WHERE-expression subset
 * (`SIZEOF`/`USEDIN`), so neither is modeled here — not even as a synthesized approximation,
 * since this entity is new to `kstep-core` in this wave and has no prior synthesized rule to
 * carry forward.
 *
 * `application_context` is a mandatory dependency of both [productContext] and
 * [productDefinitionContext] (`frame_of_reference`), which are in turn mandatory dependencies
 * of [product] and [productDefinition] respectively — see `docs/adr/ADR-0004` §"Ergonomic
 * regression" for why this is a real, and deliberate, addition to how much context a script
 * must set up before it can build a `product`.
 */
class ApplicationContextBuilder internal constructor() {
    // Nullable purely as an internal "was it set" presence sentinel (see Product.name's
    // equivalent, long-standing doc note): `application` is a non-OPTIONAL `label` with no
    // WHERE rule of its own, so a still-null value at build() time is a structural violation
    // (KSTEP-M-002), never a legitimate empty value.
    var application: String? = null
}

fun applicationContext(block: ApplicationContextBuilder.() -> Unit = {}): ValidationResult<ApplicationContext> {
    val builder = ApplicationContextBuilder().apply(block)
    val violations =
        buildList {
            if (builder.application == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "application"))
        }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(ApplicationContext(application = builder.application!!))
    } else {
        ValidationResult.Invalid(violations)
    }
}
