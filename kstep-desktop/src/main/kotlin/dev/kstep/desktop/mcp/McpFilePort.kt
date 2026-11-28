package dev.kstep.desktop.mcp

/**
 * What the buffer looked like when a file action was asked for. An action only runs if this is still true afterwards
 * (ADR-0098 D4). [fileId] is opaque (a hash of the file's place), never shown and never sent to an agent.
 */
class McpFileStamp(
    val textRevision: String,
    val dirty: Boolean,
    val fileId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is McpFileStamp && other.textRevision == textRevision && other.dirty == dirty && other.fileId == fileId

    override fun hashCode(): Int = textRevision.hashCode() * 31 + fileId.hashCode()

    override fun toString(): String = "McpFileStamp(dirty=$dirty)"
}

/** The window as the file tools need it. [fileName] and [folder] are display texts for the confirmation only. */
class McpFileTarget(
    val kind: EditorKind,
    val stamp: McpFileStamp?,
    val fileName: String?,
    val folder: String?,
    val draftOfferOpen: Boolean,
    val workspaceOpen: Boolean,
)

/** One entry of the Recent list that is a script: its place in the list and its display texts. */
class McpRecentScript(
    val index: Int,
    val name: String,
    val folder: String,
)

/** The size of a change against the file on disk. */
class McpLineDelta(
    val added: Int,
    val removed: Int,
)

sealed interface McpSaveResult {
    class Saved(
        val revision: String,
    ) : McpSaveResult

    data object Stale : McpSaveResult

    /** A conflict with the disk or a write error. */
    data object Failed : McpSaveResult

    data object StillSaving : McpSaveResult
}

sealed interface McpOpenResult {
    class Opened(
        val name: String,
    ) : McpOpenResult

    data object Stale : McpOpenResult

    data object Unsaved : McpOpenResult

    data object Missing : McpOpenResult

    data object WorkspaceOpen : McpOpenResult
}

/** What an export would do, worked out before the user is asked. */
sealed interface McpExportPlan {
    /** [fileName] is what the agent may learn; [folderDisplay] only goes to the dialog; [opaque] stays in the window. */
    class Ready(
        val fileName: String,
        val folderDisplay: String,
        val opaque: Any,
    ) : McpExportPlan

    /** No remembered folder: the user has to export once by hand. */
    data object NoFolder : McpExportPlan

    /** All free names are taken. */
    data object Exists : McpExportPlan

    /** The buffer is not the one in the stamp any more. */
    data object Stale : McpExportPlan
}

sealed interface McpExportStart {
    class Started(
        val generation: Long,
        val fileName: String,
    ) : McpExportStart

    data object Stale : McpExportStart

    data object UiBusy : McpExportStart

    data object Unsaved : McpExportStart

    data object NoFile : McpExportStart

    data object NotAScript : McpExportStart
}

sealed interface McpExportResult {
    class Done(
        val fileName: String,
    ) : McpExportResult

    class Failed(
        val code: String,
    ) : McpExportResult

    /** The target appeared meanwhile; nothing was replaced. */
    data object Exists : McpExportResult

    /** Still running after the wait. */
    class Running(
        val fileName: String,
    ) : McpExportResult
}

/**
 * What the file tools of ADR-0098 may ask of the application. No method takes a path, a file or free text from an
 * agent; the only string is the name of a Recent entry that the tool read from this port itself, so that the port can
 * make sure it opens the file that was shown. A test compares the method set with this list. Methods marked
 * "UI thread" run on it; the others never do.
 */
interface McpFilePort {
    /** UI thread. */
    fun target(): McpFileTarget

    /** UI thread. Scripts only, at most ten, newest first. */
    fun recentScripts(): List<McpRecentScript>

    /** Not the UI thread; reads the file on disk. `null` when the change is too large to count. */
    fun lineDelta(stamp: McpFileStamp): McpLineDelta?

    /** Not the UI thread: is the recent entry still a plain file (no link)? */
    fun recentStillValid(index: Int): Boolean

    /** UI thread: checks the stamp, then starts the save. `false` when the buffer is not the one in [stamp]. */
    fun beginSave(stamp: McpFileStamp): Boolean

    /** Not the UI thread. */
    fun awaitSave(
        stamp: McpFileStamp,
        timeoutMs: Long,
    ): McpSaveResult

    /** UI thread. [expectedName] is the name the tool read from [recentScripts]; it must still match. */
    fun openRecent(
        index: Int,
        expectedName: String,
        stamp: McpFileStamp?,
    ): McpOpenResult

    /** Not the UI thread: the folder, the name and the collision handling of an export. */
    fun planExport(
        format: McpExportFormat,
        name: McpSafeFileName?,
        stamp: McpFileStamp,
    ): McpExportPlan

    /** UI thread. */
    fun startExport(
        plan: McpExportPlan.Ready,
        format: McpExportFormat,
        stamp: McpFileStamp,
    ): McpExportStart

    /** Not the UI thread. */
    fun awaitExport(
        start: McpExportStart.Started,
        timeoutMs: Long,
    ): McpExportResult
}
