package dev.kstep.shape.importer

import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import java.nio.charset.StandardCharsets

/** Outcome of the opt-in AP242 V1 check of `kstep import --validate`. */
sealed interface ImportValidationOutcome {
    /** [violations]/[skipped]/[modelFindings] all zero means the V1 entities of the file are clean. */
    data class Validated(
        val reconstructed: Int,
        val opaque: Int,
        val violations: Int,
        val skipped: Int,
        val modelFindings: Int,
        val suppressed: Int,
    ) : ImportValidationOutcome {
        val clean: Boolean get() = violations == 0 && skipped == 0 && modelFindings == 0
    }

    /** No verdict could be reached (limits, syntax, encoding); `kstep import --validate` fails closed. */
    data class Unavailable(
        val reason: String,
    ) : ImportValidationOutcome
}

/**
 * Re-reads the file with the typed [Part21Reader] (TOLERANT, population validation on) and reports the
 * AP242 V1 verdict. The reader's own limits (5 MB, 10 000 instances) apply, so large geometry files
 * yield [ImportValidationOutcome.Unavailable] -- documented in ADR-0030, consistent with ADR-0024.
 */
object StepImportValidation {
    fun validate(content: ByteArray): ImportValidationOutcome =
        try {
            val result =
                Part21Reader.read(
                    String(content, StandardCharsets.ISO_8859_1),
                    Part21ReadMode.TOLERANT,
                    validateModel = true,
                )
            ImportValidationOutcome.Validated(
                reconstructed = result.instances.size,
                opaque = result.opaque.size,
                violations = result.violations.size,
                skipped = result.skipped.size,
                modelFindings = result.modelValidation?.findings?.size ?: 0,
                suppressed = result.modelValidation?.suppressedFindingCount ?: 0,
            )
        } catch (e: Exception) {
            ImportValidationOutcome.Unavailable(
                "${e.javaClass.simpleName}: ${StepInputValidation.sanitize(e.message.orEmpty())}",
            )
        }
}
