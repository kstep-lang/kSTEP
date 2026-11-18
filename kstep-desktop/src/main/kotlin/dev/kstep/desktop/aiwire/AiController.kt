package dev.kstep.desktop.aiwire

import dev.kstep.desktop.ai.AiErrorCodes
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
import dev.kstep.desktop.ai.repair.GenerateResult
import dev.kstep.desktop.ai.repair.RepairMachine
import dev.kstep.desktop.ai.repair.RepairSession
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.ai.settings.AiSettings
import dev.kstep.desktop.ai.settings.SettingsStore
import dev.kstep.desktop.edit.EditorText
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

enum class KeyStatus { PRESENT, MISSING, UNKNOWN }

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
    private val limits: AiLimits = AiLimits(maxScriptBytes = EditorText.MAX_BYTES),
    private val clientFactory: (AiModel, Secret) -> LlmClient = { m, k ->
        KoogAnthropicClient(m.id, k, JdkHttpTransport(), limits)
    },
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

    private val state = MutableStateFlow(computeUi(KeyStatus.UNKNOWN, null))
    val ui: StateFlow<AiUiState> get() = state

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
                    when (result) {
                        GenerateResult.Started -> publish(KeyStatus.PRESENT)
                        GenerateResult.NoConsent -> publish(notice = "Consent is required before anything is sent")
                        GenerateResult.NoKey -> publish(KeyStatus.MISSING, "No API key set")
                        GenerateResult.NotAllowed -> publish(notice = "The request is empty or too large")
                    }
                }
                KeyLookup.Missing -> publish(KeyStatus.MISSING, "No API key set")
                is KeyLookup.StoreFailed -> publish(KeyStatus.UNKNOWN, storeNotice(lookup.reason))
            }
        }
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
        fun production(compiler: CandidateCompiler): AiController =
            AiController(SettingsStore(), platformKeychain(), SessionKeyStore(), compiler = compiler)
    }
}
