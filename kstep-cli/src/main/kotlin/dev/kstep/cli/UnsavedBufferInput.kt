package dev.kstep.cli

import dev.kstep.preview.PreviewSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * Validation of an unsaved editor buffer that the desktop hands to a render worker (ADR-0086). Both workers (warm and
 * cold) load the buffer through [load]: the worker trusts nothing of what the desktop wrote, because the sandbox lets a
 * script write to its session folder and the buffer lives next to it. A refusal carries only a [Refusal] code, never a
 * path or any content.
 *
 * Not API: kstep-cli is unpublished (ADR-0043); public only for kstep-desktop.
 */
object UnsavedBufferInput {
    /** The largest buffer, in bytes. Same bound on the desktop side. */
    const val MAX_BYTES: Int = 1_048_576
    const val MAX_DISPLAY_NAME_CHARS: Int = 255

    /** The stderr prefix of a refusal; followed by the [Refusal] name. */
    const val REFUSED_MARKER: String = "kstep-unsaved-refused:"

    private const val SCRIPT_SUFFIX = ".kstep.kts"
    private const val SHA256_HEX_CHARS = 64
    private const val ASCII_CONTROL_LIMIT = 0x20
    private const val ASCII_DEL = 0x7F

    /** `<uuid>.kstep.kts`, lower-case: the only name the desktop gives a buffer file. */
    val FILE_NAME = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.kstep\\.kts$")

    private val HEX_SHA256 = Regex("^[0-9a-f]{$SHA256_HEX_CHARS}$")

    enum class Refusal {
        BUFFER_PATH,
        BUFFER_LINK,
        BUFFER_SIZE,
        BUFFER_ENCODING,
        BUFFER_HASH,
        SCRIPT_DIR,
        DISPLAY_NAME,
    }

    sealed interface Result {
        data class Ok(
            val source: PreviewSource.BufferScript,
        ) : Result

        data class Refused(
            val reason: Refusal,
        ) : Result
    }

    /**
     * A display name is a plain file name ending in `.kstep.kts`: no separator, no `..`, no control character, no
     * bidirectional override (it appears in error messages, where such characters could fake another text).
     */
    fun validDisplayName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= MAX_DISPLAY_NAME_CHARS &&
            name.endsWith(SCRIPT_SUFFIX) &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains("..") &&
            name.none { it.code < ASCII_CONTROL_LIMIT || it.code == ASCII_DEL || isHostile(it) }

    private fun isHostile(c: Char): Boolean =
        c.code in 0x202A..0x202E || c.code in 0x2066..0x2069 || c.code in 0x80..0x9F || c == '‎' || c == '‏'

    fun load(
        bufferPath: String,
        scriptDir: String,
        displayName: String,
        sha256Hex: String,
    ): Result {
        if (!validDisplayName(displayName)) return Result.Refused(Refusal.DISPLAY_NAME)
        if (!HEX_SHA256.matches(sha256Hex)) return Result.Refused(Refusal.BUFFER_HASH)
        val dir = scriptDirectory(scriptDir) ?: return Result.Refused(Refusal.SCRIPT_DIR)
        val path = bufferFile(bufferPath) ?: return Result.Refused(Refusal.BUFFER_PATH)
        return read(path, dir, displayName, sha256Hex)
    }

    private fun scriptDirectory(text: String): File? {
        val path = parse(text) ?: return null
        if (!path.isAbsolute || path != path.normalize()) return null
        return if (Files.isDirectory(path)) path.toFile() else null
    }

    private fun bufferFile(text: String): Path? {
        val path = parse(text) ?: return null
        if (!path.isAbsolute || path != path.normalize()) return null
        val name = path.fileName?.toString() ?: return null
        return if (FILE_NAME.matches(name)) path else null
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(StandardCharsets.US_ASCII)

    private fun parse(text: String): Path? =
        try {
            if (text.isEmpty() || text.contains('\u0000')) null else Path.of(text)
        } catch (_: java.nio.file.InvalidPathException) {
            null
        }

    @Suppress("ReturnCount")
    private fun read(
        path: Path,
        dir: File,
        displayName: String,
        sha256Hex: String,
    ): Result {
        val bytes =
            try {
                val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink || attrs.isOther) return Result.Refused(Refusal.BUFFER_LINK)
                if (!attrs.isRegularFile) return Result.Refused(Refusal.BUFFER_PATH)
                if (attrs.size() > MAX_BYTES) return Result.Refused(Refusal.BUFFER_SIZE)
                readBounded(path) ?: return Result.Refused(Refusal.BUFFER_SIZE)
            } catch (_: java.io.IOException) {
                return Result.Refused(Refusal.BUFFER_PATH)
            } catch (_: UnsupportedOperationException) {
                return Result.Refused(Refusal.BUFFER_LINK)
            }
        // a new digest per call: MessageDigest is not thread-safe
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val actual = digest.joinToString("") { "%02x".format(it) }
        val same = MessageDigest.isEqual(ascii(actual), ascii(sha256Hex))
        if (!same) return Result.Refused(Refusal.BUFFER_HASH)
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                return Result.Refused(Refusal.BUFFER_ENCODING)
            }
        return Result.Ok(PreviewSource.BufferScript(text, displayName, dir))
    }

    /** At most [MAX_BYTES] bytes, or `null` when the file has more (it grew since the attributes were read). */
    private fun readBounded(path: Path): ByteArray? =
        Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(MAX_BYTES + 1)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // keep reading until the limit or the end of the file
            }
            if (buffer.position() > MAX_BYTES) {
                null
            } else {
                buffer.array().copyOf(buffer.position())
            }
        }
}
