package dev.kstep.desktop.ai.repair

import dev.kstep.desktop.ai.llm.FailureKind
import dev.kstep.desktop.ai.llm.LlmClient
import dev.kstep.desktop.ai.llm.LlmReply
import dev.kstep.desktop.ai.llm.LlmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

sealed interface GenerateResult {
    data object NoConsent : GenerateResult

    data object NoKey : GenerateResult

    data object NotAllowed : GenerateResult

    data object Started : GenerateResult
}

/**
 * Orchestrates one run: asks the model, compiles the candidate in the worker, repeats (ADR-0056). Nothing is sent unless
 * [hasConsent] is true; the check lives here and not only in the UI.
 */
class RepairSession(
    private val machine: RepairMachine,
    private val limits: AiLimits,
    private val clientFactory: () -> LlmClient?,
    private val compiler: CandidateCompiler,
    private val hasConsent: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val _state = MutableStateFlow<RepairState>(RepairState.Idle)
    val state: StateFlow<RepairState> get() = _state

    private val lock = Any()

    @Volatile private var runId = 0L
    private var job: Job? = null

    @Volatile private var process: Process? = null

    fun generate(
        prompt: String,
        baseText: String,
        includeScript: Boolean,
    ): GenerateResult {
        if (prompt.isBlank() || prompt.length > limits.maxPromptChars) return GenerateResult.NotAllowed
        if (includeScript && baseText.toByteArray(Charsets.UTF_8).size > limits.maxIncludedScriptBytes) {
            return GenerateResult.NotAllowed
        }
        if (!hasConsent()) return GenerateResult.NoConsent
        val client = clientFactory() ?: return GenerateResult.NoKey
        val id: Long
        synchronized(lock) {
            job?.cancel()
            kill()
            id = ++runId
            val (s, effects) =
                machine.reduce(
                    _state.value,
                    RepairEvent.Start(id, prompt, baseText, includeScript, clock()),
                )
            _state.value = s
            job = scope.launch { drive(id, client, effects) }
        }
        return GenerateResult.Started
    }

    private suspend fun drive(
        id: Long,
        client: LlmClient,
        first: List<RepairEffect>,
    ) {
        var effects = first
        try {
            withTimeout(limits.totalMillis) {
                while (effects.isNotEmpty() && runId == id) {
                    val next = ArrayList<RepairEffect>()
                    for (eff in effects) {
                        val event =
                            when (eff) {
                                is RepairEffect.CallLlm ->
                                    RepairEvent.LlmAnswered(
                                        id,
                                        call(client, eff.request),
                                        clock(),
                                    )
                                is RepairEffect.Compile -> RepairEvent.Compiled(id, compile(eff.script), clock())
                                RepairEffect.KillWorker -> {
                                    kill()
                                    null
                                }
                            } ?: continue
                        val (s, more) = synchronized(lock) { if (runId != id) return@withTimeout else apply(event) }
                        _state.value = s
                        next.addAll(more)
                    }
                    effects = next
                }
            }
        } catch (e: CancellationException) {
            if (runId == id && _state.value.let { it is RepairState.Requesting || it is RepairState.Compiling }) {
                // total timeout (withTimeout) or external cancel
                // kill() inside the check: a newer run owns the worker process now and must not be killed by this one
                synchronized(lock) {
                    if (runId == id) {
                        val (s, _) = machine.reduce(_state.value, RepairEvent.Tick(clock() + limits.totalMillis + 1))
                        _state.value = s
                        kill()
                    }
                }
            }
            throw e
        }
    }

    private fun apply(event: RepairEvent): Pair<RepairState, List<RepairEffect>> = machine.reduce(_state.value, event)

    private suspend fun call(
        client: LlmClient,
        request: LlmRequest,
    ): LlmReply =
        withTimeoutOrNull(limits.perCallMillis) { client.complete(request) } ?: LlmReply.Failed(FailureKind.TIMEOUT)

    private suspend fun compile(script: String): CompileOutcome =
        runInterruptible(Dispatchers.IO) { compiler.compile(script) { process = it } }

    private fun kill() {
        val p = process ?: return
        process = null
        try {
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
        } catch (_: Exception) {
            // already gone
        }
    }

    fun stop() {
        synchronized(lock) {
            val (s, _) = machine.reduce(_state.value, RepairEvent.Stop)
            _state.value = s
            runId++ // late answers of the old run are dropped
            job?.cancel()
        }
        kill()
    }

    fun reset() {
        synchronized(lock) {
            runId++
            job?.cancel()
            _state.value = RepairState.Idle
        }
        kill()
    }

    override fun close() {
        stop()
    }
}
