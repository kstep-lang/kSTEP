package dev.kstep.desktop.ai

/** The desktop's AI error codes (ADR-0056); they continue the `KSTEP-D-xxx` range of `DesktopErrorCodes` (D-001..D-013; the sandbox codes D-029/D-030 are the last, ADR-0059). */
object AiErrorCodes {
    const val AI_PROVIDER_UNREACHABLE = "KSTEP-D-014"
    const val AI_PROVIDER_REJECTED = "KSTEP-D-015"
    const val AI_RESPONSE_INVALID = "KSTEP-D-016"
    const val AI_LIMIT_REACHED = "KSTEP-D-017"
    const val AI_KEY_STORE_FAILED = "KSTEP-D-018"

    const val AI_SANDBOX_UNAVAILABLE = "KSTEP-D-029"
    const val AI_SANDBOX_START_FAILED = "KSTEP-D-030"

    val all: List<String> =
        listOf(
            AI_PROVIDER_UNREACHABLE,
            AI_PROVIDER_REJECTED,
            AI_RESPONSE_INVALID,
            AI_LIMIT_REACHED,
            AI_KEY_STORE_FAILED,
            AI_SANDBOX_UNAVAILABLE,
            AI_SANDBOX_START_FAILED,
        )
}
