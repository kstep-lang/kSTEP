package dev.kstep.desktop.ai.provider

import dev.kstep.desktop.ai.llm.FailureKind
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

class TransportResponse(
    val status: Int,
    val body: ByteArray,
) {
    // never prints the body
    override fun toString(): String = "TransportResponse(status=$status, ${body.size} bytes)"
}

/** The failure of one call. The message is a fixed text: never a response body, header or key (ADR-0056). */
class AiTransportException(
    val kind: FailureKind,
    val httpStatus: Int? = null,
) : RuntimeException("AI request failed: $kind")

fun interface HttpTransport {
    /** Blocking; one POST. Redirects are never followed. */
    fun send(
        uri: URI,
        headers: Map<String, String>,
        bodyJson: String,
        timeout: Duration,
        maxBytes: Int,
    ): TransportResponse
}

/** The production transport on the JDK client: no redirects, a byte cap on the response, no logging of any value. */
class JdkHttpTransport(
    connectTimeout: Duration = Duration.ofSeconds(15),
) : HttpTransport {
    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(connectTimeout)
            .build()

    override fun send(
        uri: URI,
        headers: Map<String, String>,
        bodyJson: String,
        timeout: Duration,
        maxBytes: Int,
    ): TransportResponse {
        val builder =
            HttpRequest
                .newBuilder(uri)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
        headers.forEach { (k, v) -> builder.header(k, v) }
        try {
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { ins ->
                val declared = response.headers().firstValueAsLong("content-length")
                if (declared.isPresent && declared.asLong > maxBytes) {
                    throw AiTransportException(FailureKind.RESPONSE_TOO_LARGE, response.statusCode())
                }
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) {
                        throw AiTransportException(FailureKind.RESPONSE_TOO_LARGE, response.statusCode())
                    }
                    out.write(buf, 0, n)
                }
                return TransportResponse(response.statusCode(), out.toByteArray())
            }
        } catch (e: AiTransportException) {
            throw e
        } catch (_: HttpTimeoutException) {
            throw AiTransportException(FailureKind.TIMEOUT)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: IOException) {
            throw AiTransportException(FailureKind.UNREACHABLE)
        }
    }
}
