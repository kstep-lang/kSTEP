package dev.kstep.desktop.mcp

import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.VisibilityScene
import dev.kstep.desktop.pick.VisibilityState
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.Severity

/**
 * The problems of the last run as the error bar shows them (ADR-0096). [errors] are the run's own positions, not
 * moved anchors. [bufferText] is the text the run was started on when that was an unsaved buffer.
 */
class McpRunReport(
    val errors: List<DesktopError>,
    val severity: Severity,
    val origin: PreviewKind?,
    val bufferText: String?,
)

/**
 * The model the window shows. Holds only references to immutable objects, so publishing it costs nothing; the work
 * (JSON, masks) is done by the tool call in the HTTP thread (ADR-0096 D2).
 */
class McpScene(
    val parts: PartTable,
    val tree: AssemblyTree?,
    val generation: Long,
) {
    /** The stable keys of the parts; built on first use, once per model. */
    val visibilityScene: VisibilityScene by lazy { VisibilityScene.of(parts, tree) }
}

/** The two settings of the View menu an agent can read. */
data class McpViewSettings(
    val softWrap: Boolean = false,
    val previewUnsaved: Boolean = false,
)

/** Everything of the view that changes a snapshot: selection (no hover), visibility, settings. */
data class McpViewInput(
    val selection: SelectionState?,
    val visibility: VisibilityState,
    val settings: McpViewSettings,
) {
    companion object {
        val EMPTY = McpViewInput(null, VisibilityState(), McpViewSettings())

        /** Hover, tree hover and the range anchor are dropped, so moving the pointer changes nothing. */
        fun of(
            selection: SelectionState,
            visibility: VisibilityState,
            softWrap: Boolean,
            previewUnsaved: Boolean,
        ): McpViewInput =
            McpViewInput(
                selection =
                    if (selection.selected == null && selection.selectedGroup == null) {
                        null
                    } else {
                        selection.copy(hovered = null, treeHover = null, anchor = null)
                    },
                visibility = visibility,
                settings = McpViewSettings(softWrap, previewUnsaved),
            )
    }
}
