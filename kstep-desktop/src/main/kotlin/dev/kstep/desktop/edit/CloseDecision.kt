package dev.kstep.desktop.edit

/** The answer to "save the changes?" when closing the window or opening another file (ADR-0052). */
enum class CloseDecision { SAVE, DONT_SAVE, CANCEL }

/**
 * Whether the window may close / the next file may open after [decision]. A failed save keeps everything as it is:
 * the buffer is never lost to a dialog answer.
 */
fun mayProceed(
    decision: CloseDecision,
    save: () -> Boolean,
): Boolean =
    when (decision) {
        CloseDecision.SAVE -> save()
        CloseDecision.DONT_SAVE -> true
        CloseDecision.CANCEL -> false
    }
