package dev.kstep.desktop.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Limits of the JDK HTTP server that are system properties, read once when the JDK class loads (ADR-0095). Set only
 * when the user has not set them. `maxRspTime` is the time a call may take until its answer is sent: the JDK starts
 * that timer when the headers are read and drops the connection without an answer when it runs out. It must therefore
 * outlast the longest wait a tool has: the user's decision in a file dialog (60 s plus the 5 s backstop, ADR-0098) and a
 * run that waits for its result (30 s). A value of 10 made the agent lose the answer of any call that waited longer
 * (found by hand on 2026-11-28). A request that is sent slowly is bounded separately by `maxReqTime`. `drainAmount=0` makes the server close the connection after a rejection instead of
 * reading up to 64 KiB of a body that was never authorised.
 */
object McpHttpDefaults {
    /** The longest a call may take: confirmation timeout 60 s + backstop 5 s + a margin of 5 s. */
    const val MAX_RESPONSE_SECONDS = "70"

    fun apply() {
        setIfAbsent("sun.net.httpserver.maxReqTime", "10")
        setIfAbsent("sun.net.httpserver.maxRspTime", MAX_RESPONSE_SECONDS)
        setIfAbsent("sun.net.httpserver.maxReqHeaders", "64")
        setIfAbsent("sun.net.httpserver.maxReqHeaderSize", "16384")
        setIfAbsent("jdk.httpserver.maxConnections", "64")
        setIfAbsent("sun.net.httpserver.drainAmount", "0")
    }

    private fun setIfAbsent(
        key: String,
        value: String,
    ) {
        if (System.getProperty(key) == null) System.setProperty(key, value)
    }
}

/**
 * The MCP endpoint (ADR-0095): `POST /mcp` on 127.0.0.1 only, stateless, JSON in and out. The checks run in a fixed
 * order and the first violation wins; the request body is not opened before the token has been checked. Rejections carry
 * no body, so no text of the application can leak through them.
 */
