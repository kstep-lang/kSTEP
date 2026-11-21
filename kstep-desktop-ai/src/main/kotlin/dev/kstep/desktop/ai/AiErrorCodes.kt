package dev.kstep.desktop.ai

/** The desktop's AI error codes (ADR-0056); they continue the `KSTEP-D-xxx` range of `DesktopErrorCodes` (D-001..D-013; the sandbox codes D-029/D-030 belong to the AI check, ADR-0059; D-031, the sandbox of the user flows, is flow-neutral and lives in `DesktopErrorCodes`, ADR-0062; D-034/D-035 belong to applying a subset of changes, ADR-0069). */
object AiErrorCodes {
    const val AI_PROVIDER_UNREACHABLE = "KSTEP-D-014"
    const val AI_PROVIDER_REJECTED = "KSTEP-D-015"
    const val AI_RESPONSE_INVALID = "KSTEP-D-016"
    const val AI_LIMIT_REACHED = "KSTEP-D-017"
    const val AI_KEY_STORE_FAILED = "KSTEP-D-018"

    const val AI_SANDBOX_UNAVAILABLE = "KSTEP-D-029"
    const val AI_SANDBOX_START_FAILED = "KSTEP-D-030"

    /** Choosing single changes needs the worker sandbox; without it nothing is applied (ADR-0069). */
    const val AI_PARTIAL_NEEDS_SANDBOX = "KSTEP-D-034"

    /** The editor text changed while a chosen subset was being checked (ADR-0069). */
    const val AI_EDITOR_CHANGED = "KSTEP-D-035"

    val all: List<String> =
        listOf(
            AI_PROVIDER_UNREACHABLE,
            AI_PROVIDER_REJECTED,
            AI_RESPONSE_INVALID,
            AI_LIMIT_REACHED,
            AI_KEY_STORE_FAILED,
            AI_SANDBOX_UNAVAILABLE,
            AI_SANDBOX_START_FAILED,
            AI_PARTIAL_NEEDS_SANDBOX,
            AI_EDITOR_CHANGED,
        )
}
