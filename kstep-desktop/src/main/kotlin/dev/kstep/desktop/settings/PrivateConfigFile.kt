package dev.kstep.desktop.settings

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

private val logger = KotlinLogging.logger {}

/**
 * The two file operations the private config files of the desktop share (ADR-0095): a strict read of a small UTF-8 file
 * and an atomic write that is never group- or world-readable. Neither throws; failures are logged without paths.
 */
internal object PrivateConfigFile {
    /**
     * The text of [file], or `null` when it is not a regular file (a symbolic link is not followed), longer than
     * [maxBytes], not strictly valid UTF-8 or not readable.
     */
    fun readStrictUtf8(
        file: Path,
        maxBytes: Long,
    ): String? =
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > maxBytes) {
                null
            } else {
                val bytes =
                    Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes.toInt() + 1) }
                if (bytes.size > maxBytes) {
                    null
                } else {
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                }
            }
        } catch (e: Exception) {
            logger.debug { "private config read failed (${e::class.simpleName})" }
            null
        }

    /**
     * Writes [text] to `dir/name`: the folder is created `rwx------` when it is missing, the temporary file is created
     * `rw-------` where POSIX permissions exist (never widened and narrowed afterwards), then moved over the target
     * atomically. The temporary file is deleted in every case. `false` when anything failed.
     */
    fun writeAtomic(
        dir: Path,
        name: String,
        prefix: String,
        text: String,
    ): Boolean {
        var tmp: Path? = null
        return try {
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
                        prefix,
                        ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                } else {
                    Files.createTempFile(dir, prefix, ".tmp")
                }
            Files.writeString(tmp, text)
            Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            logger.debug { "private config write failed (${e::class.simpleName})" }
            false
        } finally {
            try {
                tmp?.let { Files.deleteIfExists(it) }
            } catch (_: Exception) {
                // nothing to do
            }
        }
    }
}
