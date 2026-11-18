package dev.kstep.desktop.ai.provider

import ai.koog.http.client.KoogHttpClient
import dev.kstep.desktop.ai.llm.FailureKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.net.URI
import java.time.Duration
import kotlin.reflect.KClass

/**
 * Koog's HTTP seam implemented on our own hardened transport (ADR-0056): only POST, only https to the one host of the
 * provider (exact comparison, no userinfo, port empty or 443), no redirects, a byte cap, and no response body in any
 * exception. The key travels in [headers] and appears in no `toString`.
 */
class GuardedKoogHttpClient(
    private val provider: AiProvider,
    private val headers: () -> Map<String, String>,
    private val transport: HttpTransport,
    private val json: Json,
    private val maxBodyBytes: Int,
    private val timeout: Duration = Duration.ofSeconds(60),
) : KoogHttpClient {
    override val clientName: String = "KStepGuardedClient"

    override fun toString(): String = "GuardedKoogHttpClient(${provider.host})"

    internal fun resolve(path: String): URI {
        val uri = URI.create(provider.baseUrl + "/" + path.trimStart('/'))
        check(
            uri.scheme == "https" &&
                uri.host == provider.host &&
                uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443),
        ) { "blocked host" }
        return uri
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        val uri = resolve(path)
        val body =
            requestBody as? String
                ?: json.encodeToString(json.serializersModule.serializer(requestBodyType.java), requestBody)
        val all = this.headers() + headers + mapOf("content-type" to "application/json")
        val response =
            runInterruptible(Dispatchers.IO) { transport.send(uri, all, body, timeout, maxBodyBytes) }
        when (response.status) {
            in 200..299 -> Unit
            401, 403 -> throw AiTransportException(FailureKind.KEY_REJECTED, response.status)
            429 -> throw AiTransportException(FailureKind.RATE_LIMITED, response.status)
            else -> throw AiTransportException(FailureKind.PROVIDER_ERROR, response.status)
        }
        return try {
            val serializer = json.serializersModule.serializer(responseType.java) as KSerializer<R>
            json.decodeFromString(serializer, String(response.body, Charsets.UTF_8))
        } catch (_: Exception) {
            throw AiTransportException(FailureKind.MALFORMED, response.status)
        }
    }

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = throw UnsupportedOperationException("GET is not used")

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = throw UnsupportedOperationException("streaming is not used")

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = throw UnsupportedOperationException("streaming is not used")

    override fun close() = Unit
}
