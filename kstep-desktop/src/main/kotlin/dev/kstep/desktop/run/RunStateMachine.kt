package dev.kstep.desktop.run

import java.nio.file.Path
import java.time.Instant

/** Which text the shown picture came from (ADR-0086): the saved file, or the editor buffer at that moment. */
sealed interface PreviewOrigin {
    data object SavedFile : PreviewOrigin

    /** [text] is the exact text that was sent, kept so that error marks are placed against it (ADR-0085 D6). */
    data class Buffer(
        val text: String,
    ) : PreviewOrigin
}

/** The state of the live preview. [lastGood] is the last successful model of the current file (ADR-0051). */
sealed interface DesktopState {
    val generation: Long
    val file: Path?
    val lastGood: DesktopModel?

    data object Idle : DesktopState {
        override val generation: Long = 0
        override val file: Path? = null
        override val lastGood: DesktopModel? = null
    }

    data class Running(
        override val file: Path,
        override val generation: Long,
        override val lastGood: DesktopModel?,
        val startedAtMs: Long,
        val origin: PreviewOrigin = PreviewOrigin.SavedFile,
    ) : DesktopState

    data class Ok(
        override val file: Path,
        override val generation: Long,
        val model: DesktopModel,
    ) : DesktopState {
        override val lastGood: DesktopModel get() = model
    }

    data class Error(
        override val file: Path,
        override val generation: Long,
        val errors: List<DesktopError>,
        override val lastGood: DesktopModel?,
        val at: Instant,
        /** What the failed run was run on; the error marks refer to this text. */
        val origin: PreviewOrigin = PreviewOrigin.SavedFile,
    ) : DesktopState

    data class NativeUnavailable(
        override val file: Path,
        override val generation: Long,
        val reason: String,
        override val lastGood: DesktopModel?,
    ) : DesktopState

    /** The run had nothing to do ([hint] says why, ADR-0058); the last good model stays shown. */
    data class Waiting(
        override val file: Path,
        override val generation: Long,
        val hint: String,
        override val lastGood: DesktopModel?,
    ) : DesktopState
}

sealed interface DesktopEvent {
    /** [floor] keeps generations unique across a [Close]: the new generation is above both the state's and the floor. */
    data class Open(
        val path: Path,
        val floor: Long = 0,
    ) : DesktopEvent

    /** The file is closed (the knowledge workspace was closed, ADR-0058): back to [DesktopState.Idle]. */
    data object Close : DesktopEvent

    data object Changed : DesktopEvent

    data object Missing : DesktopEvent

    data object ForceRun : DesktopEvent

    /** Run [text], the unsaved buffer of [file] (ADR-0086). Never started by a file change. */
    data class RunBuffer(
        val file: Path,
        val text: String,
    ) : DesktopEvent

    data class Finished(
        val generation: Long,
        val result: RunResult,
    ) : DesktopEvent
}

sealed interface Effect {
    data class StartRun(
        val generation: Long,
        val file: Path,
        /** The unsaved text to run instead of the file (ADR-0086), or `null`. */
        val buffer: String? = null,
    ) : Effect

    data object CancelRun : Effect

    data object ResetCamera : Effect
}

data class Transition(
    val state: DesktopState,
    val effects: List<Effect>,
)

/** The pure reducer of the live preview: no threads, no I/O, no clock of its own (ADR-0051). */
class RunStateMachine {
    fun reduce(
        state: DesktopState,
        event: DesktopEvent,
        nowMs: Long,
        now: Instant,
    ): Transition =
        when (event) {
            is DesktopEvent.Open -> {
                val gen = maxOf(state.generation, event.floor) + 1
                Transition(
                    DesktopState.Running(event.path, gen, lastGood = null, startedAtMs = nowMs),
                    listOf(Effect.CancelRun, Effect.ResetCamera, Effect.StartRun(gen, event.path)),
                )
            }
            DesktopEvent.Changed, DesktopEvent.ForceRun -> {
                val file = state.file
                if (file == null) {
                    Transition(state, emptyList())
                } else {
                    val gen = state.generation + 1
                    Transition(
                        DesktopState.Running(file, gen, state.lastGood, nowMs),
                        listOf(Effect.CancelRun, Effect.StartRun(gen, file)),
                    )
                }
            }
            is DesktopEvent.RunBuffer -> runBuffer(state, event, nowMs, now)
            DesktopEvent.Close -> Transition(DesktopState.Idle, listOf(Effect.CancelRun))
            DesktopEvent.Missing -> {
                val file = state.file
                if (file == null) {
                    Transition(state, emptyList())
                } else {
                    val error =
                        DesktopError(
                            DesktopErrorCodes.FILE_MISSING,
                            null,
                            null,
                            "file not found (waiting for it to reappear)",
                        )
                    Transition(
                        DesktopState.Error(file, state.generation + 1, listOf(error), state.lastGood, now),
                        listOf(Effect.CancelRun),
                    )
                }
            }
            is DesktopEvent.Finished -> finished(state, event, now)
        }

    private fun runBuffer(
        state: DesktopState,
        event: DesktopEvent.RunBuffer,
        nowMs: Long,
        now: Instant,
    ): Transition {
        val file = state.file
        if (file == null || event.file != file) {
            // A buffer of another file than the previewed one starts nothing. A run in progress goes on and its picture
            // stays; the refusal is only shown when no run is going (nothing is cancelled either way).
            if (file == null || state is DesktopState.Running) return Transition(state, emptyList())
            val error =
                DesktopError(
                    DesktopErrorCodes.SCRIPT_DIR_MISMATCH,
                    null,
                    null,
                    "the buffer does not belong to the previewed file",
                )
            return Transition(
                DesktopState.Error(file, state.generation + 1, listOf(error), state.lastGood, now),
                emptyList(),
            )
        }
        val gen = state.generation + 1
        return Transition(
            DesktopState.Running(file, gen, state.lastGood, nowMs, PreviewOrigin.Buffer(event.text)),
            listOf(Effect.CancelRun, Effect.StartRun(gen, file, event.text)),
        )
    }

    private fun finished(
        state: DesktopState,
        event: DesktopEvent.Finished,
        now: Instant,
    ): Transition {
        val file = state.file
        if (file == null || event.generation != state.generation) return Transition(state, emptyList())
        val origin = (state as? DesktopState.Running)?.origin ?: PreviewOrigin.SavedFile
        val next =
            when (val result = event.result) {
                is RunResult.Success -> DesktopState.Ok(file, event.generation, result.model.copy(origin = origin))
                is RunResult.Failure ->
                    DesktopState.Error(file, event.generation, result.errors, state.lastGood, now, origin)
                is RunResult.NativeUnavailable ->
                    DesktopState.NativeUnavailable(file, event.generation, result.reason, state.lastGood)
                is RunResult.Skipped -> DesktopState.Waiting(file, event.generation, result.hint, state.lastGood)
            }
        return Transition(next, emptyList())
    }
}
