package dev.kstep.desktop.mcp

import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState

/**
 * Collects what the window shows and publishes a new snapshot when something changed (ADR-0096 D2/D3). UI thread only.
 * Each input is compared as it arrives: the editor and the run state by identity, the view by value, so a hover (which
 * [McpViewInput] leaves out) and a recomposition without change publish nothing and do not move the revision.
 */
class McpSnapshotPublisher(
    private val host: SnapshotHost,
) {
    private var editor: EditorView? = null
    private var state: DesktopState? = null
    private var knowledgeOpen = false
    private var editorIsScript = false
    private var view = McpViewInput.EMPTY
    private var hasEditorInput = false

    private var sceneModel: DesktopModel? = null
    private var scene: McpScene? = null

    var revision: Long = 0L
        private set

    fun onEditorOrRun(
        editor: EditorView,
        state: DesktopState,
        knowledgeOpen: Boolean,
        editorIsScript: Boolean,
    ) {
        if (hasEditorInput &&
            editor === this.editor &&
            state === this.state &&
            knowledgeOpen == this.knowledgeOpen &&
            editorIsScript == this.editorIsScript
        ) {
            return
        }
        this.editor = editor
        this.state = state
        this.knowledgeOpen = knowledgeOpen
        this.editorIsScript = editorIsScript
        hasEditorInput = true
        publish()
    }

    fun onView(input: McpViewInput) {
        if (input == view) return
        view = input
        if (hasEditorInput) publish()
    }

    private fun publish() {
        val e = editor ?: return
        val s = state ?: return
        revision++
        host.publish(
            McpSnapshotMapper.of(e, s, knowledgeOpen, editorIsScript, view, revision, sceneFor(s)),
        )
    }

    /** The scene of the shown model; the same instance while the model is the same (identity). */
    private fun sceneFor(s: DesktopState): McpScene? {
        val model = if (s is DesktopState.NativeUnavailable) null else s.lastGood
        if (model !== sceneModel) {
            sceneModel = model
            scene = model?.parts?.let { McpScene(it, model.tree, model.generation) }
        }
        return scene
    }
}
