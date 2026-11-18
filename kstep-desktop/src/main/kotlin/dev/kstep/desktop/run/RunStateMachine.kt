package dev.kstep.desktop.run

import java.nio.file.Path
import java.time.Instant

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

    data class Finished(
        val generation: Long,
        val result: RunResult,
    ) : DesktopEvent
}

sealed interface Effect {
    data class StartRun(
        val generation: Long,
        val file: Path,
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

    private fun finished(
        state: DesktopState,
        event: DesktopEvent.Finished,
        now: Instant,
    ): Transition {
        val file = state.file
        if (file == null || event.generation != state.generation) return Transition(state, emptyList())
        val next =
            when (val result = event.result) {
                is RunResult.Success -> DesktopState.Ok(file, event.generation, result.model)
                is RunResult.Failure -> DesktopState.Error(file, event.generation, result.errors, state.lastGood, now)
                is RunResult.NativeUnavailable ->
                    DesktopState.NativeUnavailable(file, event.generation, result.reason, state.lastGood)
                is RunResult.Skipped -> DesktopState.Waiting(file, event.generation, result.hint, state.lastGood)
            }
        return Transition(next, emptyList())
    }
}
