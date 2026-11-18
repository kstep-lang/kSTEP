package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopError
import java.nio.file.Path

enum class ExportPhase { STARTING, RUNNING_SCRIPT, VALIDATING, WRITING }

/** Whether an existing target has to be confirmed before the export may replace it. */
sealed interface Overwrite {
    data object None : Overwrite

    data class NeedsConfirm(
        val bytes: Long,
    ) : Overwrite

    data object Confirmed : Overwrite
}

sealed interface ExportState {
    /** [generation] is monotonic over the whole dialog; late events of an older run are ignored. */
    val generation: Long

    data class Configuring(
        val format: ExportFormat,
        val target: Path?,
        val overwrite: Overwrite,
        val notice: String?,
        val error: DesktopError?,
        override val generation: Long,
    ) : ExportState

    data class Running(
        val format: ExportFormat,
        val target: Path,
        val phase: ExportPhase,
        val startedAtMs: Long,
        val replaceConfirmed: Boolean,
        override val generation: Long,
    ) : ExportState

    data class Succeeded(
        val fileName: String,
        val bytes: Long,
        val dir: Path,
        override val generation: Long,
    ) : ExportState
}

/** How one pipeline run ended. */
sealed interface PipelineOutcome {
    data class Success(
        val fileName: String,
        val bytes: Long,
        val dir: Path,
    ) : PipelineOutcome

    data class Failed(
        val error: DesktopError,
    ) : PipelineOutcome

    data class NeedsReplace(
        val bytes: Long,
    ) : PipelineOutcome

    data object Cancelled : PipelineOutcome
}

sealed interface ExportEvent {
    data class ChooseFormat(
        val format: ExportFormat,
    ) : ExportEvent

    /** [existing] is the size of a file already at the target (from [ExportTargets.check]), else `null`. */
    data class ChooseTarget(
        val target: Path,
        val existing: Long?,
    ) : ExportEvent

    /** The chosen target failed [ExportTargets.check]: shown in the dialog, the target stays as typed. */
    data class TargetRejected(
        val target: Path,
        val error: DesktopError,
    ) : ExportEvent

    data object ConfirmReplace : ExportEvent

    data class Start(
        val nowMs: Long,
    ) : ExportEvent

    data class PhaseChanged(
        val generation: Long,
        val phase: ExportPhase,
    ) : ExportEvent

    data class Finished(
        val generation: Long,
        val outcome: PipelineOutcome,
    ) : ExportEvent

    data object Stop : ExportEvent

    data object Close : ExportEvent
}

sealed interface ExportEffect {
    data class StartPipeline(
        val generation: Long,
        val format: ExportFormat,
        val target: Path,
        val replaceConfirmed: Boolean,
    ) : ExportEffect

    /** Kill the worker of this run; the files of the run are removed by the pipeline itself. */
    data class CancelRun(
        val generation: Long,
    ) : ExportEffect

    data object CloseDialog : ExportEffect
}

data class ExportTransition(
    val state: ExportState,
    val effects: List<ExportEffect> = emptyList(),
)

/**
 * The pure reducer of the export dialog (ADR-0057). Rules: `Start` only from `Configuring` with a target and no
 * unconfirmed replacement (so a double click cannot start a second run); `Stop` only while running and not yet writing
 * (the write is short and atomic: cancelling it would only leave a window for a half-state); a failed or stopped run
 * leads back to `Configuring` with the settings kept; events of an older generation are ignored.
 */
object ExportStateMachine {
    fun initial(
        format: ExportFormat,
        target: Path?,
        existing: Long? = null,
    ): ExportState.Configuring =
        ExportState.Configuring(
            format,
            target,
            existing?.let { Overwrite.NeedsConfirm(it) } ?: Overwrite.None,
            null,
            null,
            0,
        )

    fun reduce(
        state: ExportState,
        event: ExportEvent,
    ): ExportTransition =
        when (state) {
            is ExportState.Configuring -> configuring(state, event)
            is ExportState.Running -> running(state, event)
            is ExportState.Succeeded ->
                if (event == ExportEvent.Close) {
                    ExportTransition(state, listOf(ExportEffect.CloseDialog))
                } else {
                    ExportTransition(state)
                }
        }

