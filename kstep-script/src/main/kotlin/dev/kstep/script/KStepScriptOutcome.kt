package dev.kstep.script

import dev.kstep.core.DslViolation

/**
 * Structured code family for the script layer, parallel to `dev.kstep.core.DslViolationCodes`'s
 * `KSTEP-W-xxx`/`KSTEP-M-xxx`. Never surfaced as a thrown exception or a raw stack trace to a
 * [KStepScriptHost] caller — always one of [KStepScriptOutcome]'s structured cases.
 */
object KStepScriptOutcomeCodes {
    const val COMPILATION_ERROR = "KSTEP-S-001"
    const val NO_MODEL_PRODUCED = "KSTEP-S-002"
    const val RUNTIME_ERROR = "KSTEP-S-003"

    /** Belt-and-braces fallback (see [KStepScriptHost]'s KDoc): a script used `getOrThrow()`
     * directly, which threw before [stepFile] ever ran. The original structured
     * [DslViolation] list cannot be recovered from the thrown exception's message — only its
     * rendered text — so this code marks a single *synthetic* violation, distinguishable from
     * a genuine [dev.kstep.core.DslViolationCodes.WHERE_RULE_NOT_SATISFIED]/
     * [dev.kstep.core.DslViolationCodes.MISSING_MANDATORY_REFERENCE] violation collected via
     * the preferred `root(ValidationResult)` aggregating form.
     */
    const val GET_OR_THROW_ABORT = "KSTEP-S-004"
}

/** One compiler diagnostic, stripped down to what a CLI/JSON consumer needs: no internal
 * `kotlin.script.experimental.api.ScriptDiagnostic` types leak past [KStepScriptHost].
 */
data class ScriptDiagnosticView(
    val severity: String,
    val message: String,
    val line: Int?,
    val column: Int?,
)

/**
 * The outcome of [KStepScriptHost.eval] — never a thrown exception or a raw stack trace, always
 * one of these structured cases. See each case's KDoc for the [KStepScriptOutcomeCodes] it maps
 * to and the host-side condition that produces it.
 */
sealed interface KStepScriptOutcome {
    /** The script evaluated cleanly and produced a fully-valid [KStepModel] — every registered
     * root passed its own WHERE-rule/mandatory-reference validation. [model.header.timestamp]
     * is guaranteed non-blank (see [KStepScriptHost]).
     */
    data class Success(
        val model: KStepModel,
    ) : KStepScriptOutcome

    /** [KStepScriptOutcomeCodes.COMPILATION_ERROR] — the script failed to compile (a Kotlin
     * syntax error, an unresolved reference, a type error). [diagnostics] holds at least one
     * entry of severity `ERROR`.
     */
    data class CompilationError(
        val diagnostics: List<ScriptDiagnosticView>,
    ) : KStepScriptOutcome

    /** [KStepScriptOutcomeCodes.NO_MODEL_PRODUCED] — the script compiled and ran, but its last
     * expression did not evaluate to a [KStepModel] (e.g. it was `Unit`, or some other type).
     */
    data class NoModelProduced(
        val message: String,
    ) : KStepScriptOutcome

    /** The script produced a [KStepModel] whose [KStepModel.violations] is non-empty (the
     * `root(ValidationResult)` aggregating form), or a `getOrThrow()` call aborted the script
     * body — see [KStepScriptOutcomeCodes.GET_OR_THROW_ABORT] — in which case [violations]
     * holds exactly one synthetic entry carrying the original rendered message.
     */
    data class ValidationErrors(
        val violations: List<DslViolation>,
    ) : KStepScriptOutcome

    /** [KStepScriptOutcomeCodes.RUNTIME_ERROR] — the script threw an exception at runtime that
     * was not a `getOrThrow()` validation-failure abort. Carries only [exceptionClass] and
     * [message] — never a stack trace.
     */
    data class RuntimeError(
        val message: String,
        val exceptionClass: String,
    ) : KStepScriptOutcome
}
