package dev.kstep.desktop.aiwire

import dev.kstep.desktop.ai.AiErrorCodes
import dev.kstep.desktop.ai.diff.HunkPlan
import dev.kstep.desktop.ai.diff.HunkSelection
import dev.kstep.desktop.ai.diff.SelectionBuild
import dev.kstep.desktop.ai.keys.KeyLookup
import dev.kstep.desktop.ai.keys.KeyResolver
import dev.kstep.desktop.ai.keys.KeySource
import dev.kstep.desktop.ai.keys.KeychainBackend
import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.keys.SessionKeyStore
import dev.kstep.desktop.ai.keys.StoreFailure
import dev.kstep.desktop.ai.keys.platformKeychain
import dev.kstep.desktop.ai.llm.LlmClient
import dev.kstep.desktop.ai.prompt.PromptBuilder
import dev.kstep.desktop.ai.provider.AiModel
import dev.kstep.desktop.ai.provider.AiProvider
import dev.kstep.desktop.ai.provider.JdkHttpTransport
import dev.kstep.desktop.ai.provider.KoogAnthropicClient
import dev.kstep.desktop.ai.provider.ProviderCatalog
import dev.kstep.desktop.ai.repair.AiLimits
import dev.kstep.desktop.ai.repair.CandidateCompiler
import dev.kstep.desktop.ai.repair.CompileOutcome
import dev.kstep.desktop.ai.repair.GenerateResult
import dev.kstep.desktop.ai.repair.NotVerifiedReason
import dev.kstep.desktop.ai.repair.ProposalOrigin
import dev.kstep.desktop.ai.repair.RepairMachine
import dev.kstep.desktop.ai.repair.RepairSession
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.ai.repair.Verdict
import dev.kstep.desktop.ai.settings.AiSettings
import dev.kstep.desktop.ai.settings.SettingsStore
import dev.kstep.desktop.edit.EditorText
import dev.kstep.desktop.recent.PromptHistory
import dev.kstep.desktop.recent.PromptHistoryStore
import dev.kstep.desktop.run.WorkerSandbox
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

enum class KeyStatus { PRESENT, MISSING, UNKNOWN }

/** Whether this system has a worker sandbox for the candidate check (ADR-0059); `CHECKING` until detected. */
enum class SandboxStatus { CHECKING, AVAILABLE, UNAVAILABLE }

/** Everything the AI panel shows apart from the repair state (ADR-0056). Never holds a key. */
data class AiUiState(
    val settings: AiSettings,
    val provider: AiProvider,
    val model: AiModel,
    val keychainName: String?,
    val keyStatus: KeyStatus,
    val hasConsent: Boolean,
    /** One short line with a `KSTEP-D-xxx` code (e.g. the key store refused), or `null`. */
    val notice: String? = null,
    val busyKeyStore: Boolean = false,
    val sandbox: SandboxStatus = SandboxStatus.CHECKING,
)

/**
 * Glue between the pure AI module and the window (ADR-0056). Compose-free; the UI observes [ui] and [repair]. All key
 * store access runs on the IO dispatcher (the first macOS access shows a system dialog).
 */