    private fun configuring(
        state: ExportState.Configuring,
        event: ExportEvent,
    ): ExportTransition =
        when (event) {
            is ExportEvent.ChooseFormat ->
                ExportTransition(
                    state.copy(
                        format = event.format,
                        target = state.target?.let { retarget(it, event.format) },
                        // the extension changed, so the old existing-file check is void; Start re-checks
                        overwrite = Overwrite.None,
                        notice = null,
                        error = null,
                    ),
                )
            is ExportEvent.ChooseTarget ->
                ExportTransition(
                    state.copy(
                        target = event.target,
                        overwrite = event.existing?.let { Overwrite.NeedsConfirm(it) } ?: Overwrite.None,
                        notice = null,
                        error = null,
                    ),
                )
            is ExportEvent.TargetRejected ->
                ExportTransition(
                    state.copy(target = event.target, overwrite = Overwrite.None, notice = null, error = event.error),
                )
            ExportEvent.ConfirmReplace ->
                ExportTransition(
                    if (state.overwrite is Overwrite.NeedsConfirm) {
                        state.copy(
                            overwrite = Overwrite.Confirmed,
                        )
                    } else {
                        state
                    },
                )
            is ExportEvent.Start -> start(state, event.nowMs)
            ExportEvent.Close -> ExportTransition(state, listOf(ExportEffect.CloseDialog))
            else -> ExportTransition(state)
        }

    private fun start(
        state: ExportState.Configuring,
        nowMs: Long,
    ): ExportTransition {
        val target = state.target
        if (target == null || state.overwrite is Overwrite.NeedsConfirm) return ExportTransition(state)
        val generation = state.generation + 1
        val confirmed = state.overwrite is Overwrite.Confirmed
        return ExportTransition(
            ExportState.Running(state.format, target, ExportPhase.STARTING, nowMs, confirmed, generation),
            listOf(ExportEffect.StartPipeline(generation, state.format, target, confirmed)),
        )
    }

    private fun running(
        state: ExportState.Running,
        event: ExportEvent,
    ): ExportTransition =
        when (event) {
            is ExportEvent.PhaseChanged ->
                if (event.generation == state.generation) {
                    ExportTransition(state.copy(phase = event.phase))
                } else {
                    ExportTransition(state)
                }
            is ExportEvent.Finished ->
                if (event.generation ==
                    state.generation
                ) {
                    finished(state, event.outcome)
                } else {
                    ExportTransition(state)
                }
            ExportEvent.Stop ->
                if (state.phase == ExportPhase.WRITING) {
                    ExportTransition(state)
                } else {
                    ExportTransition(
                        ExportState.Configuring(
                            state.format,
                            state.target,
                            if (state.replaceConfirmed) Overwrite.Confirmed else Overwrite.None,
                            ExportText.STOPPED_NOTICE,
                            null,
                            state.generation,
                        ),
                        listOf(ExportEffect.CancelRun(state.generation)),
                    )
                }
            else -> ExportTransition(state)
        }

    private fun finished(
        state: ExportState.Running,
        outcome: PipelineOutcome,
    ): ExportTransition {
        fun back(
            overwrite: Overwrite,
            notice: String?,
            error: DesktopError?,
        ) = ExportTransition(
            ExportState.Configuring(state.format, state.target, overwrite, notice, error, state.generation),
        )
        return when (outcome) {
            is PipelineOutcome.Success ->
                ExportTransition(ExportState.Succeeded(outcome.fileName, outcome.bytes, outcome.dir, state.generation))
            is PipelineOutcome.Failed -> back(Overwrite.None, null, outcome.error)
            is PipelineOutcome.NeedsReplace -> back(Overwrite.NeedsConfirm(outcome.bytes), null, null)
            PipelineOutcome.Cancelled -> back(Overwrite.None, ExportText.STOPPED_NOTICE, null)
        }
    }

    /** The target with the file name's extension adjusted to [format] (a name the user typed keeps its stem). */
    fun retarget(
        target: Path,
        format: ExportFormat,
    ): Path = target.resolveSibling(ExportNaming.withExtension(target.fileName.toString(), format))
}
