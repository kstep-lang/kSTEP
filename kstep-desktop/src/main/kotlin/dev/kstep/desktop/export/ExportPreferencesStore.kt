package dev.kstep.desktop.export

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
import java.security.MessageDigest
import java.time.Instant

/** What the export dialog remembers between sessions (ADR-0063, ADR-0070). */
data class ExportPreferences(
    val lastFormat: ExportFormat? = null,
    val lastDir: Path? = null,
    /** Per script, keyed by [ExportPreferencesStore.scriptKey]; at most [ExportPreferencesStore.MAX_SCRIPTS]. */
    val scripts: Map<String, ScriptExportPreference> = emptyMap(),
)

/** What was last used for one script: the format, the folder and the declared target's file name (never a typed name). */
data class ScriptExportPreference(
    val format: ExportFormat? = null,
    val dir: Path? = null,
    val target: String? = null,
    val usedAt: Long = 0,
)

/**
 * Persists [ExportPreferences] as `export.json` in the desktop config folder (schema version 2; a file without a
 * version is the old format with only the last format and folder). The file is untrusted on load (another program may
 * have written it): at most 64 KiB, formats must be known worker ids, folders absolute, existing directories reached
 * without a symbolic link, script keys 64 lowercase hex characters, target names valid declared names; anything else is
 * dropped field by field. At most 32 scripts are kept (the most recently used). Writes are atomic (temporary file plus
 * move), mode 0600 where supported, and every I/O failure is swallowed: remembering must never break an export.
 */
class ExportPreferencesStore(
    private val dir: Path = defaultConfigDir(),
    private val clock: () -> Long = { Instant.now().epochSecond },
) {
    private val file: Path get() = dir.resolve(FILE_NAME)

    fun load(): ExportPreferences =
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
                ExportPreferences()
            } else {
                val parsed = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                val version = (parsed?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                when (version) {
                    null, 1L -> parseGlobal(parsed)
                    2L -> parseGlobal(parsed).copy(scripts = parseScripts(parsed?.get("scripts") as? JsonObject))
                    else -> ExportPreferences()
                }
            }
        } catch (_: Exception) {
            ExportPreferences()
        } catch (_: StackOverflowError) {
            ExportPreferences()
        }

    private fun parseGlobal(parsed: JsonObject?) =
        ExportPreferences(
            lastFormat = text(parsed, "lastFormat")?.let(::formatOf),
            lastDir = text(parsed, "lastDir")?.let { validDir(it, MAX_PATH_CHARS) },
        )

    private fun formatOf(id: String): ExportFormat? = ExportFormat.ALL.firstOrNull { it.workerId == id }

    private fun parseScripts(obj: JsonObject?): Map<String, ScriptExportPreference> {
        if (obj == null) return emptyMap()
        val now = clock()
        val parsed = LinkedHashMap<String, ScriptExportPreference>()
        for ((key, value) in obj) {
            if (!KEY_PATTERN.matches(key)) continue
            val entry = parseEntry(value as? JsonObject, now) ?: continue
            parsed[key] = entry
        }
        return prune(parsed)
    }

    private fun parseEntry(
        obj: JsonObject?,
        now: Long,
    ): ScriptExportPreference? {
        if (obj == null) return null
        val format = text(obj, "format")?.let(::formatOf)
        val dir = text(obj, "dir")?.let { validDir(it, MAX_SCRIPT_DIR_CHARS) }
        // a target name only makes sense with its format; it is never cleaned up, only accepted or dropped
        val target = text(obj, "target")?.takeIf { format != null && ExportNaming.isValidDeclaredName(it, format) }
        val usedAt =
            (obj["usedAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.coerceIn(
                0,
                now + MAX_FUTURE_SECONDS,
            )
                ?: 0L
        if (format == null && dir == null && target == null) return null
        return ScriptExportPreference(format, dir, target, usedAt)
    }

    /**
     * Adds or updates one script's entry (when [scriptKey] is given) and the global values, on top of the file as it is
     * NOW (another desktop instance may have written to it), then saves. Only called after a successful export.
     */
    fun remember(
        lastFormat: ExportFormat?,
        lastDir: Path?,
        scriptKey: String? = null,
        entry: ScriptExportPreference? = null,
    ): ExportPreferences {
        val current = load()
        val scripts = LinkedHashMap(current.scripts)
        if (scriptKey != null && entry != null && KEY_PATTERN.matches(scriptKey)) scripts[scriptKey] = entry
        val merged =
            ExportPreferences(lastFormat ?: current.lastFormat, lastDir ?: current.lastDir, prune(scripts))
        save(merged)
        return merged
    }

    /** Never throws. */
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
            val scripts = prune(preferences.scripts)
            val body =
                buildJsonObject {
                    put("version", 2)
                    preferences.lastFormat?.let { put("lastFormat", it.workerId) }
                    preferences.lastDir?.let { put("lastDir", it.toString()) }
                    put(
                        "scripts",
                        buildJsonObject {
                            for ((key, entry) in scripts) {
                                put(
                                    key,
                                    buildJsonObject {
                                        entry.format?.let { put("format", it.workerId) }
                                        entry.dir?.let { put("dir", it.toString()) }
                                        entry.target?.let { put("target", it) }
                                        put("usedAt", entry.usedAt)
                                    },
                                )
                            }
                        },
                    )
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

    internal fun validDir(
        text: String,
        maxChars: Int,
    ): Path? {
        if (text.isEmpty() || text.length > maxChars || text.indexOf('\u0000') >= 0) return null
        val path = Path.of(text)
        if (!path.isAbsolute) return null
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return null
        // a symbolic link anywhere in the path leads somewhere the user did not pick: drop it
        return if (path.toRealPath() == path.normalize()) path.normalize() else null
    }

    companion object {
        const val FILE_NAME = "export.json"
        const val MAX_BYTES: Long = 64L * 1024
        const val MAX_SCRIPTS = 32
        private const val MAX_PATH_CHARS = 4096
        private const val MAX_SCRIPT_DIR_CHARS = 1024
        private const val MAX_FUTURE_SECONDS = 86_400L
        private val KEY_PATTERN = Regex("^[0-9a-f]{64}$")

        /**
         * The key of a script: SHA-256 of its absolute, normalized path (not resolved through links, so it also works
         * for a script that is gone). A pseudonym, not a secret.
         */
        fun scriptKey(script: Path): String {
            val bytes =
                script
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }

        /** The 32 most recently used entries; ties are broken by key so the result is stable. */
        fun prune(scripts: Map<String, ScriptExportPreference>): Map<String, ScriptExportPreference> =
            if (scripts.size <= MAX_SCRIPTS) {
                scripts
            } else {
                scripts.entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, ScriptExportPreference>> {
                            it.value.usedAt
                        }.thenBy { it.key },
                    ).take(MAX_SCRIPTS)
                    .associate { it.key to it.value }
            }
    }
}
