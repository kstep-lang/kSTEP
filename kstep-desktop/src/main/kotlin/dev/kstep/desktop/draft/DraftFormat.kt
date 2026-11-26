package dev.kstep.desktop.draft

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest

/**
 * One crash draft (ADR-0084). [fileSha256] is the hash of the file version the draft is based on (`null` = the file did
 * not exist); [fileMtime] and [fileSize] are `-1` when unknown. [body] is empty for a header-only parse.
 */
class Draft(
    val path: Path,
    val fileMtime: Long,
    val fileSize: Long,
    val fileSha256: ByteArray?,
    val created: Long,
    val written: Long,
    val body: String,
)

enum class RejectReason {
    TOO_LARGE,
    HEADER,
    KEY,
    PATH,
    NUMBER,
    HASH_FORMAT,
    BODY_LENGTH,
    BODY_HASH,
    NOT_UTF8,
    BODY_INVALID,
    NAME_MISMATCH,
    NOT_REGULAR,
    LINK,
    OWNER,
    PERMISSIONS,
}

sealed interface DraftParse {
    class Ok(
        val draft: Draft,
    ) : DraftParse

    class Rejected(
        val reason: RejectReason,
    ) : DraftParse
}

/**
 * The draft file: a magic line, eight `key=value` lines in a fixed order, an empty line, then the body (UTF-8, LF).
 * The file is untrusted on load; [parse] never throws and checks everything the editor needs of a buffer.
 */
object DraftFormat {
    private const val MAGIC = "KSTEP-DRAFT 1"
    private val KEYS =
        listOf("path", "file-mtime", "file-size", "file-sha256", "created", "written", "body-sha256", "body-length")
    private val NUMBER = Regex("^-?[0-9]{1,19}$")
    private val HEX = Regex("^[0-9a-f]{64}$")

    private class Header(
        val draft: Draft,
        val bodySha256: String,
        val bodyLength: Long,
    )

    private class Fail(
        val reason: RejectReason,
    ) : Exception(null, null, false, false)

    /** Throws [IllegalArgumentException] for a body or path the format cannot carry. */
    fun serialize(d: Draft): ByteArray {
        val body = d.body.toByteArray(StandardCharsets.UTF_8)
        require(body.size <= DraftPolicy.MAX_BODY_BYTES) { "draft body too large" }
        val path = d.path.toString()
        require(validPath(path) != null) { "draft path invalid" }
        val header =
            buildString {
                append(MAGIC).append('\n')
                append("path=").append(path).append('\n')
                append("file-mtime=").append(d.fileMtime).append('\n')
                append("file-size=").append(d.fileSize).append('\n')
                append("file-sha256=").append(d.fileSha256?.toHex() ?: "-").append('\n')
                append("created=").append(d.created).append('\n')
                append("written=").append(d.written).append('\n')
                append("body-sha256=").append(sha256(body).toHex()).append('\n')
                append("body-length=").append(body.size).append('\n')
                append('\n')
            }.toByteArray(StandardCharsets.UTF_8)
        require(header.size <= DraftPolicy.MAX_HEADER_BYTES) { "draft header too large" }
        return header + body
    }

    fun parse(bytes: ByteArray): DraftParse =
        try {
            if (bytes.size > DraftPolicy.MAX_FILE_BYTES) throw Fail(RejectReason.TOO_LARGE)
            val end = headerEnd(bytes)
            val header = parseHeader(bytes.copyOfRange(0, end))
            val bodyBytes = bytes.copyOfRange(end + 2, bytes.size)
            if (bodyBytes.size.toLong() != header.bodyLength) throw Fail(RejectReason.BODY_LENGTH)
            if (!MessageDigest.isEqual(sha256(bodyBytes).toHex().toByteArray(), header.bodySha256.toByteArray())) {
                throw Fail(RejectReason.BODY_HASH)
            }
            val body = decodeBody(bodyBytes)
            val h = header.draft
            DraftParse.Ok(Draft(h.path, h.fileMtime, h.fileSize, h.fileSha256, h.created, h.written, body))
        } catch (f: Fail) {
            DraftParse.Rejected(f.reason)
        } catch (_: Exception) {
            DraftParse.Rejected(RejectReason.HEADER)
        }

