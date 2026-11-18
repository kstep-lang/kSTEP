package dev.kstep.desktop.ai.llm

/** One request to the model, without tools (ADR-0056: the tool whitelist is empty). */
interface LlmClient {
    /** One call. Never throws with a key or a response body in the message; cancellation is propagated. */
    suspend fun complete(request: LlmRequest): LlmReply
}

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val maxOutputTokens: Int,
)

data class LlmMessage(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, ASSISTANT }
}

sealed interface LlmReply {
    data class Text(
        val text: String,
        val outputTokens: Int?,
    ) : LlmReply

    data class Failed(
        val kind: FailureKind,
        val httpStatus: Int? = null,
    ) : LlmReply
}

enum class FailureKind {
    UNREACHABLE,
    TIMEOUT,
    KEY_REJECTED,
    RATE_LIMITED,
    PROVIDER_ERROR,
    RESPONSE_TOO_LARGE,
    MALFORMED,
}
