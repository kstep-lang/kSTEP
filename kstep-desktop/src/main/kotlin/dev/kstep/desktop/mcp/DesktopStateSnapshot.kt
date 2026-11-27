package dev.kstep.desktop.mcp

import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.VisibilityState
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

enum class EditorKind { SCRIPT, NOTE, NONE }

enum class RunKind { IDLE, RUNNING, OK, ERROR, UNAVAILABLE }

enum class PreviewKind { SAVED_FILE, UNSAVED_BUFFER }

/**
 * An immutable picture of what the window shows, published by the UI and read by the MCP tools (ADR-0095). The tools
 * never touch the UI thread: they only read the latest snapshot. [editorText] is a reference to the editor string, not
 * a copy. `toString` never prints the text or the path.
 */
class DesktopStateSnapshot(
    val filePath: Path?,
    val editorKind: EditorKind,
    val editorText: String?,
    val dirty: Boolean,
    val run: RunKind,
    val previewOrigin: PreviewKind?,
    val parts: Int?,
    val triangles: Int?,
    val draftOfferOpen: Boolean,
    val knowledgeWorkspace: Boolean,
    val waitingHint: String?,
    /** Changes whenever what the window shows changes (ADR-0096 D2); a hover does not count. 0 before the first publish. */
    val revision: Long = 0L,
    /** The errors or warnings of the last run, or `null`. */
    val runReport: McpRunReport? = null,
    /** The shown model (parts, tree), or `null` without one. */
    val scene: McpScene? = null,
    /** The selection without hover, or `null` when nothing is selected. */
    val selection: SelectionState? = null,
    val visibility: VisibilityState = VisibilityState(),
    val viewSettings: McpViewSettings = McpViewSettings(),
) {
    override fun toString(): String = "DesktopStateSnapshot(kind=$editorKind, run=$run, dirty=$dirty)"

    companion object {
        val EMPTY =
            DesktopStateSnapshot(
                filePath = null,
                editorKind = EditorKind.NONE,
                editorText = null,
                dirty = false,
                run = RunKind.IDLE,
                previewOrigin = null,
                parts = null,
                triangles = null,
                draftOfferOpen = false,
                knowledgeWorkspace = false,
                waitingHint = null,
            )
    }
}

/** What the MCP tools may see of the application: the latest snapshot and the version. */
interface McpHost {
    val snapshot: DesktopStateSnapshot
    val appVersion: String
}

/** The host the UI publishes into. */
class SnapshotHost(
    override val appVersion: String,
) : McpHost {
    private val ref = AtomicReference(DesktopStateSnapshot.EMPTY)

    override val snapshot: DesktopStateSnapshot get() = ref.get()

    fun publish(snapshot: DesktopStateSnapshot) = ref.set(snapshot)
}
