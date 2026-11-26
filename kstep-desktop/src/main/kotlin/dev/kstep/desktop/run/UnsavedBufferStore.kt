package dev.kstep.desktop.run

import dev.kstep.cli.WorkerTempDir
import dev.kstep.desktop.draft.DraftPolicy
import dev.kstep.desktop.draft.DraftStore
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.MessageDigest
import java.util.UUID

private val logger = KotlinLogging.logger {}

/** The size limit of an unsaved run (ADR-0086): the same as the editor buffer and the crash draft. */
object UnsavedRunPolicy {
    const val MAX_BYTES: Int = DraftPolicy.MAX_BODY_BYTES
}

/**
 * Where the text of an unsaved run is put for the worker (ADR-0086). The folder is NOT the session folder: the sandbox
 * lets a script write to the session folder, so a buffer there could be swapped by a script of an earlier run of the
 * warm worker. This folder is a randomly named sibling in the system temp folder, mode `rwx------`, read-only for the
 * sandbox. Every write re-checks it ([Written.Unsafe] when it is a link, has another owner or other permissions, or is
 * another folder than the first time); permissions are NOT repaired, a deviation is a sign of tampering. Files get a
 * random name, are created with `CREATE_NEW` and without following links, and the worker checks the SHA-256 on reading.
 *
 * Nothing here logs a path, a name or content.
 */
open class UnsavedBufferStore(
    private val base: Path = Path.of(System.getProperty("java.io.tmpdir")),
    /** Test hook, like the one of the draft store: the owner a fresh file should have. */
    private val currentOwner: (() -> UserPrincipal?)? = null,
    /** Test hook: the file name of the next buffer. */
    private val newName: () -> String = { UUID.randomUUID().toString() + ".kstep.kts" },
) : AutoCloseable {
    sealed interface Written {
        data class Ok(
            val path: Path,
            val sha256Hex: String,
        ) : Written

        data object TooLarge : Written

        data class Unsafe(
            val reason: String,
        ) : Written

        data class Failed(
            val reason: String,
        ) : Written
    }

    private class Known(
        val dir: Path,
        val real: Path,
        val owner: UserPrincipal?,
        val posix: Boolean,
    )

    private var known: Known? = null
    private var closed = false

    @Synchronized
    open fun write(text: String): Written {
        if (closed) return Written.Failed("closed")
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > UnsavedRunPolicy.MAX_BYTES) return Written.TooLarge
        val dir =
            try {
                known ?: create().also { known = it }
            } catch (_: IOException) {
                return Written.Failed("the folder could not be created")
            }
        verify(dir)?.let { return Written.Unsafe(it) }
        val file = dir.dir.resolve(newName())
        val attrs =
            if (dir.posix) {
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            } else {
                emptyArray()
            }
        val channel =
            try {
                Files.newByteChannel(
                    file,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    *attrs,
                )
            } catch (_: IOException) {
                // CREATE_NEW refused (the name exists, also as a link): nothing was created, nothing is deleted
                return Written.Failed("the buffer could not be created")
            }
        return try {
            channel.use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) it.write(buffer)
                (it as? java.nio.channels.FileChannel)?.force(true)
            }
            Written.Ok(file, hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        } catch (_: IOException) {
            // the file is ours (created by this call): a half-written one must not stay
            try {
                Files.deleteIfExists(file)
            } catch (_: IOException) {
                // nothing more to do
            }
            Written.Failed("the buffer could not be written")
        }
    }

    /** Removes one buffer file. Never follows a link; a failure is only logged, without path or content. */
    fun delete(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            logger.warn { "could not remove an unsaved buffer (${e::class.simpleName})" }
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        known?.let { WorkerTempDir.deleteRecursively(it.dir) }
        known = null
    }

    private fun create(): Known {
        val posix = "posix" in base.fileSystem.supportedFileAttributeViews()
        val dir =
            if (posix) {
                Files.createTempDirectory(
                    base,
                    FOLDER_PREFIX,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                )
            } else {
                Files.createTempDirectory(base, FOLDER_PREFIX)
            }
        try {
            val owner =
                if (posix) {
                    currentOwner?.invoke() ?: DraftStore.probeOwner(dir) ?: throw IOException("owner unknown")
                } else {
                    runCatching { Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
                }
            return Known(dir, dir.toRealPath(), owner, posix)
        } catch (e: Exception) {
            // the folder was just made by this call and is still empty: it must not stay behind
            runCatching { Files.deleteIfExists(dir) }
            throw e
        }
    }

    /** `null` when the folder is exactly what it was; else a short reason (never shown with a path). */
    private fun verify(k: Known): String? =
        try {
            val attrs = Files.readAttributes(k.dir, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            when {
                attrs.isSymbolicLink || !attrs.isDirectory -> "not a plain folder"
                k.dir.toRealPath() != k.real -> "another folder"
                k.owner != null && Files.getOwner(k.dir, LinkOption.NOFOLLOW_LINKS) != k.owner -> "owner changed"
                k.posix && !permissionsOk(k.dir) -> "permissions changed"
                else -> null
            }
        } catch (_: IOException) {
            "folder not readable"
        }

    private fun permissionsOk(dir: Path): Boolean {
        val view = Files.getFileAttributeView(dir, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        val perms: Set<PosixFilePermission> = view?.readAttributes()?.permissions() ?: return false
        return perms == PosixFilePermissions.fromString("rwx------")
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val FOLDER_PREFIX = "kstep-desktop-unsaved-"
    }
}
