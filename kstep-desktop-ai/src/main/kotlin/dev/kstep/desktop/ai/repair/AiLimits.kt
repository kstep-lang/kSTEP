package dev.kstep.desktop.ai.repair

/** All bounds of the AI panel in one place (ADR-0056, limits table). */
data class AiLimits(
    val maxAttempts: Int = 4,
    val totalMillis: Long = 180_000,
    val perCallMillis: Long = 60_000,
    val maxResponseBytes: Int = 200 * 1024,
    /** The desktop passes `EditorText.MAX_BYTES` (1 MiB). */
    val maxScriptBytes: Int = 1_048_576,
    val maxOutputTokens: Int = 8_192,
    val maxPromptChars: Int = 4_000,
    val maxIncludedScriptBytes: Int = 128 * 1024,
    val maxErrorSummaryBytes: Int = 4 * 1024,
)
