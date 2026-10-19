package dev.kstep.step21

import dev.kstep.core.DslViolation
import dev.kstep.core.ap242.Ap242Population
import dev.kstep.core.ap242.ModelValidationResult

/**
 * The outcome of [Part21Reader.read]: the parsed [header], every successfully re-validated
 * instance keyed by its Part-21 `#N`, and — for instances whose `kstep-core` builder rejected
 * them — the [violations] that rejected them or the [skipped] ids of the prerequisite(s) that
 * were themselves rejected/skipped. Mirrors `dev.kstep.core.ValidationResult`'s "a validation
 * failure is structured data, not a thrown exception" philosophy, extended to reading: only
 * genuine structural malformation of the Part-21 text throws (see the exception taxonomy in
 * [Part21Reader]'s KDoc); a business-rule (`WHERE`-rule) failure surfaces here instead.
 *
 * [opaque] holds every instance [Part21ReadMode.TOLERANT] kept verbatim instead of typed-
 * constructing — a [Part21ComplexInstance], or a [Part21SimpleInstance] whose entity name is not
 * a known [Part21EntityKind]. Always empty under [Part21ReadMode.STRICT] (the default). An
 * opaque instance is neither a success nor a failure: it never appears in [instances],
 * [violations], or [skipped] — see [Part21GraphResolver]'s `construct` KDoc note.
 */
data class Part21ReadResult(
    val header: Part21Header,
    val instances: Map<Int, Any>,
    val violations: Map<Int, List<DslViolation>>,
    val skipped: Map<Int, List<Int>>,
    val opaque: Map<Int, Part21EntityInstance> = emptyMap(),
    /**
     * Population-level validation (ADR-0022); `null` unless requested via
     * `Part21Reader.read(validateModel = true)`. Independent of [isFullySuccessful].
     */
    val modelValidation: ModelValidationResult? = null,
    /**
     * The reconstructed instances referenced by something that could not be reconstructed (opaque, rejected
     * or skipped): their referrers are partly invisible.
     */
    val incompleteInstances: List<Any> = emptyList(),
) {
    /**
     * The population of the reconstructed instances for reading population-dependent derived values
     * (`productDefinition.name(scope)`, ADR-0023). Instances in [incompleteInstances] make such a read throw
     * instead of answering `null`.
     */
    fun population(): Ap242Population = Ap242Population.of(instances.values, incomplete = incompleteInstances)

    val isFullySuccessful: Boolean get() = violations.isEmpty() && skipped.isEmpty()

    /** `true` when population-level validation was not requested or found nothing. */
    val isModelValid: Boolean get() = modelValidation?.isValid ?: true
}