class DesktopMcpHttpServer internal constructor(
    private val protocol: McpProtocol,
    private val token: () -> McpToken?,
    private val activity: McpActivityTracker,
    private val maxBodyBytes: Int = MAX_BODY_BYTES,
    private val workerThreads: Int = WORKER_THREADS,
    private val queueSize: Int = QUEUE_SIZE,
) {
    private var server: HttpServer? = null
    private var executor: ThreadPoolExecutor? = null

    /** Counts how often a request body was opened (a test seam: a rejected request must leave it unchanged). */
    internal val bodyOpens = AtomicInteger()

    val port: Int? get() = server?.address?.port

    /** Binds 127.0.0.1:[port] (0 picks a free one) and returns the port. @throws BindException when it is taken. */
    @Synchronized
    fun start(port: Int): Int {
        McpHttpDefaults.apply()
        check(server == null) { "already started" }
        val pool =
            ThreadPoolExecutor(
                workerThreads,
                workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(queueSize),
                WorkerFactory(),
                ThreadPoolExecutor.AbortPolicy(),
            )
        val loopback = InetAddress.getByAddress(byteArrayOf(LOOPBACK_BYTE, 0, 0, 1))
        val created =
            try {
                HttpServer.create(InetSocketAddress(loopback, port), BACKLOG)
            } catch (e: IOException) {
                pool.shutdownNow()
                throw e
            }
        try {
            check(created.address.address.isLoopbackAddress) { "the server is not bound to the loopback address" }
            val boundPort = created.address.port
            created.createContext("/") { exchange -> handle(exchange, boundPort) }
            created.executor = pool
            created.start()
        } catch (e: RuntimeException) {
            created.stop(0)
            pool.shutdownNow()
            throw e
        }
        server = created
        executor = pool
        return created.address.port
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        try {
            s.stop(1)
        } catch (e: Exception) {
            logger.debug { "mcp server stop failed (${e::class.simpleName})" }
        }
        executor?.let {
            it.shutdownNow()
            it.awaitTermination(2, TimeUnit.SECONDS)
        }
        executor = null
    }

    private class Reply(
        val status: Int,
        val body: ByteArray? = null,
        val headers: List<Pair<String, String>> = emptyList(),
    )

    private fun handle(
        exchange: HttpExchange,
        boundPort: Int,
    ) {
        var label = "other"
        try {
            val reply =
                try {
                    process(exchange, boundPort) { label = it }
                } catch (e: Exception) {
                    logger.debug { "mcp request failed (${e::class.simpleName})" }
                    Reply(INTERNAL_SERVER_ERROR)
                }
            send(exchange, reply)
            val method = exchange.requestMethod.takeIf { it in KNOWN_METHODS } ?: "other"
            logger.debug { "mcp $method /$label -> ${reply.status}" }
        } finally {
            exchange.close()
        }
    }

    private fun send(
        exchange: HttpExchange,
        reply: Reply,
    ) {
        try {
            val headers = exchange.responseHeaders
            headers.add("Cache-Control", "no-store")
            reply.headers.forEach { (name, value) -> headers.add(name, value) }
            if (reply.status >= BAD_REQUEST) headers.add("Connection", "close")
            val body = reply.body
            if (body == null) {
                // -1 means "no body"; 0 would mean chunked
                exchange.sendResponseHeaders(reply.status, -1)
            } else {
                headers.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(reply.status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        } catch (e: IOException) {
            logger.debug { "mcp response failed (${e::class.simpleName})" }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun process(
        exchange: HttpExchange,
        port: Int,
        label: (String) -> Unit,
    ): Reply {
        // 1. method
        if (exchange.requestMethod != "POST") return Reply(METHOD_NOT_ALLOWED, headers = listOf("Allow" to "POST"))
        // 2. path: exactly /mcp, no query
        val uri = exchange.requestURI
        if (uri.rawPath != PATH || uri.rawQuery != null) return Reply(NOT_FOUND)
        label("mcp")
        val headers = exchange.requestHeaders
        // 3. Host
        val hosts = headers["Host"]
        if (hosts == null || hosts.size != 1 || hosts[0].lowercase() !in allowedHosts(port)) return Reply(FORBIDDEN)
        // 4. Origin: absent is fine, present must be exactly us
        val origins = headers["Origin"]
        if (origins != null && (origins.size != 1 || origins[0].lowercase() !in allowedOrigins(port))) {
            return Reply(FORBIDDEN)
        }
        // 5. token, before anything of the body is touched
        if (!authorised(headers["Authorization"])) {
            return Reply(UNAUTHORIZED, headers = listOf("WWW-Authenticate" to "Bearer"))
        }
        // 6. content type
        val contentType =
            headers["Content-Type"]
                ?.singleOrNull()
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (contentType != "application/json") return Reply(UNSUPPORTED_MEDIA_TYPE)
        // 7. size
        val declared = headers["Content-Length"]?.singleOrNull()?.trim()?.toLongOrNull()
        if (declared != null && declared > maxBodyBytes) return Reply(PAYLOAD_TOO_LARGE)
        bodyOpens.incrementAndGet()
        val bytes = exchange.requestBody.readNBytes(maxBodyBytes + 1)
        if (bytes.size > maxBodyBytes) return Reply(PAYLOAD_TOO_LARGE)
        // 8. strict UTF-8 and JSON
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                return rpcError(BAD_REQUEST, JsonRpc.PARSE_ERROR, "Parse error")
            }
        val element =
            try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                return rpcError(BAD_REQUEST, JsonRpc.PARSE_ERROR, "Parse error")
            } catch (_: StackOverflowError) {
                return rpcError(BAD_REQUEST, JsonRpc.PARSE_ERROR, "Parse error")
            }
        // 9. one object, no batch
        if (element is JsonArray) {
            return rpcError(BAD_REQUEST, JsonRpc.INVALID_REQUEST, "Batch requests are not supported")
        }
        if (element !is JsonObject) return rpcError(BAD_REQUEST, JsonRpc.INVALID_REQUEST, "Invalid request")
        // 10. protocol version header
        val versions = headers["MCP-Protocol-Version"]
        if (versions != null && (versions.size != 1 || versions[0] !in McpProtocol.SUPPORTED_VERSIONS)) {
            return Reply(BAD_REQUEST)
        }
        // 11. the call
        val response = protocol.handle(element, versions?.singleOrNull())
        if (response == null) return Reply(ACCEPTED)
        val status = if (JsonRpc.errorCode(response) == JsonRpc.INVALID_REQUEST) BAD_REQUEST else OK
        if (status == OK) activity.record()
        return Reply(status, response.toString().toByteArray(Charsets.UTF_8))
    }

    private fun rpcError(
        status: Int,
        code: Int,
        message: String,
    ) = Reply(status, JsonRpc.error(null, code, message).toString().toByteArray(Charsets.UTF_8))

    private fun authorised(values: List<String>?): Boolean {
        val expected = token() ?: return false
        if (values == null || values.size != 1) return false
        val value = values[0]
        val space = value.indexOf(' ')
        if (space < 0 || !value.substring(0, space).equals("Bearer", ignoreCase = true)) return false
        val presented = value.substring(space + 1)
        return presented.length == McpTokenStore.TOKEN_CHARS && expected.matches(presented)
    }

    private fun allowedHosts(port: Int) = setOf("127.0.0.1:$port", "localhost:$port")

    private fun allowedOrigins(port: Int) = setOf("http://127.0.0.1:$port", "http://localhost:$port")

    private class WorkerFactory : ThreadFactory {
        private val counter = AtomicInteger()

        override fun newThread(r: Runnable): Thread =
            Thread(r, "kstep-mcp-worker-${counter.incrementAndGet()}").also { it.isDaemon = true }
    }

    companion object {
        const val PATH = "/mcp"
        const val MAX_BODY_BYTES = 1 shl 20
        const val WORKER_THREADS = 4
        const val QUEUE_SIZE = 16
        private const val BACKLOG = 16
        private const val LOOPBACK_BYTE: Byte = 127
        private val KNOWN_METHODS = setOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")
        private const val OK = 200
        private const val ACCEPTED = 202
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private const val PAYLOAD_TOO_LARGE = 413
        private const val UNSUPPORTED_MEDIA_TYPE = 415
        private const val INTERNAL_SERVER_ERROR = 500
    }
}
