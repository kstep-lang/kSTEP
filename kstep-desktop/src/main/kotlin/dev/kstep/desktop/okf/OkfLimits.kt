package dev.kstep.desktop.okf

import dev.kstep.desktop.edit.EditorText

/** Every bound of the knowledge workspace (ADR-0058). The scan and the extraction never exceed them. */
object OkfLimits {
    const val MAX_MD_FILES = 2_000
    const val MAX_DEPTH = 8
    const val MAX_DIR_ENTRIES = 20_000
    const val MAX_SCAN_BYTES = 64L shl 20
    const val MAX_SCAN_MILLIS = 10_000L

    /** A note must be openable in the editor, so the list limit is the editor limit (ADR-0058, D2). */
    val MAX_FILE_BYTES: Long = EditorText.MAX_BYTES.toLong()
    const val MAX_BLOCKS = 200
    const val MAX_BLOCK_BYTES = 256 * 1024
    const val MAX_FENCE_RUN = 64
    const val MAX_OTHER_FENCES = 1_000

    /** Only this much of an info string is looked at; the rest of a (hostile) line belongs to the content. */
    const val MAX_INFO_CHARS = 64 * 1024
    const val FM_MAX_BYTES = 16 * 1024
    const val FM_MAX_LINES = 200
    const val FM_MAX_KEYS = 64
    const val FM_MAX_SCALAR = 1_024
    const val FM_MAX_TAGS = 32
    const val DEBOUNCE_MS = 300L
}
