package dev.kstep.desktop.draft

/** The limits and timings of the crash draft (ADR-0084). */
object DraftPolicy {
    const val DIR_NAME = "drafts"
    const val SUFFIX = ".draft"

    /** The same limit as the editor buffer (`EditorText.MAX_BYTES`; a test asserts the equality). */
    const val MAX_BODY_BYTES = 1_048_576
    const val MAX_HEADER_BYTES = 8_192
    const val MAX_FILE_BYTES = MAX_BODY_BYTES + MAX_HEADER_BYTES
    const val MAX_PATH_BYTES = 4_096

    /** The same limit as the editor buffer (`EditorText.MAX_LINE_CHARS`; a test asserts the equality). */
    const val MAX_LINE_CHARS = 20_000
    const val MAX_DRAFTS = 32
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_TEMP_AGE_MS = 60L * 60 * 1000
    const val DEBOUNCE_MS = 2_000L
    const val MAX_DELAY_MS = 20_000L
    const val SHUTDOWN_BUDGET_MS = 2_000L
    const val MAX_DIR_ENTRIES_SCANNED = 1_024
    val NAME = Regex("^[0-9a-f]{64}\\.draft$")
    val TEMP_NAME = Regex("^\\.draft-[0-9A-Za-z]+\\.tmp$")
}