class AiController(
    private val settingsStore: SettingsStore,
    private val keychain: KeychainBackend,
    private val session: SessionKeyStore,
    private val env: (String) -> String? = System::getenv,
    compiler: CandidateCompiler,
    private val sandbox: () -> WorkerSandbox = { WorkerSandbox.Unavailable("not detected") },
    private val limits: AiLimits = AiLimits(maxScriptBytes = EditorText.MAX_BYTES),
    private val clientFactory: (AiModel, Secret) -> LlmClient = { m, k ->
        KoogAnthropicClient(m.id, k, JdkHttpTransport(), limits)
    },
    private val historyStore: PromptHistoryStore = PromptHistoryStore(),
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolver = KeyResolver(keychain, session, env)

    @Volatile private var current: Secret? = null

    @Volatile private var pendingClient: LlmClient? = null

    @Volatile private var currentOwned = false

    private var settings: AiSettings = settingsStore.load()

    private val repairSession =
        RepairSession(
            RepairMachine(limits, PromptBuilder(limits)) { listOfNotNull(current) },
            limits,
            { pendingClient },
            compiler,
            { settingsStore.hasConsent(settings, provider()) },
            scope,
        )

    val repair: StateFlow<RepairState> get() = repairSession.state

    @Volatile private var sandboxStatus = SandboxStatus.CHECKING

    private val state = MutableStateFlow(computeUi(KeyStatus.UNKNOWN, null))
    val ui: StateFlow<AiUiState> get() = state

    // The in-memory list is authoritative (guarded by historyLock); the file always gets the whole list (ADR-0069).
    private val historyLock = Any()
    private var historyValue = PromptHistory.empty()
    private val historyFlow = MutableStateFlow<List<String>>(emptyList())

    /** Past prompts, newest first; never holds a key or an answer. */
    val history: StateFlow<List<String>> get() = historyFlow

    val sandboxAvailable: Boolean get() = sandboxStatus == SandboxStatus.AVAILABLE

    init {
        scope.launch {
            val loaded = historyStore.load()
            synchronized(historyLock) {
                // A prompt recorded before the load finished stays first.
                historyValue = PromptHistory.of(historyValue.entries + loaded.entries)
                historyFlow.value = historyValue.entries
            }
        }
        // The detection runs one probe process: once per session, never on the UI thread.
        scope.launch {
            sandboxStatus =
                if (sandbox() is WorkerSandbox.Unavailable) SandboxStatus.UNAVAILABLE else SandboxStatus.AVAILABLE
            // Only the sandbox field: a full publish() would reset a notice or busy flag set meanwhile.
            state.update { it.copy(sandbox = sandboxStatus) }
        }
    }

    val limitsInUse: AiLimits get() = limits

    private fun provider(): AiProvider = ProviderCatalog.byId(settings.providerId) ?: AiProvider.ANTHROPIC

    private fun computeUi(
        keyStatus: KeyStatus,
        notice: String?,
        busy: Boolean = false,
    ): AiUiState {
        val p = provider()
        return AiUiState(
            settings,
            p,
            ProviderCatalog.modelById(p, settings.modelId),
            keychain.takeIf { it.isAvailable() }?.displayName,
            keyStatus,
            settingsStore.hasConsent(settings, p),
            notice,
            busy,
            sandboxStatus,
        )
    }

    private fun publish(
        keyStatus: KeyStatus = state.value.keyStatus,
        notice: String? = null,
        busy: Boolean = false,
    ) {
        state.value = computeUi(keyStatus, notice, busy)
    }

    /** Looks up whether a key is available for the chosen source, without reading its value where avoidable. */
    fun refresh() {
        scope.launch {
            val p = provider()
            val status =
                when (settings.keySource) {
                    KeySource.KEYCHAIN ->
                        when (keychain.has(p)) {
                            true -> KeyStatus.PRESENT
                            false -> KeyStatus.MISSING
                            null -> KeyStatus.UNKNOWN
                        }
                    KeySource.SESSION -> if (session.get(p) != null) KeyStatus.PRESENT else KeyStatus.MISSING
                    KeySource.ENVIRONMENT ->
                        if (!env(p.envVar).isNullOrBlank()) KeyStatus.PRESENT else KeyStatus.MISSING
                }
            publish(status)
        }
    }

    fun setModel(id: String) {
        settings = settings.copy(modelId = id)
        persist()
        publish()
    }

    fun setKeySource(source: KeySource) {
        settings = settings.copy(keySource = source)
        persist()
        refresh()
        publish()
    }

    /** Stores [key] in the chosen source (the key store or this session); the caller's array is zeroed. */
    fun saveKey(
        key: CharArray,
        source: KeySource,
    ) {
        val secret = Secret.of(key)
        val p = provider()
        settings = settings.copy(keySource = source)
        persist()
        publish(busy = source == KeySource.KEYCHAIN)
        scope.launch {
            when (source) {
                KeySource.SESSION -> {
                    session.put(p, secret)
                    publish(KeyStatus.PRESENT)
                }
                KeySource.KEYCHAIN -> {
                    val failure = keychain.put(p, secret)
                    secret.wipe()
                    if (failure == null) {
                        publish(KeyStatus.PRESENT)
                    } else {
                        publish(KeyStatus.UNKNOWN, storeNotice(failure))
                    }
                }
                KeySource.ENVIRONMENT -> {
                    secret.wipe()
                    publish()
                }
            }
        }
    }

    fun removeKey() {
        val p = provider()
        scope.launch {
            session.clear(p)
            val failure = if (keychain.isAvailable()) keychain.delete(p) else null
            if (failure == null) publish(KeyStatus.MISSING) else publish(KeyStatus.UNKNOWN, storeNotice(failure))
        }
    }

    private fun storeNotice(f: StoreFailure): String =
        "${AiErrorCodes.AI_KEY_STORE_FAILED}: the key store " +
            when (f) {
                StoreFailure.UNAVAILABLE -> "is not available"
                StoreFailure.DENIED -> "refused access"
                StoreFailure.TIMEOUT -> "did not answer in time"
                StoreFailure.ERROR -> "reported an error"
            }

    fun grantConsent() {
        settings = settingsStore.grant(settings, provider())
        persist()
        publish()
    }

    fun revokeConsent() {
        settings = settingsStore.revoke(settings, provider())
        persist()
        publish()
    }

    private fun persist() {
        try {
            settingsStore.save(settings)
        } catch (_: Exception) {
            logger.warn { "AI settings could not be saved" }
        }
    }

    /** Starts a run. The consent must already be granted ([AiUiState.hasConsent]); without it nothing is sent. */
    fun generate(
        prompt: String,
        baseText: String,
        includeScript: Boolean,
    ) {
        scope.launch {
            val p = provider()
            when (val lookup = resolver.resolve(p, settings.keySource)) {
                is KeyLookup.Found -> {
                    val old = current
                    val oldClient = pendingClient
                    val oldOwned = currentOwned
                    val newOwned = settings.keySource != KeySource.SESSION // the session store owns its own key
                    current = lookup.secret
                    currentOwned = newOwned
                    pendingClient = clientFactory(ProviderCatalog.modelById(p, settings.modelId), lookup.secret)
                    val result = repairSession.generate(prompt, baseText, includeScript)
                    if (result == GenerateResult.Started) {
                        // generate() has cancelled the previous run, so nobody reads the old key any more
                        if (oldOwned && old !== lookup.secret) old?.wipe()
                    } else {
                        // Nothing started and the previous run (if any) is still going: keep its key and client.
                        current = old
                        pendingClient = oldClient
                        currentOwned = oldOwned
                        if (newOwned && old !== lookup.secret) lookup.secret.wipe()
                    }
                    // Publish first, then write the history: the disk write must not delay the UI state, or a later
                    // call's notice can be overwritten by this call's late publish.
                    when (result) {
                        GenerateResult.Started -> publish(KeyStatus.PRESENT)
                        GenerateResult.NoConsent -> publish(notice = "Consent is required before anything is sent")
                        GenerateResult.NoKey -> publish(KeyStatus.MISSING, "No API key set")
                        GenerateResult.NotAllowed -> publish(notice = "The request is empty or too large")
                    }
                    if (result == GenerateResult.Started) recordHistory(prompt)
                }
                KeyLookup.Missing -> publish(KeyStatus.MISSING, "No API key set")
                is KeyLookup.StoreFailed -> publish(KeyStatus.UNKNOWN, storeNotice(lookup.reason))
            }
        }
    }

    private fun recordHistory(prompt: String) {
        // Check the text that would really be stored (control/bidi characters removed): a key with an invisible
        // character inserted must not slip past the check on the raw prompt.
        val stored = PromptHistory.sanitize(prompt) ?: return
        val key = current
        if (key != null && (key.occursIn(prompt) || key.occursIn(stored))) return
        synchronized(historyLock) {
            historyValue = historyValue.record(prompt)
            historyFlow.value = historyValue.entries
            historyStore.save(historyValue)
        }
    }

    fun clearHistory() {
        scope.launch {
            synchronized(historyLock) {
                historyValue = PromptHistory.empty()
                historyFlow.value = emptyList()
                historyStore.clear()
            }
        }
    }

    /**
     * Applies a chosen subset of the proposal's change groups (ADR-0069). A full selection is the plain apply path; a
     * real subset is built, re-checked in the worker sandbox and applied only if it COMPILES. Without a sandbox, or on
     * any doubt, nothing is applied (fail-closed). [applyIf] compares the editor text with the one the plan was made
     * from and applies atomically.
     */
    fun applySelection(
        proposal: RepairState.Proposal,
        plan: HunkPlan.Ready,
        selected: Set<Int>,
        applyIf: (expected: String, text: String) -> Boolean,
    ) {
        if (selected.isEmpty()) return
        scope.launch {
            fun fail(
                code: String,
                message: String,
            ) = repairSession.transition(
                proposal,
                RepairState.Failed(code, message, proposal.attempts, proposal),
            )
            if (HunkSelection.isAll(plan, selected)) {
                if (applyIf(plan.old, plan.new)) {
                    repairSession.transition(proposal, RepairState.Idle)
                } else {
                    fail(AiErrorCodes.AI_EDITOR_CHANGED, "the editor changed; nothing was applied")
                }
                return@launch
            }
            if (!sandboxAvailable) {
                fail(AiErrorCodes.AI_PARTIAL_NEEDS_SANDBOX, "choosing single changes needs the worker sandbox")
                return@launch
            }
            val buffer =
                try {
                    HunkSelection.build(plan.old, plan.new, plan, selected, limits.maxScriptBytes)
                } catch (_: IllegalArgumentException) {
                    fail(AiErrorCodes.AI_EDITOR_CHANGED, "the changes no longer match the editor text")
                    return@launch
                }
            val text =
                when (buffer) {
                    is SelectionBuild.Built -> buffer.text
                    SelectionBuild.TooLarge -> {
                        fail(AiErrorCodes.AI_LIMIT_REACHED, "the chosen changes make the script too large")
                        return@launch
                    }
                }
            repairSession.checkSelection(proposal, text, selected, plan.hunks.size, plan.old) { outcome ->
                selectionResult(outcome, proposal, plan, selected, text, applyIf)
            }
        }
    }

    private fun selectionResult(
        outcome: CompileOutcome,
        proposal: RepairState.Proposal,
        plan: HunkPlan.Ready,
        selected: Set<Int>,
        buffer: String,
        applyIf: (String, String) -> Boolean,
    ): RepairState {
        fun failed(
            code: String,
            message: String,
        ) = RepairState.Failed(code, message, proposal.attempts, proposal)

        fun selection(
            verdict: Verdict,
            why: NotVerifiedReason? = null,
        ) = RepairState.Proposal(
            buffer,
            plan.old,
            verdict,
            proposal.attempts,
            proposal.attemptsUsed,
            why,
            ProposalOrigin.SELECTION,
            proposal,
        )
        return when (outcome) {
            CompileOutcome.Ok ->
                if (applyIf(plan.old, buffer)) {
                    RepairState.Idle
                } else {
                    failed(AiErrorCodes.AI_EDITOR_CHANGED, "the editor changed during the check; nothing was applied")
                }
            is CompileOutcome.Errors -> selection(Verdict.DOES_NOT_COMPILE)
            is CompileOutcome.Unverifiable ->
                if (outcome.why == NotVerifiedReason.SANDBOX_UNAVAILABLE) {
                    failed(AiErrorCodes.AI_PARTIAL_NEEDS_SANDBOX, "choosing single changes needs the worker sandbox")
                } else {
                    selection(Verdict.NOT_VERIFIED, outcome.why)
                }
            is CompileOutcome.Refused -> failed(outcome.code, outcome.message)
        }
    }

    /** "Apply anyway" for a subset the check could not confirm; the user has seen the verdict. */
    fun applyChecked(
        p: RepairState.Proposal,
        applyIf: (expected: String, text: String) -> Boolean,
    ) {
        if (p.origin != ProposalOrigin.SELECTION) return
        if (applyIf(p.baseText, p.script)) {
            discard()
        } else {
            repairSession.transition(
                p,
                RepairState.Failed(
                    AiErrorCodes.AI_EDITOR_CHANGED,
                    "the editor changed; nothing was applied",
                    p.attempts,
                    p.original,
                ),
            )
        }
    }

    /** Returns from a subset result or a failed subset check to the model's proposal. */
    fun backToProposal() {
        val cur = repair.value
        val target =
            when (cur) {
                is RepairState.Failed -> cur.back
                is RepairState.Proposal -> cur.original
                else -> null
            } ?: return
        repairSession.transition(cur, target)
    }

    fun stop() = repairSession.stop()

    /** Drops a proposal or a failure and returns to the idle panel. */
    fun discard() = repairSession.reset()

    override fun close() {
        repairSession.close()
        session.wipeAll()
        current?.wipe()
        scope.cancel()
    }

    companion object {
        fun production(
            compiler: CandidateCompiler,
            sandbox: () -> WorkerSandbox = { WorkerSandbox.Unavailable("not detected") },
        ): AiController =
            AiController(SettingsStore(), platformKeychain(), SessionKeyStore(), compiler = compiler, sandbox = sandbox)
    }
}
