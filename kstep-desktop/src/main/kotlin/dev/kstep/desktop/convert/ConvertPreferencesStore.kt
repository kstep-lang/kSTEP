package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertTarget
import dev.kstep.desktop.ai.settings.defaultConfigDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** What the convert dialog remembers between sessions (ADR-0077). */
data class ConvertPreferences(
    val lastOutDir: Path? = null,
    val lastTarget: ConvertTarget? = null,
)

/**
 * Persists [ConvertPreferences] as `convert.json` (schema version 1) in the desktop config folder. The file is
 * untrusted on load: at most 64 KiB, read without following a link; the folder must be absolute, short, free of NUL, an
 * existing directory and reached without a symbolic link; the target must be `okf` or `kts`; an unknown version gives
 * the defaults. Writes are atomic (temporary file plus move), mode 0600 where supported, and every failure is
 * swallowed: remembering must never break a conversion.
 */
class ConvertPreferencesStore(
    private val dir: Path = defaultConfigDir(),
) {
    private val file: Path get() = dir.resolve(FILE_NAME)

    fun load(): ConvertPreferences =
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
                ConvertPreferences()
            } else {
                val parsed = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                val version = (parsed?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                if (version == VERSION) {
                    ConvertPreferences(
                        lastOutDir = text(parsed, "lastOutDir")?.let(::validDir),
                        lastTarget = text(parsed, "lastTarget")?.let(::targetOf),
                    )
                } else {
                    ConvertPreferences()
                }
            }
        } catch (_: Exception) {
            ConvertPreferences()
        } catch (_: StackOverflowError) {
            ConvertPreferences()
        }

    /** Only called after a successful conversion; a null value keeps what the file has NOW. Never throws. */
    fun remember(
        outDir: Path?,
        target: ConvertTarget?,
    ): ConvertPreferences {
        val current = load()
        val merged = ConvertPreferences(outDir ?: current.lastOutDir, target ?: current.lastTarget)
        save(merged)
        return merged
    }

    /** Never throws. */
    fun save(preferences: ConvertPreferences) {
        var tmp: Path? = null
        try {
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
            val posix = Files.getFileStore(dir).supportsFileAttributeView("posix")
            tmp =
                if (posix) {
                    Files.createTempFile(
                        dir,
                        "convert",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createTempFile(dir, "convert", ".tmp")
                }
            val body =
                buildJsonObject {
                    put("version", VERSION)
                    preferences.lastOutDir?.let { put("lastOutDir", it.toString()) }
                    preferences.lastTarget?.let { put("lastTarget", idOf(it)) }
                }
            Files.writeString(tmp, body.toString())
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            // best effort
        } finally {
            try {
                tmp?.let { Files.deleteIfExists(it) }
            } catch (_: Exception) {
                // nothing to do
            }
        }
    }

    private fun idOf(target: ConvertTarget): String =
        when (target) {
            ConvertTarget.OKF -> "okf"
            ConvertTarget.KTS -> "kts"
        }

    private fun targetOf(id: String): ConvertTarget? =
        when (id) {
            "okf" -> ConvertTarget.OKF
            "kts" -> ConvertTarget.KTS
            else -> null
        }

    private fun text(
        obj: JsonObject?,
        key: String,
    ): String? = (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun validDir(text: String): Path? {
        if (text.isEmpty() || text.length > MAX_PATH_CHARS || text.indexOf('\u0000') >= 0) return null
        val path = Path.of(text)
        if (!path.isAbsolute) return null
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
        // a symbolic link anywhere in the path leads somewhere the user did not pick: drop it
        return if (path.toRealPath() == path.normalize()) path.normalize() else null
    }

    companion object {
        const val FILE_NAME = "convert.json"
        const val MAX_BYTES: Long = 64L * 1024
        private const val VERSION = 1L
        private const val MAX_PATH_CHARS = 4096
    }
}
