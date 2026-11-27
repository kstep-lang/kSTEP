package dev.kstep.desktop.run

/** One problem shown in the error bar (ADR-0051). [code] is a `KSTEP-S-xxx` script code or a `KSTEP-D-xxx` desktop code. */
data class DesktopError(
    val code: String,
    val line: Int?,
    val column: Int?,
    val message: String,
    val severity: Severity = Severity.ERROR,
)

enum class Severity { ERROR, WARNING }

/** The desktop's own error codes (ADR-0051); script errors keep the `KSTEP-S-xxx` codes of `kstep render`. */
object DesktopErrorCodes {
    const val TIMEOUT = "KSTEP-D-001"
    const val HEAP_EXHAUSTED = "KSTEP-D-002"
    const val OUTPUT_TOO_LARGE = "KSTEP-D-003"
    const val TOO_MANY_TRIANGLES = "KSTEP-D-004"
    const val FILE_MISSING = "KSTEP-D-005"
    const val WORKER_START_FAILED = "KSTEP-D-006"
    const val RESULT_UNREADABLE = "KSTEP-D-007"
    const val PREVIEW_FAILED = "KSTEP-D-008"
    const val EDITOR_TOO_LARGE = "KSTEP-D-009"
    const val EDITOR_NOT_TEXT = "KSTEP-D-010"
    const val SAVE_FAILED = "KSTEP-D-011"
    const val PARTS_INVALID = "KSTEP-D-012"
    const val TREE_INVALID = "KSTEP-D-013"

    // The export dialog (ADR-0057).
    const val EXPORT_UNAVAILABLE = "KSTEP-D-019"
    const val EXPORT_TARGET_INVALID = "KSTEP-D-020"
    const val EXPORT_TARGET_NOT_WRITABLE = "KSTEP-D-021"
    const val EXPORT_FAILED = "KSTEP-D-022"
    const val EXPORT_WRITE_FAILED = "KSTEP-D-023"
    const val EXPORT_OUTPUT_INVALID = "KSTEP-D-024"

    // The knowledge workspace (ADR-0058).
    const val WORKSPACE_UNAVAILABLE = "KSTEP-D-025"
    const val WORKSPACE_PATH_REJECTED = "KSTEP-D-026"
    const val WORKSPACE_LIMIT = "KSTEP-D-027"
    const val BLOCK_NOT_CLOSED = "KSTEP-D-028"

    // The sandbox of the user's own flows (ADR-0062); D-029 and D-030 belong to the AI check (AiErrorCodes).
    const val SANDBOX_START_FAILED = "KSTEP-D-031"

    // Recent and drag-and-drop (ADR-0066).
    const val RECENT_UNAVAILABLE = "KSTEP-D-032"
    const val DROP_REJECTED = "KSTEP-D-033"

    // File > Convert (ADR-0077): an unexpected failure of the conversion service itself.
    const val CONVERT_FAILED = "KSTEP-D-036"

    // The input files a script reads (ADR-0080): the worker's dependency list was not trustworthy and was ignored.
    const val DEPENDENCIES_IGNORED = "KSTEP-D-037"

    // The worker of the packaged app could not be located or is incomplete (ADR-0082).
    const val WORKER_MISSING = "KSTEP-D-038"

    // The crash draft (ADR-0084): it could not be written, a draft was refused, the folder is unusable, the text is too large.
    const val DRAFT_WRITE_FAILED = "KSTEP-D-042"
    const val DRAFT_REJECTED = "KSTEP-D-043"
    const val DRAFT_STORE_UNAVAILABLE = "KSTEP-D-044"
    const val DRAFT_TOO_LARGE = "KSTEP-D-045"

    // Run Unsaved (ADR-0086): the text of the buffer is too large, the private folder is not safe, writing it failed,
    // the buffer does not belong to the previewed file or its folder is unusable.
    const val UNSAVED_RUN_TOO_LARGE = "KSTEP-D-046"
    const val SESSION_DIR_UNSAFE = "KSTEP-D-047"
    const val UNSAVED_RUN_WRITE_FAILED = "KSTEP-D-048"
    const val SCRIPT_DIR_MISMATCH = "KSTEP-D-049"

    // The MCP server (ADR-0095): no free port in the fallback range, the token could not be stored, the settings could
    // not be saved.
    const val MCP_NO_FREE_PORT = "KSTEP-D-050"
    const val MCP_TOKEN_WRITE_FAILED = "KSTEP-D-051"
    const val MCP_SETTINGS_WRITE_FAILED = "KSTEP-D-052"
}
