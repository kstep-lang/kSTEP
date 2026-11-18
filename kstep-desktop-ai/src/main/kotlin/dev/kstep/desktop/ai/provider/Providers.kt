package dev.kstep.desktop.ai.provider

/**
 * The fixed provider whitelist (ADR-0056). There is deliberately no free host field: the host is a compile-time
 * constant per provider, so a settings file or a model answer can never redirect a request.
 */
enum class AiProvider(
    val id: String,
    val displayName: String,
    val host: String,
    val envVar: String,
) {
    ANTHROPIC("anthropic", "Anthropic", "api.anthropic.com", "KSTEP_AI_ANTHROPIC_KEY"),
    ;

    /** Always `https`, never configurable. */
    val baseUrl: String get() = "https://$host"
}

/** A model offered for a provider; [id] is the Anthropic model id the request carries. */
data class AiModel(
    val id: String,
    val displayName: String,
)

object ProviderCatalog {
    private val anthropic =
        listOf(
            AiModel("claude-sonnet-5", "Sonnet 5"),
            AiModel("claude-opus-5", "Opus 5"),
            AiModel("claude-haiku-4-5", "Haiku 4.5"),
        )

    fun models(p: AiProvider): List<AiModel> =
        when (p) {
            AiProvider.ANTHROPIC -> anthropic
        }

    fun defaultModel(p: AiProvider): AiModel = models(p).first()

    fun modelById(
        p: AiProvider,
        id: String?,
    ): AiModel = models(p).firstOrNull { it.id == id } ?: defaultModel(p)

    /** An unknown id (e.g. from a hand-edited settings file) gives `null`. */
    fun byId(id: String): AiProvider? = AiProvider.entries.firstOrNull { it.id == id }
}
