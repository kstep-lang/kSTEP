package dev.kstep.desktop.ai.provider

import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.llm.FailureKind
import dev.kstep.desktop.ai.llm.LlmMessage
import dev.kstep.desktop.ai.llm.LlmReply
import dev.kstep.desktop.ai.llm.LlmRequest
import dev.kstep.desktop.ai.repair.AiLimits
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.time.Duration

const val FAKE_KEY = "sk-ant-TEST-0000000000000000000000"

class FakeTransport(
    private val status: Int = 200,
    private val body: String = OK_BODY,
    private val throwKind: FailureKind? = null,
    private val bodyBytes: ByteArray? = null,
) : HttpTransport {
    var uri: URI? = null
    var headers: Map<String, String> = emptyMap()
    var sent: String = ""
    var calls = 0

    override fun send(
        uri: URI,
        headers: Map<String, String>,
        bodyJson: String,
        timeout: Duration,
        maxBytes: Int,
    ): TransportResponse {
        calls++
        this.uri = uri
        this.headers = headers
        sent = bodyJson
        throwKind?.let { throw AiTransportException(it) }
        val b = bodyBytes ?: body.toByteArray()
        if (b.size > maxBytes) throw AiTransportException(FailureKind.RESPONSE_TOO_LARGE)
        return TransportResponse(status, b)
    }

    companion object {
        fun okBody(text: String) =
            """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5",
                "content":[{"type":"text","text":${kotlinx.serialization.json.JsonPrimitive(text)}}],
                "stop_reason":"end_turn","usage":{"input_tokens":3,"output_tokens":7}}"""

        val OK_BODY = okBody("```kotlin\nval x = 1\n```")
    }
}

class KoogAnthropicClientTest :
    FunSpec({
        fun client(t: HttpTransport) = KoogAnthropicClient("claude-sonnet-5", Secret.fromEnv(FAKE_KEY), t, AiLimits())
        val request = LlmRequest("sys", listOf(LlmMessage(LlmMessage.Role.USER, "hello")), 8192)

        test("a normal answer is decoded; request goes to the fixed https host with the key header") {
            val t = FakeTransport()
            val r = runBlocking { client(t).complete(request) }
            r.shouldBeInstanceOf<LlmReply.Text>()
            (r as LlmReply.Text).text shouldBe "```kotlin\nval x = 1\n```"
            r.outputTokens shouldBe 7
            t.uri.toString() shouldBe "https://api.anthropic.com/v1/messages"
            t.headers["x-api-key"] shouldBe FAKE_KEY
            t.sent shouldBe t.sent // body captured
            t.sent shouldNotContain FAKE_KEY
            Regex("\"max_tokens\"\\s*:\\s*8192").containsMatchIn(t.sent) shouldBe true
            // tool whitelist is empty: no tools in the request
            (t.sent.contains("\"tools\"") && !Regex("\"tools\"\\s*:\\s*\\[\\s*]").containsMatchIn(t.sent)) shouldBe
                false
        }

        test("status codes map to failure kinds without content") {
            for ((status, kind) in listOf(
                401 to FailureKind.KEY_REJECTED,
                403 to FailureKind.KEY_REJECTED,
                429 to FailureKind.RATE_LIMITED,
                503 to FailureKind.PROVIDER_ERROR,
                302 to FailureKind.PROVIDER_ERROR,
            )) {
                val r =
                    runBlocking {
                        client(
                            FakeTransport(status, "{\"error\":\"$FAKE_KEY /Users/x/secret.txt\"}"),
                        ).complete(request)
                    }
                (r as LlmReply.Failed).kind shouldBe kind
                r.toString() shouldNotContain FAKE_KEY
                r.toString() shouldNotContain "/Users"
            }
        }

        test("transport failures and malformed bodies map to kinds") {
            (
                runBlocking {
                    client(FakeTransport(throwKind = FailureKind.TIMEOUT)).complete(request)
                } as LlmReply.Failed
            ).kind shouldBe
                FailureKind.TIMEOUT
            (
                runBlocking {
                    client(FakeTransport(throwKind = FailureKind.UNREACHABLE)).complete(request)
                } as LlmReply.Failed
            ).kind shouldBe
                FailureKind.UNREACHABLE
            (
                runBlocking {
                    client(
                        FakeTransport(body = "not json"),
                    ).complete(request)
                } as LlmReply.Failed
            ).kind shouldBe
                FailureKind.MALFORMED
            (
                runBlocking {
                    client(
                        FakeTransport(
                            bodyBytes =
                                ByteArray(300 * 1024) {
                                    'a'.code.toByte()
                                },
                        ),
                    ).complete(request)
                } as LlmReply.Failed
            ).kind shouldBe
                FailureKind.RESPONSE_TOO_LARGE
        }

        test("the guarded client refuses any other host or scheme") {
            val g =
                GuardedKoogHttpClient(
                    AiProvider.ANTHROPIC,
                    { emptyMap() },
                    FakeTransport(),
                    kotlinx.serialization.json.Json,
                    1000,
                )
            g.resolve("v1/messages").host shouldBe "api.anthropic.com"
            g.toString() shouldNotContain "key"
        }
    })
