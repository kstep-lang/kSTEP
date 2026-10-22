package dev.kstep.script

import dev.kstep.core.ap242.Ap242ModelValidator
import dev.kstep.core.ap242.ModelValidationResult
import dev.kstep.express.semantic.SemanticModelException
import dev.kstep.express.validation.IndeterminateDerivedValueException
import dev.kstep.express.validation.InstanceGraphException
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.express.validation.UnknownInstanceTypeException
import dev.kstep.express.validation.UnsupportedInverseException
import dev.kstep.express.validation.UnsupportedUniqueRuleException
import dev.kstep.express.validation.UnsupportedWhereExpressionException
import dev.kstep.express.validation.WhereRuleEvaluationException

/** Why a population-level validation produced no verdict (see [ModelValidationOutcome.Unavailable]). */
enum class UnavailableKind {
    /** A population or evaluation bound was exceeded (instances, references, depth, visited nodes). */
    LIMIT_EXCEEDED,

    /** A schema construct outside the supported EXPRESS subset (WHERE expression, INVERSE, UNIQUE). */
    UNSUPPORTED_CONSTRUCT,

    /** A rule could not be evaluated on the data at hand. */
    EVALUATION_FAILED,

    /** The population contains an instance whose type the AP242 describer does not know. */
    UNKNOWN_INSTANCE_TYPE,

    /** The schema model itself was unusable. */
    SCHEMA_MODEL,
}

/**
 * Result of [validateModel]: either a definite [Checked] verdict or [Unavailable] (no verdict;
 * fail closed -- a caller must never treat it as "valid").
 */
sealed interface ModelValidationOutcome {
    data class Checked(
        val result: ModelValidationResult,
    ) : ModelValidationOutcome

    data class Unavailable(
        val kind: UnavailableKind,
        val message: String,
        val exceptionClass: String,
    ) : ModelValidationOutcome
}

/**
 * Population-level validation (ADR-0022/0023: USEDIN/INVERSE/UNIQUE and the acyclic-assembly rule)
 * of the entities registered via `root(...)`. Opt-in (ADR-0024): neither [KStepScriptHost] nor the
 * builders run it. Registered `shape(...)` assignments are not validated -- they are no AP242
 * entity type. Only the expected failure types of the validator are mapped; anything else
 * propagates.
 */
fun KStepModel.validateModel(
    limits: ReverseReferenceIndex.Limits = ReverseReferenceIndex.Limits(),
): ModelValidationOutcome =
    try {
        ModelValidationOutcome.Checked(Ap242ModelValidator.validate(roots, limits))
    } catch (e: UnknownInstanceTypeException) {
        e.unavailable(UnavailableKind.UNKNOWN_INSTANCE_TYPE)
    } catch (e: InstanceGraphException) {
        // Covers ReverseReferenceIndexLimitExceededException and AcyclicEvaluationLimitExceededException.
        e.unavailable(UnavailableKind.LIMIT_EXCEEDED)
    } catch (e: UnsupportedWhereExpressionException) {
        e.unavailable(UnavailableKind.UNSUPPORTED_CONSTRUCT)
    } catch (e: UnsupportedInverseException) {
        e.unavailable(UnavailableKind.UNSUPPORTED_CONSTRUCT)
    } catch (e: UnsupportedUniqueRuleException) {
        e.unavailable(UnavailableKind.UNSUPPORTED_CONSTRUCT)
    } catch (e: WhereRuleEvaluationException) {
        e.unavailable(UnavailableKind.EVALUATION_FAILED)
    } catch (e: IndeterminateDerivedValueException) {
        e.unavailable(UnavailableKind.EVALUATION_FAILED)
    } catch (e: SemanticModelException) {
        e.unavailable(UnavailableKind.SCHEMA_MODEL)
    }

private fun Exception.unavailable(kind: UnavailableKind): ModelValidationOutcome.Unavailable =
    ModelValidationOutcome.Unavailable(
        kind = kind,
        message = message ?: "model validation failed",
        exceptionClass = this::class.qualifiedName ?: "unknown",
    )
