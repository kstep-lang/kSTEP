package dev.kstep.script

import dev.kstep.core.DslViolation
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.time.Instant
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

private val logger = KotlinLogging.logger {}

// The exact marker text `dev.kstep.core.getOrThrow()` embeds at the start of the
// IllegalStateException message it throws for an Invalid ValidationResult (see
// ValidationResult.kt: `error("kSTEP DSL validation failed: ...")`). Recognized here so a
// script author who calls getOrThrow() directly still gets a structured ValidationErrors
// outcome instead of an opaque RuntimeError -- see KStepScriptOutcomeCodes.GET_OR_THROW_ABORT.
private const val VALIDATION_FAILURE_MARKER = "kSTEP DSL validation failed:"

/**
 * Evaluates `*.kstep.kts` scripts using [KStepScriptCompilationConfiguration], mapping every
 * outcome -- successful or not -- into a [KStepScriptOutcome]. Never lets a raw
 * `kotlin.script.experimental.*` type, an unhandled exception, or a stack trace escape to the
 * caller (`kstep-cli`'s `export` subcommand): a `.kstep.kts` script is arbitrary user input, and
 * this host is the "compiler as oracle" boundary that turns whatever it does -- compile
 * cleanly, fail to compile, run and validate, or throw -- into one of [KStepScriptOutcome]'s
 * structured cases.
 *
 * The host is reused across calls — do not create multiple instances.
 *
 * **Deliberately no sandbox** (see kSTEP's M2 scripting-wave plan, §8.3, for the full
 * reasoning): [KStepScriptCompilationConfiguration] uses `wholeClasspath = true` with no
 * curated/allowlisted classpath, mirroring kUML's *trusted in-process* script path (kUML's
 * equivalent *sandboxed* path exists only for its hosted-portal "compile someone else's
 * script" scenario, which kSTEP has no equivalent of yet). A `.kstep.kts` script run via
 * `kstep export` is local, operator-invoked, arbitrary Kotlin — the same trust level as running
 * `kotlinc -script` or a `build.gradle.kts` on one's own machine. If kSTEP later grows a
 * hosted surface that compiles third-party scripts, port kUML's curated-classpath + worker-
 * process approach then, not now.
 */
object KStepScriptHost {
    private val host = BasicJvmScriptingHost()
    private val compilationConfig = createJvmCompilationConfigurationFromTemplate<KStepScript>()

    /** Evaluates a `*.kstep.kts` file. */
    fun eval(file: File): KStepScriptOutcome =
        evalToOutcome { host.eval(file.toScriptSource(), compilationConfig, null) }

    /** Evaluates inline `*.kstep.kts` source — useful for tests. */
    fun eval(
        code: String,
        fileName: String = "inline.kstep.kts",
    ): KStepScriptOutcome = evalToOutcome { host.eval(code.toScriptSource(fileName), compilationConfig, null) }

    // Wraps the actual host.eval(...) invocation itself in a broad catch: kotlin-scripting
    // normally reports both compile errors (ResultWithDiagnostics.Failure) and script-runtime
    // exceptions (ResultValue.Error, handled in mapReturnValue below) *within* its result type,
    // never as a thrown exception out of eval() itself -- but a script source that can't even
    // be read (e.g. a file deleted between resolveCommand and eval), or an internal scripting-
    // host failure, could still throw directly. Catching here is what keeps that from ever
    // reaching kstep-cli as a raw stack trace.
    private fun evalToOutcome(block: () -> ResultWithDiagnostics<EvaluationResult>): KStepScriptOutcome =
        try {
            mapResult(block())
        } catch (e: Exception) {
            logger.warn(e) { "kSTEP script evaluation threw before producing a scripting result" }
            KStepScriptOutcome.RuntimeError(
                message = e.message ?: "unknown error",
                exceptionClass = e::class.qualifiedName ?: "unknown",
            )
        }

