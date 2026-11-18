package dev.kstep.desktop.ai.keys

import dev.kstep.desktop.ai.provider.AiProvider
import java.nio.file.Files
import java.nio.file.Path

enum class KeySource { KEYCHAIN, SESSION, ENVIRONMENT }

enum class StoreFailure { UNAVAILABLE, DENIED, TIMEOUT, ERROR }

sealed interface KeyLookup {
    data class Found(
        val secret: Secret,
    ) : KeyLookup

    data object Missing : KeyLookup

    data class StoreFailed(
        val reason: StoreFailure,
    ) : KeyLookup
}

/** An operating-system key store. There is no plaintext fallback anywhere (ADR-0056). */
interface KeychainBackend {
    val displayName: String

    fun isAvailable(): Boolean

    /** `null` on success. */
    fun put(
        provider: AiProvider,
        secret: Secret,
    ): StoreFailure?

    fun get(provider: AiProvider): KeyLookup

    /** Tri-state: `null` if the backend does not answer. */
    fun has(provider: AiProvider): Boolean?

    fun delete(provider: AiProvider): StoreFailure?
}

private fun serviceName(p: AiProvider) = "dev.kstep.desktop.ai.${p.id}"

private fun failureOf(r: ProcessResult): StoreFailure =
    when {
        r.timedOut -> StoreFailure.TIMEOUT
        r.exitCode == EXIT_USER_CANCELLED -> StoreFailure.DENIED
        else -> StoreFailure.ERROR
    }

private const val EXIT_USER_CANCELLED = 128
private const val MAC_NOT_FOUND = 44

class MacOsKeychainBackend(
    private val runner: SecretCommandRunner = SecretProcessRunner(),
    private val user: String = System.getProperty("user.name").orEmpty(),
    private val binary: String = "/usr/bin/security",
) : KeychainBackend {
    override val displayName: String = "macOS Keychain"

    override fun isAvailable(): Boolean = Files.isExecutable(Path.of(binary))

    override fun put(
        provider: AiProvider,
        secret: Secret,
    ): StoreFailure? {
        if (secret.length() == 0) return StoreFailure.ERROR
        // `-w` as the last option without a value reads the secret from stdin TWICE (prompt + confirmation); one line
        // would store an empty secret silently (kUML lesson). One service name per provider: `-l` does not filter.
        val input = (secret.reveal() + "\n" + secret.reveal() + "\n").toCharArray()
        val r =
            try {
                runner.run(
                    listOf(
                        binary,
                        "add-generic-password",
                        "-U",
                        "-a",
                        user,
                        "-s",
                        serviceName(provider),
                        "-l",
                        "kSTEP AI (${provider.displayName})",
                        "-w",
                    ),
                    input,
                )
            } finally {
                input.fill('\u0000')
            }
        if (r.exitCode != 0 || r.timedOut) return failureOf(r)
        // read back: the length must match (without logging the content)
        val back = get(provider)
        val ok = back is KeyLookup.Found && back.secret.length() == secret.length()
        (back as? KeyLookup.Found)?.secret?.wipe()
        return if (ok) null else StoreFailure.ERROR
    }

    override fun get(provider: AiProvider): KeyLookup {
        val r = runner.run(listOf(binary, "find-generic-password", "-a", user, "-s", serviceName(provider), "-w"), null)
        return when {
            r.timedOut -> KeyLookup.StoreFailed(StoreFailure.TIMEOUT)
            r.exitCode == MAC_NOT_FOUND -> KeyLookup.Missing
            r.exitCode != 0 -> KeyLookup.StoreFailed(failureOf(r))
            r.stdout.trim().isEmpty() -> KeyLookup.Missing
            else -> KeyLookup.Found(Secret.fromEnv(r.stdout.trimEnd('\n', '\r')))
        }
    }

    override fun has(provider: AiProvider): Boolean? {
        val r = runner.run(listOf(binary, "find-generic-password", "-a", user, "-s", serviceName(provider)), null)
        return when {
            r.timedOut -> null
            r.exitCode == 0 -> true
            r.exitCode == MAC_NOT_FOUND -> false
            else -> null
        }
    }

    override fun delete(provider: AiProvider): StoreFailure? {
        val r = runner.run(listOf(binary, "delete-generic-password", "-a", user, "-s", serviceName(provider)), null)
        return if (r.exitCode == 0 || r.exitCode == MAC_NOT_FOUND) null else failureOf(r)
    }
}

