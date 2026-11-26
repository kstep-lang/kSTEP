package dev.kstep.desktop.settings

import dev.kstep.desktop.ai.settings.defaultConfigDir
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

private val logger = KotlinLogging.logger {}

/**
 * What the window layout remembers (ADR-0067): the share of the width the editor takes, and whether long lines wrap
 * (ADR-0085).
 */
data class LayoutPreferences(
    val editorFraction: Double = LayoutPreferencesStore.DEFAULT,
    val softWrap: Boolean = false,
)

/**
 * Persists [LayoutPreferences] as `layout.json` in the desktop config folder. Untrusted on load: a regular file (no
 * symbolic link) of at most 1 KiB, strict UTF-8, `version` 1, a finite number clamped to 0.20..0.80 and an optional
 * boolean `softWrap`; anything else
 * gives the default. Writes are atomic, 0600/0700 where supported, and never throw.
 */
class LayoutPreferencesStore(
    private val dir: Path = defaultConfigDir(),
) {
    private val file: Path get() = dir.resolve(FILE_NAME)

    fun load(): LayoutPreferences =
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
                LayoutPreferences()
            } else {
                val bytes = Files.newInputStream(file).use { it.readNBytes(MAX_BYTES.toInt() + 1) }
                if (bytes.size > MAX_BYTES) {
                    LayoutPreferences()
                } else {
                    val decoder =
                        Charsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    parse(Json.parseToJsonElement(text) as? JsonObject)
                }
            }
        } catch (_: Exception) {
            LayoutPreferences()
        } catch (_: StackOverflowError) {
            LayoutPreferences()
        }

    private fun parse(obj: JsonObject?): LayoutPreferences {
        val version = (obj?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.content
        if (version != "1") return LayoutPreferences()
        val value =
            (obj["editorFraction"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?: return LayoutPreferences()
        if (!value.isFinite()) return LayoutPreferences()
        // optional (ADR-0085): absent means off; a value that is not a JSON boolean makes the whole file untrusted
        val wrapElement = obj["softWrap"]
        val wrap =
            when {
                wrapElement == null -> false
                wrapElement is JsonPrimitive && !wrapElement.isString ->
                    wrapElement.booleanOrNull
                        ?: return LayoutPreferences()
                else -> return LayoutPreferences()
            }
        return LayoutPreferences(value.coerceIn(MIN, MAX), wrap)
    }

    /** Never throws. */
    fun save(preferences: LayoutPreferences) {
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
                        "layout",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createTempFile(dir, "layout", ".tmp")
                }
            val value = (Math.round(preferences.editorFraction.coerceIn(MIN, MAX) * 1000.0) / 1000.0)
            val body =
                buildJsonObject {
                    put("version", 1)
                    put("editorFraction", value)
                    put("softWrap", preferences.softWrap)
                }
            Files.writeString(tmp, body.toString())
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            logger.debug { "layout save failed (${e::class.simpleName})" }
        } finally {
            try {
                tmp?.let { Files.deleteIfExists(it) }
            } catch (_: Exception) {
                // nothing to do
            }
        }
    }

    companion object {
        const val FILE_NAME = "layout.json"
        const val MAX_BYTES: Long = 1024L
        const val MIN = 0.20
        const val MAX = 0.80
        const val DEFAULT = 0.45
    }
}