    private fun mapResult(result: ResultWithDiagnostics<EvaluationResult>): KStepScriptOutcome {
        val errorDiagnostics = result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
        // Mirrors kUML's own compile-error detection (KumlCliCommand callers): a Failure result
        // is always a compile error, but a Success result can *also* carry ERROR-severity
        // reports (e.g. warnings escalated by a future config) -- checking both is what makes
        // this robust rather than relying on the sealed-variant check alone.
        if (result is ResultWithDiagnostics.Failure || errorDiagnostics.isNotEmpty()) {
            // DEBUG/INFO reports are the scripting host's own internal chatter (JDK/module
            // resolution, classpath diagnostics) -- never useful to a script author and noisy
            // enough to bury the actual error, so only WARNING and above are surfaced here.
            // Mirrors kUML's own DiagnosticsCommand threshold (`severity >= WARNING`).
            val relevantDiagnostics = result.reports.filter { it.severity >= ScriptDiagnostic.Severity.WARNING }
            return KStepScriptOutcome.CompilationError(relevantDiagnostics.map { it.toView() })
        }
        val success = result as ResultWithDiagnostics.Success
        return mapReturnValue(success.value.returnValue)
    }

    private fun mapReturnValue(returnValue: ResultValue): KStepScriptOutcome =
        when (returnValue) {
            is ResultValue.Value -> mapProducedValue(returnValue.value)
            is ResultValue.Unit ->
                KStepScriptOutcome.NoModelProduced(
                    "script's last expression is Unit -- a *.kstep.kts script must end with a " +
                        "`stepFile { ... }` call whose result becomes the script's return value",
                )
            is ResultValue.Error -> mapRuntimeException(returnValue.error)
            is ResultValue.NotEvaluated ->
                KStepScriptOutcome.NoModelProduced("script did not evaluate to a result")
        }

    private fun mapProducedValue(value: Any?): KStepScriptOutcome =
        when {
            value is KStepModel && value.isValid -> KStepScriptOutcome.Success(withDefaultedTimestamp(value))
            value is KStepModel -> KStepScriptOutcome.ValidationErrors(value.violations)
            else ->
                KStepScriptOutcome.NoModelProduced(
                    "script's last expression evaluated to a " +
                        "'${value?.let { it::class.qualifiedName } ?: "null"}', not a " +
                        "dev.kstep.script.KStepModel -- did you forget to end the script with a " +
                        "`stepFile { ... }` call?",
                )
        }

    private fun mapRuntimeException(error: Throwable): KStepScriptOutcome {
        val message = error.message
        return if (message != null && message.startsWith(VALIDATION_FAILURE_MARKER)) {
            KStepScriptOutcome.ValidationErrors(
                violations =
                    listOf(
                        DslViolation(
                            code = KStepScriptOutcomeCodes.GET_OR_THROW_ABORT,
                            entityName = "(unknown -- getOrThrow() aborted the script before stepFile could run)",
                            ruleLabel = null,
                            expressionText = null,
                            message = message.removePrefix(VALIDATION_FAILURE_MARKER).trim(),
                        ),
                    ),
            )
        } else {
            KStepScriptOutcome.RuntimeError(
                message = message ?: (error::class.qualifiedName ?: "unknown error"),
                exceptionClass = error::class.qualifiedName ?: "unknown",
            )
        }
    }

    // A blank timestamp is a legitimate way for a script author to say "I don't care, use now"
    // -- substituted here rather than in stepFile()/KStepModelBuilder so that DSL surface stays
    // pure/deterministic and independently testable. Only ever applied to a model already known
    // to be isValid (see mapProducedValue), so this never masks a validation failure.
    private fun withDefaultedTimestamp(model: KStepModel): KStepModel =
        if (model.header.timestamp.isBlank()) {
            model.copy(header = model.header.copy(timestamp = Instant.now().toString()))
        } else {
            model
        }

    private fun ScriptDiagnostic.toView(): ScriptDiagnosticView =
        ScriptDiagnosticView(
            severity = severity.name,
            message = message,
            line = location?.start?.line,
            column = location?.start?.col,
        )
}
