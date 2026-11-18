package dev.kstep.desktop.edit

import dev.kstep.desktop.run.DesktopErrorCodes
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest

enum class LineEnding(
    val sequence: String,
) {
    LF("\n"),
    CRLF("\r\n"),
}

/** Loading, decoding and encoding of the editor text (ADR-0052): UTF-8 only, 1 MiB at most, never throws. */
object EditorText {
    const val MAX_BYTES = 1_048_576
    const val MAX_LINE_CHARS = 20_000

    sealed interface Loaded {
        /** [text] has `\n` line endings and no BOM; [sha256] is over the RAW bytes of the file. */
        class Ok(
            val text: String,
            val hadBom: Boolean,
            val lineEnding: LineEnding,
            val mixedLineEndings: Boolean,
            val sha256: ByteArray,
        ) : Loaded

        /** The file cannot be edited ([code] is KSTEP-D-005, -009 or -010). [sha256] is set when the bytes were read. */
        class Refused(
            val code: String,
            val reason: String,
            val sha256: ByteArray? = null,
        ) : Loaded
    }

    fun load(path: Path): Loaded =
        try {
            val size = Files.size(path)
            if (size > MAX_BYTES) {
                Loaded.Refused(DesktopErrorCodes.EDITOR_TOO_LARGE, "the file is larger than 1 MiB")
            } else {
                // At most MAX_BYTES + 1: a file that grows between the size check and the read cannot exhaust memory.
                val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
                decode(bytes)
            }
        } catch (_: NoSuchFileException) {
            Loaded.Refused(DesktopErrorCodes.FILE_MISSING, "the file does not exist")
        } catch (_: java.io.IOException) {
            Loaded.Refused(DesktopErrorCodes.EDITOR_NOT_TEXT, "the file could not be read")
        } catch (_: SecurityException) {
            Loaded.Refused(DesktopErrorCodes.EDITOR_NOT_TEXT, "the file could not be read")
        }

    fun decode(bytes: ByteArray): Loaded {
        if (bytes.size > MAX_BYTES) {
            return Loaded.Refused(DesktopErrorCodes.EDITOR_TOO_LARGE, "the file is larger than 1 MiB")
        }
        val hash = sha256(bytes)
        if (bytes.any { it == 0.toByte() }) {
            return Loaded.Refused(DesktopErrorCodes.EDITOR_NOT_TEXT, "the file contains a NUL byte", hash)
        }
        val hasBom =
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val skip = if (hasBom) 3 else 0
        val decoded =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip))
                    .toString()
            } catch (_: CharacterCodingException) {
                return Loaded.Refused(DesktopErrorCodes.EDITOR_NOT_TEXT, "the file is not valid UTF-8", hash)
            }
        var crlf = 0
        var lf = 0
        var cr = 0
        val sb = StringBuilder(decoded.length)
        var firstEnding: LineEnding? = null
        var lineLength = 0
        var i = 0
        while (i < decoded.length) {
            val c = decoded[i]
            if (c == '\r') {
                if (i + 1 < decoded.length && decoded[i + 1] == '\n') {
                    crlf++
                    i++
                    if (firstEnding == null) firstEnding = LineEnding.CRLF
                } else {
                    cr++
                    if (firstEnding == null) firstEnding = LineEnding.LF
                }
                sb.append('\n')
                lineLength = 0
            } else if (c == '\n') {
                lf++
                if (firstEnding == null) firstEnding = LineEnding.LF
                sb.append('\n')
                lineLength = 0
            } else {
                sb.append(c)
                lineLength++
                if (lineLength > MAX_LINE_CHARS) {
                    return Loaded.Refused(
                        DesktopErrorCodes.EDITOR_NOT_TEXT,
                        "a line is longer than 20,000 characters",
                        hash,
                    )
                }
            }
            i++
        }
        val kinds = listOf(crlf, lf, cr).count { it > 0 }
        return Loaded.Ok(sb.toString(), hasBom, firstEnding ?: LineEnding.LF, kinds > 1, hash)
    }

    /** The bytes to write: [text] (internal `\n`) with [ending] line endings and an optional BOM. */
    fun encodeForSave(
        text: String,
        ending: LineEnding,
        bom: Boolean,
    ): ByteArray {
        val body =
            (
                if (ending ==
                    LineEnding.CRLF
                ) {
                    text.replace("\n", "\r\n")
                } else {
                    text
                }
            ).toByteArray(StandardCharsets.UTF_8)
        return if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body else body
    }

    /** A new [MessageDigest] per call: digests are not thread-safe. */
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
