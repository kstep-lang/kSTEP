package dev.kstep.desktop.edit

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

private val logger = KotlinLogging.logger {}

enum class SaveFailure(
    val message: String,
) {
    READ_ONLY("the file or its folder is read-only"),
    DISK_FULL("the disk is full"),
    ATOMIC_MOVE_UNSUPPORTED("this file system cannot replace the file atomically"),
    IO("the file could not be written"),
}

/**
 * Writes a script crash-safely (ADR-0052): temp file in the same directory, `fsync`, atomic move over the target. The
 * disk is re-hashed first: if it no longer matches [save]'s `expectedBaseHash` nothing is written ([Result.Conflict]).
 * A symbolic link is followed (the target is written, the link survives). There is no non-atomic fallback: a failure
 * leaves the original untouched and removes the temp file. The window between the hash check and the move cannot be
 * closed against another writer (accepted and documented in the ADR). Calls are serialised.
 */
class AtomicSaver(
    private val writer: (Path, ByteArray) -> Unit = ::writeAndSync,
) {
    sealed interface Result {
        class Saved(
            val newHash: ByteArray,
        ) : Result

        /** The disk changed under the editor; [diskHash] is `null` when the file is gone. */
        class Conflict(
            val diskHash: ByteArray?,
        ) : Result

        class Failed(
            val reason: SaveFailure,
        ) : Result
    }

    /** [expectedBaseHash] is the hash of the version the editor last saw, `null` if it knows the file is gone. */
    @Synchronized
    fun save(
        path: Path,
        bytes: ByteArray,
        expectedBaseHash: ByteArray?,
        force: Boolean = false,
    ): Result {
        val started = System.nanoTime()
        val result = doSave(path, bytes, expectedBaseHash, force)
        logger.info {
            "save result=${result::class.simpleName} durationMs=${(System.nanoTime() - started) / 1_000_000}"
        }
        return result
    }

    private fun doSave(
        path: Path,
        bytes: ByteArray,
        expectedBaseHash: ByteArray?,
        force: Boolean,
    ): Result {
        var temp: Path? = null
        try {
            val target =
                try {
                    path.toRealPath()
                } catch (_: NoSuchFileException) {
                    path.toAbsolutePath().normalize().let { it.parent.toRealPath().resolve(it.fileName) }
                }
            val dir = target.parent
            val exists = Files.exists(target)
            if (exists && !Files.isWritable(target)) return Result.Failed(SaveFailure.READ_ONLY)
            if (!Files.isWritable(dir)) return Result.Failed(SaveFailure.READ_ONLY)
            if (!force) {
                val diskHash = if (exists) hashOf(target) else null
                val matches =
                    when {
                        !exists -> expectedBaseHash == null
                        expectedBaseHash == null -> false
                        else -> diskHash != null && diskHash.contentEquals(expectedBaseHash)
                    }
                if (!matches) return Result.Conflict(diskHash)
            }
            val created = createTemp(dir, target, exists)
            temp = created
            writer(created, bytes)
            Files.move(created, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            temp = null
            return Result.Saved(EditorText.sha256(bytes))
        } catch (_: AtomicMoveNotSupportedException) {
            return Result.Failed(SaveFailure.ATOMIC_MOVE_UNSUPPORTED)
        } catch (_: AccessDeniedException) {
            return Result.Failed(SaveFailure.READ_ONLY)
        } catch (e: IOException) {
            val text = e.message.orEmpty().lowercase()
            val full = "no space" in text || "disk full" in text || "not enough space" in text
            return Result.Failed(if (full) SaveFailure.DISK_FULL else SaveFailure.IO)
        } catch (_: SecurityException) {
            return Result.Failed(SaveFailure.READ_ONLY)
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun hashOf(file: Path): ByteArray? {
        if (Files.size(file) > MAX_HASHED_BYTES) return null
        return EditorText.sha256(Files.readAllBytes(file))
    }

    private fun createTemp(
        dir: Path,
        target: Path,
        targetExists: Boolean,
    ): Path {
        val temp = dir.resolve(".${target.fileName}.kstep-save-${UUID.randomUUID().toString().take(8)}.tmp")
        val posix = "posix" in dir.fileSystem.supportedFileAttributeViews()
        if (posix) {
            val permissions: Set<PosixFilePermission> =
                if (targetExists) {
                    Files.getPosixFilePermissions(target)
                } else {
                    PosixFilePermissions.fromString("rw-r--r--")
                }
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(permissions))
        } else {
            Files.createFile(temp)
        }
        return temp
    }

    companion object {
        private const val MAX_HASHED_BYTES = 64L * 1024 * 1024

        /** Writes all bytes and forces them to the device before the move. */
        fun writeAndSync(
            file: Path,
            bytes: ByteArray,
        ) {
            FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }
    }
}
