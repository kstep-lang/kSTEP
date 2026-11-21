package dev.kstep.desktop.ai.repair

import dev.kstep.desktop.ai.AiErrorCodes
import dev.kstep.desktop.ai.extract.Diagnostic
import dev.kstep.desktop.ai.extract.ErrorSummary
import dev.kstep.desktop.ai.extract.Extraction
import dev.kstep.desktop.ai.extract.ResponseExtractor
import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.llm.FailureKind
import dev.kstep.desktop.ai.llm.LlmReply
import dev.kstep.desktop.ai.llm.LlmRequest
import dev.kstep.desktop.ai.prompt.PromptBuilder

/** The result of compiling one candidate in the worker (implemented in kstep-desktop). */
sealed interface CompileOutcome {
    data object Ok : CompileOutcome

    data class Errors(
        val diagnostics: List<Diagnostic>,
    ) : CompileOutcome

    /** No verdict is possible (geometry engine or worker sandbox missing); this is not a compile error. */
    data class Unverifiable(
        val reason: String,
        val why: NotVerifiedReason = NotVerifiedReason.GEOMETRY_ENGINE_UNAVAILABLE,
    ) : CompileOutcome

    /**
     * The check itself was refused (the worker sandbox could not start, ADR-0059): not a compiler error, so nothing
     * goes back to the model; the run ends with [RepairState.Failed].
     */
    data class Refused(
        val code: String,
        val message: String,
    ) : CompileOutcome
}

/** Why a proposal carries no verdict. */
enum class NotVerifiedReason { GEOMETRY_ENGINE_UNAVAILABLE, SANDBOX_UNAVAILABLE }

/** Compiles a candidate script. [onStarted] receives the worker process so a stop can kill its tree. */
fun interface CandidateCompiler {
    fun compile(
        script: String,
        onStarted: (Process) -> Unit,
    ): CompileOutcome
}

enum class Verdict { COMPILES, DOES_NOT_COMPILE, NOT_VERIFIED }

data class AttemptLine(
    val attempt: Int,
    val max: Int,
    val text: String,
)

/** What one run is about; carried by the running states. */
data class RunContext(
    val runId: Long,
    val prompt: String,
    val baseText: String,
    val includeScript: Boolean,
    val startedMs: Long,
    val lastCandidate: String? = null,
)

/** Where a proposal comes from: the model, or a subset of the model's change groups the user chose (ADR-0069). */
enum class ProposalOrigin { MODEL, SELECTION }

sealed interface RepairState {
    data object Idle : RepairState

    data class Requesting(
        val ctx: RunContext,
        val attempt: Int,
        val attempts: List<AttemptLine>,
    ) : RepairState

    data class Compiling(
        val ctx: RunContext,
        val attempt: Int,
        val attempts: List<AttemptLine>,
    ) : RepairState

    data class Proposal(
        val script: String,
        val baseText: String,
        val verdict: Verdict,
        val attempts: List<AttemptLine>,
        val attemptsUsed: Int,
        val notVerifiedReason: NotVerifiedReason? = null,
        val origin: ProposalOrigin = ProposalOrigin.MODEL,
        /** For a [ProposalOrigin.SELECTION] proposal: the model's proposal "Back to proposal" returns to. */
        val original: Proposal? = null,
    ) : RepairState

    /** The user's subset is being re-checked in the worker sandbox (no model call, ADR-0069). */
    data class CheckingSelection(
        val original: Proposal,
        val selected: Set<Int>,
        val total: Int,
        val buffer: String,
        val baseSnapshot: String,
        val startedMs: Long,
    ) : RepairState

    data class Failed(
        val code: String,
        val message: String,
        val attempts: List<AttemptLine>,
        /** The proposal "Back to proposal" returns to, if this failure came from checking a subset. */
        val back: Proposal? = null,
    ) : RepairState

    data object Stopped : RepairState
}

sealed interface RepairEvent {
    data class Start(
        val runId: Long,
        val prompt: String,
        val baseText: String,
        val includeScript: Boolean,
        val nowMs: Long,
    ) : RepairEvent

    data class LlmAnswered(
        val runId: Long,
        val reply: LlmReply,
        val nowMs: Long,
    ) : RepairEvent