    /** Parses only the first bytes of a file (at most [DraftPolicy.MAX_HEADER_BYTES] plus the separator). */
    fun parseHeaderOnly(prefix: ByteArray): DraftParse =
        try {
            DraftParse.Ok(parseHeader(prefix.copyOfRange(0, headerEnd(prefix))).draft)
        } catch (f: Fail) {
            DraftParse.Rejected(f.reason)
        } catch (_: Exception) {
            DraftParse.Rejected(RejectReason.HEADER)
        }

    private fun headerEnd(bytes: ByteArray): Int {
        val limit = minOf(bytes.size - 1, DraftPolicy.MAX_HEADER_BYTES)
        for (i in 0 until limit) {
            if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) return i
        }
        throw Fail(RejectReason.HEADER)
    }

    private fun parseHeader(headerBytes: ByteArray): Header {
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(headerBytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw Fail(RejectReason.NOT_UTF8)
            }
        val lines = text.split('\n')
        if (lines.size != KEYS.size + 1 || lines[0] != MAGIC) throw Fail(RejectReason.HEADER)
        val values =
            KEYS.mapIndexed { i, key ->
                val line = lines[i + 1]
                if (!line.startsWith("$key=")) throw Fail(RejectReason.KEY)
                line.substring(key.length + 1)
            }
        val path = validPath(values[0]) ?: throw Fail(RejectReason.PATH)
        val mtime = number(values[1], allowMinusOne = true)
        val size = number(values[2], allowMinusOne = true)
        val fileHash =
            if (values[3] == "-") {
                null
            } else {
                hex(values[3])
            }
        val created = number(values[4], allowMinusOne = false)
        val written = number(values[5], allowMinusOne = false)
        if (created > written) throw Fail(RejectReason.NUMBER)
        val bodyHash = values[6].also { if (!HEX.matches(it)) throw Fail(RejectReason.HASH_FORMAT) }
        val bodyLength = number(values[7], allowMinusOne = false)
        if (bodyLength > DraftPolicy.MAX_BODY_BYTES) throw Fail(RejectReason.BODY_LENGTH)
        return Header(Draft(path, mtime, size, fileHash, created, written, ""), bodyHash, bodyLength)
    }

    private fun hex(raw: String): ByteArray {
        if (!HEX.matches(raw)) throw Fail(RejectReason.HASH_FORMAT)
        return ByteArray(
            32,
        ) { ((Character.digit(raw[it * 2], 16) shl 4) or Character.digit(raw[it * 2 + 1], 16)).toByte() }
    }

    private fun number(
        raw: String,
        allowMinusOne: Boolean,
    ): Long {
        if (!NUMBER.matches(raw)) throw Fail(RejectReason.NUMBER)
        val n = raw.toLongOrNull() ?: throw Fail(RejectReason.NUMBER)
        if (n < 0 && !(allowMinusOne && n == -1L)) throw Fail(RejectReason.NUMBER)
        return n
    }

    /** An absolute, normalized path without control characters; `null` if the text is not one. */
    internal fun validPath(raw: String): Path? {
        if (raw.isEmpty() || raw.toByteArray(StandardCharsets.UTF_8).size > DraftPolicy.MAX_PATH_BYTES) return null
        if (raw.any { it.code < 0x20 || it.code == 0x7F }) return null
        val path =
            try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return null
            }
        return if (path.isAbsolute && path == path.normalize()) path else null
    }

    private fun decodeBody(bytes: ByteArray): String {
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw Fail(RejectReason.NOT_UTF8)
            }
        // the invariants of the editor buffer: a draft the editor could not save later is refused
        if (text.firstOrNull() == Char(0xFEFF) || text.any { it == '\u0000' || it == '\r' }) {
            throw Fail(RejectReason.BODY_INVALID)
        }
        if (text.split('\n').any { it.length > DraftPolicy.MAX_LINE_CHARS }) throw Fail(RejectReason.BODY_INVALID)
        return text
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
