package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopErrorCodes
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

private val logger = KotlinLogging.logger {}

sealed interface WriteResult {
    data class Written(
        val bytes: Long,
    ) : WriteResult

    /** The target exists and the user has not confirmed the replacement (also the case that appeared after the check). */
    data class NeedsReplace(
        val existingBytes: Long,
    ) : WriteResult

    data object Cancelled : WriteResult

    data class Failed(
        val code: String,
        val message: String,
    ) : WriteResult
}

/**
 * Moves the validated worker output to the user's target crash-safely (ADR-0057): a temp file in the TARGET directory
 * (so the move stays on one file system), copied in chunks, `fsync`ed, then an atomic move. No non-atomic fallback: a
 * failure leaves the target untouched and removes the temp file. Only a code, a format-free size and a file name are
 * logged, never a path.
 *
 * Between the last check and the move another process can still replace the target (a window no portable API closes);
 * this is accepted and documented in the ADR. [beforeMove] is a test seam for exactly that window.
 */
class ExportAtomicWriter(
    private val beforeMove: () -> Unit = {},
    private val chunkBytes: Int = CHUNK_BYTES,
) {
    fun write(
        source: Path,
        target: Path,
        replaceConfirmed: Boolean,
        onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean,
    ): WriteResult {
        var temp: Path? = null
        try {
            val dir =
                target.parent ?: return failed(DesktopErrorCodes.EXPORT_TARGET_INVALID, "the target has no folder")
            temp = createTemp(dir)
            val bytes = copy(source, temp, onProgress, isCancelled) ?: return WriteResult.Cancelled
            beforeMove()
            if (isCancelled()) return WriteResult.Cancelled
            val existing = attributes(target)
            if (existing != null) {
                if (existing.isSymbolicLink || !existing.isRegularFile) {
                    return failed(DesktopErrorCodes.EXPORT_TARGET_INVALID, "the target is not a regular file")
                }
                if (!replaceConfirmed) return WriteResult.NeedsReplace(existing.size())
            }
            if (replaceConfirmed) {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            }
            logger.info { "export written bytes=$bytes" }
            return WriteResult.Written(bytes)
        } catch (_: FileAlreadyExistsException) {
            return WriteResult.NeedsReplace(attributes(target)?.size() ?: 0)
        } catch (_: AtomicMoveNotSupportedException) {
            return failed(
                DesktopErrorCodes.EXPORT_WRITE_FAILED,
                "this file system cannot replace the file atomically; choose a folder on a local disk",
            )
        } catch (_: AccessDeniedException) {
            return failed(DesktopErrorCodes.EXPORT_TARGET_NOT_WRITABLE, "the folder or file is not writable")
        } catch (e: IOException) {
            logger.warn { "export write failed (${e::class.simpleName})" }
            return failed(DesktopErrorCodes.EXPORT_WRITE_FAILED, "the file could not be written (disk full or in use?)")
        } finally {
            temp?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (_: IOException) {
                    logger.warn { "could not remove a temporary export file" }
                }
            }
        }
    }

    private fun failed(
        code: String,
        message: String,
    ) = WriteResult.Failed(code, message)

    private fun attributes(path: Path): BasicFileAttributes? =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }

    private fun createTemp(dir: Path): Path {
        val name = ".kstep-export-" + randomHex() + ".tmp"
        val path = dir.resolve(name)
        if ("posix" in dir.fileSystem.supportedFileAttributeViews()) {
            Files.createFile(
                path,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-r--r--"),
                ),
            )
        } else {
            Files.createFile(path)
        }
        return path
    }

    /** Copies in chunks, checking [isCancelled] between them and reporting after each written block; `null` if stopped. */
    private fun copy(
        source: Path,
        temp: Path,
        onProgress: (written: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long? {
        var total = 0L
        val expected =
            try {
                Files.size(source)
            } catch (_: IOException) {
                0L
            }
        FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileChannel.open(temp, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteBuffer.allocate(chunkBytes)
                while (true) {
                    if (isCancelled()) return null
                    buffer.clear()
                    if (input.read(buffer) < 0) break
                    buffer.flip()
                    while (buffer.hasRemaining()) total += output.write(buffer)
                    // a progress callback never breaks an export
                    try {
                        onProgress(total, expected)
                    } catch (_: Exception) {
                        // ignored
                    }
                }
                output.force(true)
            }
        }
        return total
    }

    private fun randomHex(): String {
        val bytes = ByteArray(RANDOM_BYTES).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val CHUNK_BYTES: Int = 1 shl 20
        private const val RANDOM_BYTES = 4
    }
}
