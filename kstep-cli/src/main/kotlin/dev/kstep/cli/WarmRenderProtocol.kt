package dev.kstep.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.InputStream
import java.util.Base64

/**
 * The line protocol between the desktop and the warm render worker (ADR-0081): one JSON object per line, one request
 * at a time. The worker prints a `hello` line once it is warm; then every request line is answered by exactly one reply
 * line. Output of the render (stdout and stderr of the script) travels as Base64 so that no byte of it can break the
 * framing, with a fixed size bound (4/3) instead of the up to sixfold growth of JSON escaping.
 *
 * Not API: kstep-cli is unpublished (ADR-0043); public only for kstep-desktop.
 */
object WarmRenderProtocol {
    const val VERSION: Int = 1

    /** Longest request line a worker accepts. */
    const val MAX_REQUEST_LINE_BYTES: Int = 64 * 1024

    /** What the worker captures of one render's stdout (same bound as the cold supervisor). */
    const val MAX_CAPTURED_STDOUT_BYTES: Int = ImportWorkerSupervisor.MAX_STDOUT_BYTES

    /** What the worker captures of one render's stderr (same bound as the cold supervisor). */
    const val MAX_CAPTURED_STDERR_BYTES: Int = ImportWorkerSupervisor.MAX_STDERR_BYTES

    /** Longest reply line the desktop accepts: both captures as Base64 plus the JSON around them. */
    const val MAX_REPLY_LINE_BYTES: Int =
        (MAX_CAPTURED_STDOUT_BYTES + MAX_CAPTURED_STDERR_BYTES) / 3 * 4 + 64 * 1024

    private const val MAX_REASON_CHARS = 64

    private val json = Json

    data class Request(
        val id: Long,
        val script: String,
        val out: String,
    )

    class Reply(
        val id: Long,
        val exit: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
        val overflow: Boolean,
        val recycle: Boolean,
        val recycleReason: String?,
    )

    fun helloLine(): String =
        buildJsonObject {
            put("v", VERSION)
            put("hello", true)
        }.toString()

    /** `true` for the worker's ready line. */
    fun isHello(line: String): Boolean {
        val obj = parseObject(line) ?: return false
        return (obj["v"] as? JsonPrimitive)?.intOrNull == VERSION &&
            (obj["hello"] as? JsonPrimitive)?.booleanOrNull == true
    }

    fun encodeRequest(request: Request): String =
        buildJsonObject {
            put("v", VERSION)
            put("id", request.id)
            put("script", request.script)
            put("out", request.out)
        }.toString()

    /** `null` for anything that is not a well-formed request of this version (a protocol error). */
    fun decodeRequest(line: String): Request? {
        val obj = parseObject(line) ?: return null
        if ((obj["v"] as? JsonPrimitive)?.intOrNull != VERSION) return null
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull ?: return null
        val script = stringOf(obj["script"]) ?: return null
        val out = stringOf(obj["out"]) ?: return null
        return Request(id, script, out)
    }

    fun encodeReply(reply: Reply): String =
        buildJsonObject {
            put("v", VERSION)
            put("id", reply.id)
            put("exit", reply.exit)
            put("out64", Base64.getEncoder().encodeToString(reply.stdout))
            put("err64", Base64.getEncoder().encodeToString(reply.stderr))
            put("overflow", reply.overflow)
            put("recycle", reply.recycle)
            reply.recycleReason?.let { put("reason", it.take(MAX_REASON_CHARS)) }
        }.toString()

    /**
     * Strict decoding of the reply to request [expectedId]: wrong version or id, missing fields, invalid Base64 or a
     * capture beyond its bound all give `null`. The caller treats `null` as a protocol error.
     */
    fun decodeReply(
        line: String,
        expectedId: Long,
    ): Reply? {
        val obj = parseObject(line) ?: return null
        if ((obj["v"] as? JsonPrimitive)?.intOrNull != VERSION) return null
        if ((obj["id"] as? JsonPrimitive)?.longOrNull != expectedId) return null
        val exit = (obj["exit"] as? JsonPrimitive)?.intOrNull ?: return null
        val stdout = decodeBounded(obj["out64"], MAX_CAPTURED_STDOUT_BYTES) ?: return null
        val stderr = decodeBounded(obj["err64"], MAX_CAPTURED_STDERR_BYTES) ?: return null
        val overflow = (obj["overflow"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val recycle = (obj["recycle"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val reason = stringOf(obj["reason"])?.take(MAX_REASON_CHARS)
        return Reply(expectedId, exit, stdout, stderr, overflow, recycle, reason)
    }

    /** The `id` of a protocol line that carries one in this version, else `null`. For spotting a late reply. */
    fun idOf(line: String): Long? {
        val obj = parseObject(line) ?: return null
        if ((obj["v"] as? JsonPrimitive)?.intOrNull != VERSION) return null
        return (obj["id"] as? JsonPrimitive)?.longOrNull
    }

    /** What [readLine] found. */
    sealed interface Line {
        class Text(
            val bytes: ByteArray,
        ) : Line

        data object TooLong : Line

        data object EndOfStream : Line
    }

    /**
     * Reads one `\n`-terminated line of at most [maxBytes] bytes from [input] (a trailing `\r` is dropped). An unfinished
     * last line at the end of the stream counts as the end of the stream, not as a line.
     */
    fun readLine(
        input: InputStream,
        maxBytes: Int,
    ): Line {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return Line.EndOfStream
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                return Line.Text(
                    if (bytes.isNotEmpty() &&
                        bytes.last() == '\r'.code.toByte()
                    ) {
                        bytes.copyOf(bytes.size - 1)
                    } else {
                        bytes
                    },
                )
            }
            if (buffer.size() >= maxBytes) return Line.TooLong
            buffer.write(b)
        }
    }

    private fun parseObject(line: String): JsonObject? =
        try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun stringOf(element: kotlinx.serialization.json.JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun decodeBounded(
        element: kotlinx.serialization.json.JsonElement?,
        maxBytes: Int,
    ): ByteArray? {
        val text = stringOf(element) ?: return null
        // The encoded length bounds the decoded one, so nothing is allocated for an oversized field.
        if (text.length > (maxBytes + 2) / 3 * 4) return null
        return try {
            Base64.getDecoder().decode(text).takeIf { it.size <= maxBytes }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