class LinuxSecretToolBackend(
    private val runner: SecretCommandRunner = SecretProcessRunner(),
    private val candidates: List<String> = listOf("/usr/bin/secret-tool", "/usr/local/bin/secret-tool"),
) : KeychainBackend {
    override val displayName: String = "Secret Service"

    private val binary: String? get() = candidates.firstOrNull { Files.isExecutable(Path.of(it)) }

    override fun isAvailable(): Boolean {
        val b = binary ?: return false
        val r = runner.run(listOf(b, "search", "service", "dev.kstep.desktop.ai"), null)
        return !r.timedOut && (r.exitCode == 0 || r.exitCode == 1)
    }

    private fun attrs(p: AiProvider) = listOf("service", "dev.kstep.desktop.ai", "account", p.id)

    override fun put(
        provider: AiProvider,
        secret: Secret,
    ): StoreFailure? {
        val b = binary ?: return StoreFailure.UNAVAILABLE
        if (secret.length() == 0) return StoreFailure.ERROR
        val input = secret.reveal().toCharArray()
        val r =
            try {
                runner.run(listOf(b, "store", "--label=kSTEP AI (${provider.displayName})") + attrs(provider), input)
            } finally {
                input.fill('\u0000')
            }
        return if (r.exitCode == 0 && !r.timedOut) null else failureOf(r)
    }

    override fun get(provider: AiProvider): KeyLookup {
        val b = binary ?: return KeyLookup.StoreFailed(StoreFailure.UNAVAILABLE)
        val r = runner.run(listOf(b, "lookup") + attrs(provider), null)
        return when {
            r.timedOut -> KeyLookup.StoreFailed(StoreFailure.TIMEOUT)
            r.exitCode == 0 && r.stdout.isNotBlank() -> KeyLookup.Found(Secret.fromEnv(r.stdout.trimEnd('\n', '\r')))
            r.exitCode == 0 || r.exitCode == 1 -> KeyLookup.Missing
            else -> KeyLookup.StoreFailed(failureOf(r))
        }
    }

    override fun has(provider: AiProvider): Boolean? =
        when (val g = get(provider)) {
            is KeyLookup.Found -> {
                g.secret.wipe()
                true
            }
            KeyLookup.Missing -> false
            is KeyLookup.StoreFailed -> null
        }

    override fun delete(provider: AiProvider): StoreFailure? {
        val b = binary ?: return StoreFailure.UNAVAILABLE
        val r = runner.run(listOf(b, "clear") + attrs(provider), null)
        return if (!r.timedOut && (r.exitCode == 0 || r.exitCode == 1)) null else failureOf(r)
    }
}

/** Windows, or a headless Linux without D-Bus: no key store; only a session key or an environment variable remain. */
object NoKeychainBackend : KeychainBackend {
    override val displayName: String = "none"

    override fun isAvailable(): Boolean = false

    override fun put(
        provider: AiProvider,
        secret: Secret,
    ): StoreFailure? = StoreFailure.UNAVAILABLE

    override fun get(provider: AiProvider): KeyLookup = KeyLookup.StoreFailed(StoreFailure.UNAVAILABLE)

    override fun has(provider: AiProvider): Boolean? = null

    override fun delete(provider: AiProvider): StoreFailure? = StoreFailure.UNAVAILABLE
}

fun platformKeychain(os: String = System.getProperty("os.name").orEmpty()): KeychainBackend {
    val name = os.lowercase()
    return when {
        name.contains("mac") || name.contains("darwin") -> MacOsKeychainBackend()
        name.contains("linux") -> LinuxSecretToolBackend().takeIf { it.isAvailable() } ?: NoKeychainBackend
        else -> NoKeychainBackend
    }
}

/** Keys that live for one run of the application only. */
class SessionKeyStore {
    private val keys = HashMap<AiProvider, Secret>()

    @Synchronized
    fun put(
        p: AiProvider,
        s: Secret,
    ) {
        keys.put(p, s)?.wipe()
    }

    @Synchronized
    fun get(p: AiProvider): Secret? = keys[p]

    @Synchronized
    fun clear(p: AiProvider) {
        keys.remove(p)?.wipe()
    }

    @Synchronized
    fun wipeAll() {
        keys.values.forEach { it.wipe() }
        keys.clear()
    }
}

class KeyResolver(
    private val keychain: KeychainBackend,
    private val session: SessionKeyStore,
    private val env: (String) -> String? = System::getenv,
) {
    fun resolve(
        p: AiProvider,
        source: KeySource,
    ): KeyLookup =
        when (source) {
            KeySource.KEYCHAIN -> keychain.get(p)
            KeySource.SESSION -> session.get(p)?.let { KeyLookup.Found(it) } ?: KeyLookup.Missing
            KeySource.ENVIRONMENT ->
                env(p.envVar)?.takeIf { it.isNotBlank() }?.let { KeyLookup.Found(Secret.fromEnv(it)) }
                    ?: KeyLookup.Missing
        }
}
