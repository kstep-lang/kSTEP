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
        /** The names the script suggested (ADR-0063), at most four; empty without an `export { }` block. */
        val scriptTargets: List<ScriptExportTarget> = emptyList(),
        /** Index into [scriptTargets] while the chosen format and file name are still the declared ones, else `null`. */
        val selectedScriptTarget: Int? = null,
    ) : ExportState

    data class Running(
        val format: ExportFormat,
        val target: Path,
        val phase: ExportPhase,
        val startedAtMs: Long,
        val replaceConfirmed: Boolean,
        override val generation: Long,
        /** Carried through a run so that a stopped or failed run returns to the same dialog (ADR-0063). */
        val scriptTargets: List<ScriptExportTarget> = emptyList(),
        val selectedScriptTarget: Int? = null,
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

    /** The user clicked one of the names the script suggested; [index] outside the list is ignored. */
    data class ChooseScriptTarget(
        val index: Int,
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
        scriptTargets: List<ScriptExportTarget> = emptyList(),
        selectedScriptTarget: Int? = null,
    ): ExportState.Configuring =
        ExportState.Configuring(
            format,
            target,
            existing?.let { Overwrite.NeedsConfirm(it) } ?: Overwrite.None,
            null,
            null,
            0,
            scriptTargets,
            selectedScriptTarget,
        )

    /** The format, marked script target and suggested file name the dialog opens with (ADR-0063). */
    data class Suggestion(
        val format: ExportFormat,
        val selectedScriptTarget: Int?,
        val fileName: String,
    )

    /** With a script block the first declared name wins over the last used format; without one nothing changes. */
    fun suggestion(
        scriptFileName: String,
        scriptTargets: List<ScriptExportTarget>,
        lastFormat: ExportFormat?,
    ): Suggestion {
        val first = scriptTargets.firstOrNull()
        if (first != null) return Suggestion(first.format, 0, first.fileName)
        val format = lastFormat ?: ExportFormat.DEFAULT
        return Suggestion(format, null, ExportNaming.defaultFileName(scriptFileName, format))
    }

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
                        selectedScriptTarget = null,
                        target = state.target?.let { retarget(it, event.format) },
                        // the extension changed, so the old existing-file check is void; Start re-checks
                        overwrite = Overwrite.None,
                        notice = null,
                        error = null,
                    ),
                )
            is ExportEvent.ChooseScriptTarget -> chooseScriptTarget(state, event.index)
            is ExportEvent.ChooseTarget ->
                ExportTransition(
                    state.copy(
                        target = event.target,
                        selectedScriptTarget =
                            state.selectedScriptTarget?.takeIf { selected ->
                                state.scriptTargets.getOrNull(selected)?.fileName == event.target.fileName?.toString()
                            },
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

    /** The mark stays only while the chosen file name is exactly the declared one. */
    private fun stillDeclared(
        state: ExportState.Configuring,
        target: Path,
    ): Int? {
        val selected = state.selectedScriptTarget ?: return null
        val declared = state.scriptTargets.getOrNull(selected) ?: return null
        return if (declared.fileName == target.fileName?.toString()) selected else null
    }

    /** The declared name replaces the file name in the folder already chosen (or none yet); the folder is never the script's. */
    private fun chooseScriptTarget(
        state: ExportState.Configuring,
        index: Int,
    ): ExportTransition {
        val declared = state.scriptTargets.getOrNull(index) ?: return ExportTransition(state)
        return ExportTransition(
            state.copy(
                format = declared.format,
                selectedScriptTarget = index,
                target = state.target?.resolveSibling(declared.fileName),
                overwrite = Overwrite.None,
                notice = null,
                error = null,
            ),
        )
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
            ExportState.Running(
                state.format,
                target,
                ExportPhase.STARTING,
                nowMs,
                confirmed,
                generation,
                state.scriptTargets,
                state.selectedScriptTarget,
            ),
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
                            state.scriptTargets,
                            state.selectedScriptTarget,
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
            ExportState.Configuring(
                state.format,
                state.target,
                overwrite,
                notice,
                error,
                state.generation,
                state.scriptTargets,
                state.selectedScriptTarget,
            ),
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
