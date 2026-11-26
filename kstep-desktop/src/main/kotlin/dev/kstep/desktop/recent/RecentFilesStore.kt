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
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

private val logger = KotlinLogging.logger {}

/**
 * Persists the [RecentList] as `recent.json` in the desktop config folder (ADR-0066). The file is untrusted on load:
 * at most 128 KiB, version 1, at most [RecentList.MAX_ENTRIES] entries, each with a known kind and an absolute,
 * normalized path without control characters; a script path must end in `.kstep.kts`; a note (ADR-0083) must end in `.md` and carry an absolute workspace `root` above it. The file system is NOT touched
 * for the entries (a missing file is found when it is clicked). Writes are atomic, mode 0600 where supported, and every
 * failure is swallowed.
 */
class RecentFilesStore(
    private val dir: Path = defaultConfigDir(),
    private val caseInsensitive: Boolean = RecentList.osCaseInsensitive(),
) {
    private val file: Path get() = dir.resolve(FILE_NAME)

    fun load(): RecentList =
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES) {
                RecentList.empty(caseInsensitive)
            } else {
                val root = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
                val version = (root?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.content
                val entries = root?.get("entries") as? JsonArray
                if (version != "1" || entries == null) {
                    RecentList.empty(caseInsensitive)
                } else {
                    RecentList.of(entries.take(RecentList.MAX_ENTRIES).mapNotNull(::entry), caseInsensitive)
                }
            }
        } catch (_: Exception) {
            RecentList.empty(caseInsensitive)
        }

    /** Never throws. */
    fun save(list: RecentList) {
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
                        "recent",
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createTempFile(dir, "recent", ".tmp")
                }
            val body =
                buildJsonObject {
                    put("version", 1)
                    put(
                        "entries",
                        buildJsonArray {
                            list.entries.forEach {
                                add(
                                    buildJsonObject {
                                        put(
                                            "kind",
                                            when (it.kind) {
                                                RecentKind.SCRIPT -> "script"
                                                RecentKind.FOLDER -> "folder"
                                                RecentKind.NOTE -> "note"
                                            },
                                        )
                                        put("path", it.path.toString())
                                        if (it.kind == RecentKind.NOTE && it.root != null) {
                                            put("root", it.root.toString())
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            Files.writeString(tmp, body.toString())
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            logger.debug { "the recent list could not be saved" } // no path in the log
        } finally {
            try {
                tmp?.let { Files.deleteIfExists(it) }
            } catch (_: Exception) {
                // nothing to do
            }
        }
    }

    private fun entry(element: kotlinx.serialization.json.JsonElement): RecentEntry? {
        val obj = element as? JsonObject ?: return null
        val kind =
            when (text(obj, "kind")) {
                "script" -> RecentKind.SCRIPT
                "folder" -> RecentKind.FOLDER
                "note" -> RecentKind.NOTE
                else -> return null
            }
        val path = absolutePath(text(obj, "path")) ?: return null
        if (kind == RecentKind.SCRIPT && !hasSuffix(path, SCRIPT_SUFFIX)) return null
        if (kind != RecentKind.NOTE) return RecentEntry(path, kind)
        // a note needs its workspace folder: an absolute, normalized folder path above the note
        val root = absolutePath(text(obj, "root")) ?: return null
        if (!hasSuffix(path, NOTE_SUFFIX) || path == root || !path.startsWith(root)) return null
        return RecentEntry(path, kind, root)
    }

    private fun hasSuffix(
        path: Path,
        suffix: String,
    ): Boolean = path.toString().lowercase(Locale.ROOT).endsWith(suffix)

    private fun absolutePath(raw: String?): Path? {
        if (raw == null ||
            raw.isEmpty() ||
            raw.length > MAX_PATH_CHARS ||
            raw.any { it.code <= 0x1F || it.code == 0x7F }
        ) {
            return null
        }
        val path =
            try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return null
            }
        return if (path.isAbsolute && path == path.normalize()) path else null
    }

    private fun text(
        obj: JsonObject,
        key: String,
    ): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val FILE_NAME = "recent.json"
        const val MAX_BYTES: Long = 128L * 1024
        private const val MAX_PATH_CHARS = 4096
        private const val SCRIPT_SUFFIX = ".kstep.kts"
        private const val NOTE_SUFFIX = ".md"
    }
}