    data class Compiled(
        val runId: Long,
        val outcome: CompileOutcome,
        val nowMs: Long,
    ) : RepairEvent

    data object Stop : RepairEvent

    data class Tick(
        val nowMs: Long,
    ) : RepairEvent
}

sealed interface RepairEffect {
    data class CallLlm(
        val runId: Long,
        val request: LlmRequest,
    ) : RepairEffect

    data class Compile(
        val runId: Long,
        val script: String,
    ) : RepairEffect

    data object KillWorker : RepairEffect
}

/** The pure repair loop (ADR-0056): at most [AiLimits.maxAttempts] model calls, no I/O. */
class RepairMachine(
    private val limits: AiLimits,
    private val prompts: PromptBuilder,
    private val secrets: () -> List<Secret> = { emptyList() },
) {
    fun reduce(
        state: RepairState,
        event: RepairEvent,
    ): Pair<RepairState, List<RepairEffect>> =
        when (event) {
            is RepairEvent.Start -> start(event)
            RepairEvent.Stop ->
                if (state is RepairState.Requesting || state is RepairState.Compiling) {
                    RepairState.Stopped to listOf(RepairEffect.KillWorker)
                } else {
                    state to emptyList()
                }
            is RepairEvent.Tick -> tick(state, event.nowMs)
            is RepairEvent.LlmAnswered -> answered(state, event)
            is RepairEvent.Compiled -> compiled(state, event)
        }

    private fun start(e: RepairEvent.Start): Pair<RepairState, List<RepairEffect>> {
        val ctx = RunContext(e.runId, e.prompt, e.baseText, e.includeScript, e.nowMs)
        val request = prompts.initial(e.prompt, if (e.includeScript) e.baseText else null)
        return RepairState.Requesting(ctx, 1, emptyList()) to listOf(RepairEffect.CallLlm(e.runId, request))
    }

    private fun timedOut(
        ctx: RunContext,
        now: Long,
    ) = now - ctx.startedMs > limits.totalMillis

    private fun limitFailure(attempts: List<AttemptLine>): Pair<RepairState, List<RepairEffect>> =
        RepairState.Failed(
            AiErrorCodes.AI_LIMIT_REACHED,
            "time limit reached (${limits.totalMillis / MS} s)",
            attempts,
        ) to
            listOf(RepairEffect.KillWorker)

    private fun tick(
        state: RepairState,
        now: Long,
    ): Pair<RepairState, List<RepairEffect>> =
        when (state) {
            is RepairState.Requesting ->
                if (timedOut(state.ctx, now)) {
                    limitFailure(state.attempts)
                } else {
                    state to
                        emptyList()
                }
            is RepairState.Compiling ->
                if (timedOut(state.ctx, now)) {
                    limitFailure(state.attempts)
                } else {
                    state to
                        emptyList()
                }
            else -> state to emptyList()
        }

    private fun answered(
        state: RepairState,
        e: RepairEvent.LlmAnswered,
    ): Pair<RepairState, List<RepairEffect>> {
        if (state !is RepairState.Requesting || state.ctx.runId != e.runId) return state to emptyList()
        if (timedOut(state.ctx, e.nowMs)) return limitFailure(state.attempts)
        val n = state.attempt
        return when (val reply = e.reply) {
            is LlmReply.Failed -> failure(reply, state.attempts)
            is LlmReply.Text ->
                when (val x = ResponseExtractor.extract(reply.text, limits, secrets())) {
                    is Extraction.Script ->
                        RepairState.Compiling(state.ctx.copy(lastCandidate = x.text), n, state.attempts) to
                            listOf(RepairEffect.Compile(e.runId, x.text))
                    is Extraction.Rejected -> {
                        val lines = state.attempts + AttemptLine(n, limits.maxAttempts, "answer rejected: ${x.reason}")
                        if (n >= limits.maxAttempts) {
                            RepairState.Failed(AiErrorCodes.AI_RESPONSE_INVALID, x.reason, lines) to emptyList()
                        } else {
                            nextRequest(state.ctx, n + 1, lines, "No valid script was returned (${x.reason}).")
                        }
                    }
                }
        }
    }

    private fun failure(
        f: LlmReply.Failed,
        attempts: List<AttemptLine>,
    ): Pair<RepairState, List<RepairEffect>> {
        val (code, msg) =
            when (f.kind) {
                FailureKind.UNREACHABLE, FailureKind.TIMEOUT ->
                    AiErrorCodes.AI_PROVIDER_UNREACHABLE to "Provider not reachable"
                FailureKind.KEY_REJECTED -> AiErrorCodes.AI_PROVIDER_REJECTED to "API key rejected"
                FailureKind.RATE_LIMITED -> AiErrorCodes.AI_PROVIDER_REJECTED to "Rate limited by provider"
                FailureKind.PROVIDER_ERROR ->
                    AiErrorCodes.AI_PROVIDER_REJECTED to
                        ("Provider error" + (f.httpStatus?.let { " (HTTP $it)" } ?: ""))
                FailureKind.RESPONSE_TOO_LARGE, FailureKind.MALFORMED ->
                    AiErrorCodes.AI_RESPONSE_INVALID to "Provider answer too large or malformed"
            }
        return RepairState.Failed(code, msg, attempts) to emptyList()
    }

    private fun compiled(
        state: RepairState,
        e: RepairEvent.Compiled,
    ): Pair<RepairState, List<RepairEffect>> {
        if (state !is RepairState.Compiling || state.ctx.runId != e.runId) return state to emptyList()
        val n = state.attempt
        val candidate = state.ctx.lastCandidate.orEmpty()

        fun proposal(
            v: Verdict,
            line: String,
            why: NotVerifiedReason? = null,
        ): Pair<RepairState, List<RepairEffect>> =
            RepairState.Proposal(
                candidate,
                state.ctx.baseText,
                v,
                state.attempts + AttemptLine(n, limits.maxAttempts, line),
                n,
                why,
            ) to emptyList()
        return when (val o = e.outcome) {
            CompileOutcome.Ok -> proposal(Verdict.COMPILES, "compiles")
            is CompileOutcome.Unverifiable ->
                proposal(
                    Verdict.NOT_VERIFIED,
                    if (o.why == NotVerifiedReason.SANDBOX_UNAVAILABLE) {
                        "not checked (no worker sandbox on this system)"
                    } else {
                        "not verified (geometry engine unavailable)"
                    },
                    o.why,
                )
            is CompileOutcome.Refused ->
                RepairState.Failed(
                    o.code,
                    o.message,
                    state.attempts + AttemptLine(n, limits.maxAttempts, "not checked (${o.code})"),
                ) to emptyList()
            is CompileOutcome.Errors -> {
                val first = o.diagnostics.firstOrNull()
                val where =
                    first
                        ?.let {
                            " ${it.code}" + (
                                it.line?.let { l ->
                                    " at $l:${it.column ?: 0}"
                                } ?: ""
                            )
                        }.orEmpty()
                val line = "does not compile$where"
                if (n >= limits.maxAttempts) {
                    proposal(Verdict.DOES_NOT_COMPILE, line)
                } else if (timedOut(state.ctx, e.nowMs)) {
                    limitFailure(state.attempts + AttemptLine(n, limits.maxAttempts, line))
                } else if (candidate.toByteArray(Charsets.UTF_8).size > limits.maxIncludedScriptBytes) {
                    proposal(Verdict.DOES_NOT_COMPILE, "$line (too large to send for repair)")
                } else {
                    val summary = ErrorSummary.forModel(o.diagnostics, limits, secrets())
                    nextRequest(state.ctx, n + 1, state.attempts + AttemptLine(n, limits.maxAttempts, line), summary)
                }
            }
        }
    }

    private fun nextRequest(
        ctx: RunContext,
        attempt: Int,
        attempts: List<AttemptLine>,
        summary: String,
    ): Pair<RepairState, List<RepairEffect>> {
        val request =
            prompts.repair(ctx.prompt, if (ctx.includeScript) ctx.baseText else null, ctx.lastCandidate, summary)
        return RepairState.Requesting(ctx, attempt, attempts) to listOf(RepairEffect.CallLlm(ctx.runId, request))
    }

    private companion object {
        const val MS = 1000
    }
}
