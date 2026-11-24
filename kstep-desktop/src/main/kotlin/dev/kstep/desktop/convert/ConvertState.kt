package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertIssue
import dev.kstep.cli.ConvertedFile
import dev.kstep.cli.SkippedSource

/** A message for the dialog; [text] is already escaped or sanitized. */
data class ConvertMessage(
    val code: String,
    val text: String,
)

/**
 * The states of File > Convert (ADR-0077). [generation] changes with every transition, so a late result of an older
 * run never applies to a newer state.
 */
sealed interface ConvertState {
    val generation: Long

    data class Idle(
        override val generation: Long = 0,
    ) : ConvertState

    data class Editing(
        val request: ConvertRequest,
        val error: ConvertMessage? = null,
        override val generation: Long = 0,
    ) : ConvertState

    data class Running(
        val request: ConvertRequest,
        val replacing: Boolean,
        override val generation: Long = 0,
    ) : ConvertState

    data class Done(
        val request: ConvertRequest,
        val written: List<ConvertedFile>,
        val skipped: List<SkippedSource>,
        val warnings: List<ConvertIssue>,
        override val generation: Long = 0,
    ) : ConvertState

    /** Targets exist and nothing was written. [shown] holds at most [MAX_SHOWN] names, [more] the rest. */
    data class Conflict(
        val request: ConvertRequest,
        val shown: List<String>,
        val more: Int,
        val allConflicts: Int,
        override val generation: Long = 0,
    ) : ConvertState

    /** [written] is what was already on disk when the run failed (a run is not transactional). */
    data class Failed(
        val request: ConvertRequest,
        val errors: List<ConvertIssue>,
        val moreErrors: Int,
        val written: List<ConvertedFile>,
        val warnings: List<ConvertIssue>,
        override val generation: Long = 0,
    ) : ConvertState

    companion object {
        const val MAX_SHOWN = 50
    }
}
