package dev.kstep.script

import dev.kstep.core.DslViolation
import dev.kstep.core.ValidationResult
import dev.kstep.step21.Part21Header

/**
 * The single artifact a `*.kstep.kts` script produces: a Part-21 [header] plus its [roots]
 * object set, ready for `Part21Writer.write(header, roots)` — unless one or more entities
 * registered via [KStepModelBuilder.root] failed their own WHERE-rule/mandatory-reference
 * validation, in which case [violations] carries every collected [DslViolation] instead and
 * [roots] only ever contains the entities that *did* validate successfully.
 *
 * A script's last expression must evaluate to this type (built via the top-level [stepFile]
 * DSL entry point) — see [KStepScriptHost] for how the host extracts it, defaults a blank
 * [Part21Header.timestamp], and what happens when the last expression is something else.
 */
data class KStepModel(
    val header: Part21Header,
    val roots: List<Any>,
    val violations: List<DslViolation>,
) {
    val isValid: Boolean get() = violations.isEmpty()
}

/**
 * Builder receiver for [stepFile]. Two ways to register a root, both supported deliberately
 * (kSTEP-ADR-0001's acceptance criterion #3 discussion, see the M2 scripting-wave plan):
 *
 * - [root] with an already-unwrapped entity — the concise `getOrThrow()` pattern. A validation
 *   failure throws *inside the script body*, before this builder or [stepFile] ever runs, so
 *   [KStepScriptHost]'s runtime-exception handling is what surfaces it (as a
 *   [KStepScriptOutcome.ValidationErrors], via its `getOrThrow()`-marker-message recognition —
 *   see that class's KDoc), not [KStepModel.violations].
 * - [root] with a raw [ValidationResult] — the preferred, non-throwing form for LLM/JSON
 *   consumption: every violation across every registered root is aggregated into
 *   [KStepModel.violations] instead of the script aborting at the first bad entity.
 */
class KStepModelBuilder internal constructor() {
    private val validRoots = mutableListOf<Any>()
    private val collectedViolations = mutableListOf<DslViolation>()

    /** Registers an already-validated entity (typically the result of a builder's `getOrThrow()`). */
    fun root(entity: Any) {
        validRoots += entity
    }

    /** Registers a raw [ValidationResult]: an [ValidationResult.Invalid] contributes its
     * violations to [KStepModel.violations] instead of aborting the script.
     */
    fun <T : Any> root(result: ValidationResult<T>) {
        when (result) {
            is ValidationResult.Valid -> validRoots += result.value
            is ValidationResult.Invalid -> collectedViolations += result.violations
        }
    }

    /**
     * Registers several roots at once — see the two [root] overloads for the accepted shapes.
     *
     * A `vararg` parameter has a single static element type, so it cannot forward each argument
     * to the matching [root] overload via ordinary overload resolution — every element would be
     * seen as `Any`, always selecting [root]'s single-entity overload even for a raw
     * [ValidationResult] argument (which would then be stored, unwrapped, as a "root", silently
     * defeating validation instead of aggregating into [KStepModel.violations]). Each element is
     * therefore inspected at runtime and handled exactly as the matching [root] overload would.
     */
    fun roots(vararg entities: Any) {
        entities.forEach { entity ->
            when {
                entity is ValidationResult.Valid<*> ->
                    validRoots +=
                        requireNotNull(entity.value) {
                            "roots(...): a ValidationResult.Valid root must not wrap a null value"
                        }
                entity is ValidationResult.Invalid -> collectedViolations += entity.violations
                else -> validRoots += entity
            }
        }
    }

    internal fun build(header: Part21Header): KStepModel =
        KStepModel(header = header, roots = validRoots.toList(), violations = collectedViolations.toList())
}

/**
 * Top-level DSL entry point for a `*.kstep.kts` script: builds the single [KStepModel] the
 * script exports, with the Part-21 header fields supplied inline — the CLI has no other
 * channel to receive them from.
 *
 * [timestamp] may be left blank: [KStepScriptHost] substitutes the current time before handing
 * a successfully-validated model to `Part21Writer.write`, which keeps this function itself
 * pure/deterministic (and its own unit tests reproducible) rather than baking a wall-clock read
 * into the DSL surface.
 */
fun stepFile(
    fileName: String,
    schema: String = "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF",
    timestamp: String = "",
    description: List<String> = emptyList(),
    author: List<String> = emptyList(),
    organization: List<String> = emptyList(),
    block: KStepModelBuilder.() -> Unit,
): KStepModel {
    val builder = KStepModelBuilder().apply(block)
    val header =
        Part21Header(
            fileName = fileName,
            timestamp = timestamp,
            schemaIdentifiers = listOf(schema),
            description = description,
            author = author,
            organization = organization,
        )
    return builder.build(header)
}
