package dev.kstep.desktop.ai.settings

import dev.kstep.desktop.ai.keys.KeySource
import dev.kstep.desktop.ai.provider.AiProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

private val logger = KotlinLogging.logger {}

/** The persisted AI settings. There is NO field for a key, by construction (ADR-0056). */
@Serializable
data class AiSettings(
    val version: Int = 1,
    val providerId: String = AiProvider.ANTHROPIC.id,
    val modelId: String? = null,
    val keySource: KeySource = KeySource.KEYCHAIN,
    val consents: Map<String, Consent> = emptyMap(),
)

@Serializable
data class Consent(
    val host: String,
    val grantedAtEpochSec: Long,
)

class SettingsStore(
    private val dir: Path = defaultConfigDir(),
    private val clock: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file: Path get() = dir.resolve(FILE_NAME)

    fun load(): AiSettings {
        return try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
                if (Files.exists(file)) logger.warn { "AI settings file ignored (not a regular file or too large)" }
                return AiSettings()
            }
            val parsed = json.decodeFromString<AiSettings>(Files.readString(file))
            if (ProviderCatalogLookup.known(parsed.providerId)) parsed else AiSettings()
        } catch (_: Exception) {
            logger.warn { "AI settings file unreadable; using defaults" }
            AiSettings()
        }
    }

    fun save(s: AiSettings) {
        val posix = Files.getFileStore(dirOrCreate()).supportsFileAttributeView("posix")
        val tmp =
            if (posix) {
                Files.createTempFile(
                    dir,
                    "desktop-ai",
                    ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } else {
                Files.createTempFile(dir, "desktop-ai", ".tmp")
            }
        try {
            Files.writeString(tmp, json.encodeToString(AiSettings.serializer(), s))
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun dirOrCreate(): Path {
        if (!Files.isDirectory(dir)) {
            try {
                Files.createDirectories(
                    dir,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
            } catch (_: UnsupportedOperationException) {
                Files.createDirectories(dir)
            }
        }
        return dir
    }

    /** A consent holds only for the host it was given for: a changed host asks again. */
    fun hasConsent(
        s: AiSettings,
        p: AiProvider,
    ): Boolean = s.consents[p.id]?.host == p.host

    fun grant(
        s: AiSettings,
        p: AiProvider,
    ): AiSettings = s.copy(consents = s.consents + (p.id to Consent(p.host, clock().epochSecond)))

    fun revoke(
        s: AiSettings,
        p: AiProvider,
    ): AiSettings = s.copy(consents = s.consents - p.id)

    private object ProviderCatalogLookup {
        fun known(id: String): Boolean = AiProvider.entries.any { it.id == id }
    }

    companion object {
        const val FILE_NAME = "desktop-ai.json"
        const val MAX_BYTES = 64L * 1024
    }
}

fun defaultConfigDir(
    os: String = System.getProperty("os.name").orEmpty(),
    env: (String) -> String? = System::getenv,
    home: String = System.getProperty("user.home").orEmpty(),
): Path {
    val name = os.lowercase()
    return when {
        name.contains("mac") -> Path.of(home, "Library", "Application Support", "kSTEP")
        name.contains("win") -> Path.of(env("APPDATA") ?: Path.of(home, "AppData", "Roaming").toString(), "kSTEP")
        else ->
            env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "kstep") }
                ?: Path.of(home, ".config", "kstep")
    }
}
