package dev.kstep.desktop.ui

import dev.kstep.desktop.edit.AgentEditOutcome
import dev.kstep.desktop.edit.CaretMapper
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.mcp.AgentEditResult
import dev.kstep.desktop.mcp.AgentNoticeKind
import dev.kstep.desktop.mcp.EditorKind
import dev.kstep.desktop.mcp.McpActionPort
import dev.kstep.desktop.mcp.McpActionScene
import dev.kstep.desktop.mcp.McpEditorCapture
import dev.kstep.desktop.mcp.McpRunGate
import dev.kstep.desktop.mcp.McpRunMode
import dev.kstep.desktop.mcp.McpRunOutcome
import dev.kstep.desktop.mcp.McpShowTarget
import dev.kstep.desktop.menu.MenuContext
import dev.kstep.desktop.menu.ViewAction
import dev.kstep.desktop.menu.stateOf
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.ShowInScriptInput
import dev.kstep.desktop.pick.VisibilityState

/**
 * What the window gives the MCP action tools (ADR-0097). All methods except [awaitRun] run on the UI thread. It reads
 * the editor fresh from the session, not from a composed copy, and it starts a run only through the same entries the
 * menu uses ([runStarter]), judged by the same rules ([menuContext]). By construction it cannot write a file, leave the
 * app or use the clipboard; the architecture test checks the source for that.
 */
internal class DesktopMcpActionPort(
    private val session: EditorSession,
    private val menuContext: () -> MenuContext,
    private val knowledgeOpen: () -> Boolean,
    private val runStarter: (McpRunMode) -> Unit,
    private val generation: () -> Long,
    private val runAwaiter: (afterGeneration: Long, timeoutMs: Long) -> McpRunOutcome?,
    private val selectionGet: () -> SelectionState,
    private val selectionSet: (SelectionState) -> Unit,
    private val visibilityGet: () -> VisibilityState,
    private val visibilitySet: (VisibilityState) -> Unit,
    private val sceneNow: () -> McpActionScene?,
    private val partsNow: () -> PartTable?,
    private val displaySourceText: () -> String?,
    private val jumpTo: (line: Int) -> Unit,
    private val notice: (AgentNoticeKind) -> Unit,
    private val sandboxed: () -> Boolean = { true },
    private val autoPreview: () -> Boolean = { false },
) : McpActionPort {
    private fun isScript(): Boolean = menuContext().editorIsScript && !knowledgeOpen()

    override fun editor(): McpEditorCapture {
        val view = session.view.value
        val editing = view.state as? EditorState.Editing
        val kind =
            when {
                editing == null -> EditorKind.NONE
                isScript() -> EditorKind.SCRIPT
                else -> EditorKind.NOTE
            }
        return McpEditorCapture(kind, editing?.text, view.draftOffer != null, view.dirty)
    }

    override fun applyAgentEdit(
        expected: String,
        newText: String,
    ): AgentEditResult =
        when (session.applyAgentEdit(expected, newText)) {
            AgentEditOutcome.APPLIED -> AgentEditResult.APPLIED
            AgentEditOutcome.UNCHANGED -> AgentEditResult.UNCHANGED
            AgentEditOutcome.STALE -> AgentEditResult.STALE
            AgentEditOutcome.NOT_EDITING -> AgentEditResult.NOT_EDITING
            AgentEditOutcome.DRAFT_OFFER_OPEN -> AgentEditResult.DRAFT_OFFER_OPEN
        }

    override fun runGate(mode: McpRunMode): McpRunGate {
        val ctx = menuContext()
        val view = session.view.value
        return when {
            !ctx.fileOpen || view.state !is EditorState.Editing -> McpRunGate.NO_FILE
            !isScript() -> McpRunGate.NOT_A_SCRIPT
            mode == McpRunMode.UNSAVED && view.draftOffer != null -> McpRunGate.DRAFT_OFFER_OPEN
            mode == McpRunMode.SAVED ->
                if (stateOf(ViewAction.RUN, ctx).enabled) McpRunGate.ALLOWED else McpRunGate.UNAVAILABLE
            stateOf(ViewAction.RUN_UNSAVED, ctx).enabled -> McpRunGate.ALLOWED
            else -> McpRunGate.UNAVAILABLE
        }
    }

    override fun runIsSandboxed(): Boolean = sandboxed()

    override fun autoPreviewOn(): Boolean = autoPreview()

    override fun startRun(mode: McpRunMode): Long {
        val before = generation()
        runStarter(mode)
        return before
    }

    override fun awaitRun(
        afterGeneration: Long,
        timeoutMs: Long,
    ): McpRunOutcome? = runAwaiter(afterGeneration, timeoutMs)

    override fun scene(): McpActionScene? = sceneNow()

    override fun selection(): SelectionState = selectionGet()

    override fun setSelection(next: SelectionState) = selectionSet(next)

    override fun visibility(): VisibilityState = visibilityGet()

    override fun setVisibility(next: VisibilityState) = visibilitySet(next)

    override fun showInScriptTarget(part: String): McpShowTarget {
        val view = session.view.value
        val editing = view.state as? EditorState.Editing ?: return McpShowTarget.NoFile
        if (!isScript()) return McpShowTarget.NotAScript
        val table = partsNow() ?: return McpShowTarget.NotFound
        val index = table.indexOfName(part)
        if (index < 0) return McpShowTarget.NotFound
        val shown = displaySourceText()
        if (shown == null || shown != editing.text) return McpShowTarget.BufferNotRun
        val info = table.parts.getOrNull(index)?.takeIf { it.name == part }
        return McpShowTarget.Ready(
            text = editing.text,
            editorRevision = view.revision,
            sourceLine = info?.sourceLine,
            usable = ShowInScriptInput.positionUsable(info, shown, editing.text),
            sameCount = table.countOfName(part),
        )
    }

    override fun selectInEditor(
        start: Int,
        end: Int,
        editorRevision: Int,
    ): Boolean {
        val text = session.view.value.text
        if (!session.requestSelection(start, end, editorRevision)) return false
        jumpTo(CaretMapper.lineColumn(text, start).first)
        return true
    }

    override fun agentNotice(kind: AgentNoticeKind) = notice(kind)
}
