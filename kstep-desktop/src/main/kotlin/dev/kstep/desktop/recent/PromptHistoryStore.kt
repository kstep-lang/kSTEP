package dev.kstep.desktop.recent

import dev.kstep.desktop.ai.settings.defaultConfigDir
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

private val logger = KotlinLogging.logger {}

/**
 * Persists the [PromptHistory] as `ai-prompt-history.json` in the desktop config folder (ADR-0069). The file holds
 * prompt text in clear (mode 0600 where supported, not encrypted). Untrusted on load: a regular file (no symlink) of at
 * most [MAX_BYTES], version 1, string entries only, then sanitized and clamped. Writes are atomic; every failure is
 * swallowed and nothing from the history reaches a log.
 */
class PromptHistoryStore(
    private val dir: Path = defaultConfigDir(),
) {
    private val file: Path get() = dir.resolve(FILE_NAME)

    /** Never throws. */
    fun load(): PromptHistory =
        try {
            if (!dir.isAbsolute ||
                !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(file) > MAX_BYTES
            ) {
                PromptHistory.empty()
            } else {
                val root = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                val version = (root?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.content
                val prompts = root?.get("prompts") as? JsonArray
                if (version != "1" || prompts == null) {
                    PromptHistory.empty()
                } else {
                    PromptHistory.of(
                        prompts
                            .take(PromptHistory.MAX_ENTRIES * 2)
                            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                    )
                }
            }
        } catch (_: Exception) {
            PromptHistory.empty()
        }

    /** Never throws. The oldest entries are dropped until the file fits [MAX_BYTES], so the next load accepts it. */
    fun save(history: PromptHistory) {
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
                        "prompts",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createTempFile(dir, "prompts", ".tmp")
                }
            var entries = history.entries
            var body = serialize(entries)
            while (body.toByteArray(Charsets.UTF_8).size > MAX_BYTES && entries.isNotEmpty()) {
                entries = entries.dropLast(1)
                body = serialize(entries)
            }
            Files.writeString(tmp, body)
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            logger.debug { "the prompt history could not be saved" } // never the content
        } finally {
            try {
                tmp?.let { Files.deleteIfExists(it) }
            } catch (_: Exception) {
                // nothing to do
            }
        }
    }

    /** Never throws. */
    fun clear() {
        try {
            Files.deleteIfExists(file)
        } catch (_: Exception) {
            logger.debug { "the prompt history could not be removed" }
        }
    }

    private fun serialize(entries: List<String>): String =
        buildJsonObject {
            put("version", 1)
            put("prompts", buildJsonArray { entries.forEach { add(JsonPrimitive(it)) } })
        }.toString()

    companion object {
        const val FILE_NAME = "ai-prompt-history.json"
        const val MAX_BYTES: Long = 256L * 1024
    }
}
