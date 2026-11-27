package dev.kstep.desktop.mcp

import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.VisibilityScene
import dev.kstep.desktop.pick.VisibilityState

/** The editor as the UI thread sees it right now (never a snapshot). */
class McpEditorCapture(
    val kind: EditorKind,
    val text: String?,
    val draftOfferOpen: Boolean,
    val dirty: Boolean = false,
) {
    override fun toString(): String = "McpEditorCapture(kind=$kind)"
}

/** What an agent edit did (ADR-0097). */
enum class AgentEditResult { APPLIED, UNCHANGED, STALE, NOT_EDITING, DRAFT_OFFER_OPEN }

/** Which text a run starts on: the saved file or the editor buffer. */
enum class McpRunMode { SAVED, UNSAVED }

/** Whether the run entry the user would use is available. */
enum class McpRunGate { ALLOWED, NO_FILE, NOT_A_SCRIPT, DRAFT_OFFER_OPEN, UNAVAILABLE }

/** How a run ended. */
sealed interface McpRunOutcome {
    data object Finished : McpRunOutcome

    class Failed(
        val errorCount: Int,
    ) : McpRunOutcome
}

/** A small hint the window shows after an agent acted. */
enum class AgentNoticeKind { EDITED, RUN_STARTED }

/** The model the window shows, as needed to select and to change the visibility. */
class McpActionScene(
    val parts: PartTable,
    val tree: AssemblyTree?,
    val visibilityScene: VisibilityScene,
)

/** Where "Show in Script" would go for a part. */
sealed interface McpShowTarget {
    data object BufferNotRun : McpShowTarget

    data object NotFound : McpShowTarget

    data object NotAScript : McpShowTarget

    data object NoFile : McpShowTarget

    class Ready(
        val text: String,
        val editorRevision: Int,
        val sourceLine: Int?,
        val usable: Boolean,
        val sameCount: Int,
    ) : McpShowTarget
}

/**
 * What the MCP action tools may ask of the application (ADR-0097). Every method except [awaitRun] runs on the UI thread.
 * There is deliberately nothing to write a file, to leave the app or to touch the system clipboard: an agent can only
 * change the editor text of the open buffer, start a run the user could start, and change selection and visibility.
 * A test checks the method names.
 */
interface McpActionPort {
    fun editor(): McpEditorCapture

    fun applyAgentEdit(
        expected: String,
        newText: String,
    ): AgentEditResult

    fun runGate(mode: McpRunMode): McpRunGate

    /**
     * Whether a run of the user flow would be sandboxed on this system (ADR-0097 D12). Without a sandbox a script has
     * the full rights of the user, so agents may not cause a run then.
     */
    fun runIsSandboxed(): Boolean

    /** Whether an edit of the editor text starts a run on its own (Preview Unsaved Changes). */
    fun autoPreviewOn(): Boolean

    /** Starts a run the way the menu does; returns the generation seen before the start. */
    fun startRun(mode: McpRunMode): Long

    /** Not on the UI thread. Waits up to [timeoutMs] for a run newer than [afterGeneration]; `null` on timeout. */
    fun awaitRun(
        afterGeneration: Long,
        timeoutMs: Long,
    ): McpRunOutcome?

    fun scene(): McpActionScene?

    fun selection(): SelectionState

    fun setSelection(next: SelectionState)

    fun visibility(): VisibilityState

    fun setVisibility(next: VisibilityState)

    fun showInScriptTarget(part: String): McpShowTarget

    /** Selects the text range without taking the focus; `false` when the buffer changed since [editorRevision]. */
    fun selectInEditor(
        start: Int,
        end: Int,
        editorRevision: Int,
    ): Boolean

    fun agentNotice(kind: AgentNoticeKind)
}
