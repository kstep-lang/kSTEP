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
}
