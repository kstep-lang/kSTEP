package dev.kstep.desktop.mcp

import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.PreviewOrigin
import dev.kstep.desktop.run.Severity

/** Turns the editor view and the run state into a [DesktopStateSnapshot]. Pure; the UI calls it, the tools never. */
object McpSnapshotMapper {
    fun of(
        editor: EditorView,
        state: DesktopState,
        knowledgeOpen: Boolean,
        editorIsScript: Boolean,
        view: McpViewInput = McpViewInput.EMPTY,
        revision: Long = 0L,
        scene: McpScene? = null,
    ): DesktopStateSnapshot {
        val editing = editor.state as? EditorState.Editing
        val kind =
            when {
                editing == null -> EditorKind.NONE
                editorIsScript -> EditorKind.SCRIPT
                else -> EditorKind.NOTE
            }
        val model = state.lastGood
        return DesktopStateSnapshot(
            filePath = editor.state.path ?: state.file,
            editorKind = kind,
            editorText = editing?.text,
            dirty = editor.dirty,
            run = runKind(state),
            previewOrigin =
                originOf(state)?.let {
                    if (it is PreviewOrigin.Buffer) PreviewKind.UNSAVED_BUFFER else PreviewKind.SAVED_FILE
                },
            parts = model?.parts?.parts?.size,
            triangles = model?.triangles,
            draftOfferOpen = editor.draftOffer != null,
            knowledgeWorkspace = knowledgeOpen,
            waitingHint = (state as? DesktopState.Waiting)?.hint,
            revision = revision,
            runReport = reportOf(state),
            scene = scene,
            selection = view.selection,
            visibility = view.visibility,
            viewSettings = view.settings,
        )
    }

    private fun reportOf(state: DesktopState): McpRunReport? =
        when (state) {
            is DesktopState.Error -> report(state.errors, Severity.ERROR, state.origin)
            is DesktopState.Ok ->
                state.model.warnings
                    .takeIf {
                        it.isNotEmpty()
                    }?.let { report(it, Severity.WARNING, state.model.origin) }
            else -> null
        }

    private fun report(
        errors: List<DesktopError>,
        severity: Severity,
        origin: PreviewOrigin,
    ) = McpRunReport(
        errors = errors,
        severity = severity,
        origin = if (origin is PreviewOrigin.Buffer) PreviewKind.UNSAVED_BUFFER else PreviewKind.SAVED_FILE,
        bufferText = (origin as? PreviewOrigin.Buffer)?.text,
    )

    private fun runKind(state: DesktopState): RunKind =
        when (state) {
            DesktopState.Idle -> RunKind.IDLE
            is DesktopState.Running -> RunKind.RUNNING
            is DesktopState.Ok -> RunKind.OK
            is DesktopState.Error -> RunKind.ERROR
            is DesktopState.NativeUnavailable -> RunKind.UNAVAILABLE
            is DesktopState.Waiting -> RunKind.IDLE
        }

    private fun originOf(state: DesktopState): PreviewOrigin? =
        when (state) {
            is DesktopState.Running -> state.origin
            is DesktopState.Error -> state.origin
            is DesktopState.Ok -> state.model.origin
            else -> null
        }
}
