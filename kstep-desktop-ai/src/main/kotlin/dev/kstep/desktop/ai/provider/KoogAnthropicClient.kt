package dev.kstep.desktop.ai.provider

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.llm.FailureKind
import dev.kstep.desktop.ai.llm.LlmClient
import dev.kstep.desktop.ai.llm.LlmMessage
import dev.kstep.desktop.ai.llm.LlmReply
import dev.kstep.desktop.ai.llm.LlmRequest
import dev.kstep.desktop.ai.repair.AiLimits
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * [LlmClient] on Koog's Anthropic client with our guarded transport (ADR-0056). Koog is used only as an Anthropic
 * message encoder/decoder: no agent, no tools (`tools = emptyList()`), no streaming. Every exception becomes a
 * [LlmReply.Failed] by kind; `e.message` is never used.
 */
class KoogAnthropicClient(
    private val modelId: String,
    private val key: Secret,
    transport: HttpTransport,
    private val limits: AiLimits,
) : LlmClient {
    @OptIn(ExperimentalSerializationApi::class)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
            explicitNulls = false
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
    private val settings =
        AnthropicClientSettings(
            baseUrl = AiProvider.ANTHROPIC.baseUrl,
            timeoutConfig = ConnectionTimeoutConfig(requestTimeoutMillis = limits.perCallMillis),
        )
    private val http =
        GuardedKoogHttpClient(
            AiProvider.ANTHROPIC,
            { mapOf("x-api-key" to key.reveal(), "anthropic-version" to settings.apiVersion) },
            transport,
            json,
            limits.maxResponseBytes,
        )
    private val client = AnthropicLLMClient(settings, http)

    private fun model(): LLModel =
        listOf(AnthropicModels.Sonnet_5, AnthropicModels.Opus_5, AnthropicModels.Haiku_4_5).first { it.id == modelId }

    override suspend fun complete(request: LlmRequest): LlmReply =
        try {
            val p =
                prompt("kstep-ai", LLMParams(maxTokens = request.maxOutputTokens)) {
                    system(request.system)
                    request.messages.forEach {
                        when (it.role) {
                            LlmMessage.Role.USER -> user(it.text)
                            LlmMessage.Role.ASSISTANT -> assistant(it.text)
                        }
                    }
                }
            val reply = client.execute(p, model(), emptyList())
            val text = reply.textContent()
            if (text.isBlank()) {
                LlmReply.Failed(
                    FailureKind.MALFORMED,
                )
            } else {
                LlmReply.Text(text, reply.metaInfo.outputTokensCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            var t: Throwable? = e
            var found: AiTransportException? = null
            var depth = 0
            while (t != null && found == null && depth++ < MAX_CAUSE_DEPTH) {
                found = t as? AiTransportException
                t = t.cause
            }
            LlmReply.Failed(found?.kind ?: FailureKind.PROVIDER_ERROR, found?.httpStatus)
        }

    private companion object {
        const val MAX_CAUSE_DEPTH = 8
    }
}
