package dev.kstep.desktop.export

import dev.kstep.desktop.ai.settings.defaultConfigDir
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** What the export dialog remembers between sessions (ADR-0063): the last format and the last folder, nothing else. */
data class ExportPreferences(
    val lastFormat: ExportFormat? = null,
    val lastDir: Path? = null,
)

/**
 * Persists [ExportPreferences] as `export.json` in the desktop config folder. The file is untrusted on load (another
 * program may have written it): at most 4 KiB, the format must be one of the known worker ids, the folder must be an
 * absolute, existing directory that is not reached through a symbolic link, else the value is dropped silently. Writes
 * are atomic (temporary file plus move), mode 0600 where supported, and every I/O failure is swallowed: remembering
 * the last folder must never break an export.
 */
class ExportPreferencesStore(
    private val dir: Path = defaultConfigDir(),
) {
    private val file: Path get() = dir.resolve(FILE_NAME)

    fun load(): ExportPreferences =
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
                ExportPreferences()
            } else {
                val parsed = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                ExportPreferences(
                    lastFormat =
                        text(parsed, "lastFormat")?.let { id ->
                            ExportFormat.ALL.firstOrNull {
                                it.workerId ==
                                    id
                            }
                        },
                    lastDir = text(parsed, "lastDir")?.let(::validDir),
                )
            }
        } catch (_: Exception) {
            ExportPreferences()
        }

    /** Only called after a successful export. Never throws. */
    fun save(preferences: ExportPreferences) {
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
                        "export",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createTempFile(dir, "export", ".tmp")
                }
            val body =
                buildJsonObject {
                    preferences.lastFormat?.let { put("lastFormat", it.workerId) }
                    preferences.lastDir?.let { put("lastDir", it.toString()) }
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
        const val FILE_NAME = "export.json"
        const val MAX_BYTES: Long = 4L * 1024
        private const val MAX_PATH_CHARS = 4096
    }
}
