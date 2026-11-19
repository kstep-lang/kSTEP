package dev.kstep.cli.okf

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom

/**
 * The file-system side of `kstep convert` (ADR-0060): bounded reads without following links, an output folder that must
 * already exist, targets that are never links, and atomic writes (temp file + `fsync` + atomic move, no fallback).
 */
internal object ConvertFiles {
    sealed interface ReadResult {
        data class Ok(
            val text: String,
        ) : ReadResult

        data class Failed(
            val message: String,
        ) : ReadResult
    }

    sealed interface DirResult {
        data class Ok(
            val real: Path,
        ) : DirResult

        data class Failed(
            val message: String,
        ) : DirResult
    }

    sealed interface TargetCheck {
        data class Free(
            val path: Path,
        ) : TargetCheck

        data class Exists(
            val path: Path,
        ) : TargetCheck

        data class Invalid(
            val message: String,
        ) : TargetCheck
    }

    sealed interface WriteResult {
        data object Written : WriteResult

        data object AlreadyExists : WriteResult

        data class Failed(
            val message: String,
        ) : WriteResult
    }

    private val random = SecureRandom()

    private fun attributes(path: Path): BasicFileAttributes? =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            null
        }

    fun readBounded(
        path: Path,
        maxBytes: Long,
    ): ReadResult {
        try {
            val attrs = attributes(path) ?: return ReadResult.Failed("the input file does not exist")
            if (attrs.isSymbolicLink) return ReadResult.Failed("the input is a symbolic link")
            if (!attrs.isRegularFile) return ReadResult.Failed("the input is not a regular file")
            if (attrs.size() > maxBytes) return ReadResult.Failed("the input is larger than ${maxBytes / 1024} KiB")
            val buffer = ByteBuffer.allocate((maxBytes + 1).toInt())
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { ch ->
                while (buffer.hasRemaining()) {
                    if (ch.read(buffer) < 0) break
                }
            }
            if (buffer.position() >
                maxBytes
            ) {
                return ReadResult.Failed("the input is larger than ${maxBytes / 1024} KiB")
            }
            buffer.flip()
            val text =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buffer)
                    .toString()
            return ReadResult.Ok(text)
        } catch (_: CharacterCodingException) {
            return ReadResult.Failed("the input is not valid UTF-8")
        } catch (_: IOException) {
            return ReadResult.Failed("the input cannot be read")
        } catch (_: UnsupportedOperationException) {
            return ReadResult.Failed("the input cannot be read")
        }
    }

    /** The chosen folder may itself be a link (like `/tmp` on macOS); it must exist, nothing is created. */
    fun resolveOutDir(dir: Path): DirResult =
        try {
            val real = dir.toRealPath()
            if (Files.isDirectory(
                    real,
                )
            ) {
                DirResult.Ok(real)
            } else {
                DirResult.Failed("the output folder is not a directory")
            }
        } catch (_: IOException) {
            DirResult.Failed("the output folder does not exist")
        }

    fun checkTarget(
        outReal: Path,
        fileName: String,
        inputReal: Path,
    ): TargetCheck {
        if (fileName.isEmpty() ||
            fileName == "." ||
            fileName == ".." ||
            fileName.any { it == '/' || it == '\\' || it == '\u0000' }
        ) {
            return TargetCheck.Invalid("the target file name is not valid")
        }
        val target =
            try {
                outReal.resolve(fileName)
            } catch (_: RuntimeException) {
                return TargetCheck.Invalid("the target file name is not valid")
            }
        if (target.parent != outReal) return TargetCheck.Invalid("the target is outside the output folder")
        return try {
            val attrs = attributes(target)
            when {
                attrs == null -> TargetCheck.Free(target)
                attrs.isSymbolicLink || !attrs.isRegularFile -> TargetCheck.Invalid("the target is not a regular file")
                Files.isSameFile(target, inputReal) -> TargetCheck.Invalid("the target is the input file")
                else -> TargetCheck.Exists(target)
            }
        } catch (_: IOException) {
            TargetCheck.Invalid("the target cannot be checked")
        }
    }

    fun writeAtomic(
        outReal: Path,
        fileName: String,
        bytes: ByteArray,
        replace: Boolean,
        beforeMove: () -> Unit = {},
    ): WriteResult {
        var temp: Path? = null
        try {
            val target = outReal.resolve(fileName)
            temp = outReal.resolve(".kstep-convert-${randomHex()}.tmp")
            FileChannel
                .open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                .use { ch ->
                    val buf = ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) ch.write(buf)
                    ch.force(true)
                }
            beforeMove()
            val existing = attributes(target)
            if (existing != null) {
                if (existing.isSymbolicLink || !existing.isRegularFile) {
                    return WriteResult.Failed("the target is not a regular file")
                }
                if (!replace) return WriteResult.AlreadyExists
            }
            if (replace) {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            }
            temp = null
            return WriteResult.Written
        } catch (_: AtomicMoveNotSupportedException) {
            return WriteResult.Failed("this file system cannot replace the file atomically")
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            return WriteResult.AlreadyExists
        } catch (_: IOException) {
            return WriteResult.Failed("the file cannot be written")
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp)
                } catch (_: IOException) {
                    // best effort
                }
            }
        }
    }

    private fun randomHex(): String {
        val b = ByteArray(8)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
