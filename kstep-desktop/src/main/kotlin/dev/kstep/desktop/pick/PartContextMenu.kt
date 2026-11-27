package dev.kstep.desktop.pick

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.model.PartTable

/** What a context menu entry does (ADR-0092). */
enum class MenuAction { ShowInScript, Hide, Isolate, ShowAll, CopyName, SelectChildren }

/** One entry; a disabled entry still shows (and can be focused) so its [reason] can be read. */
data class MenuEntry(
    val action: MenuAction,
    val label: String,
    val enabled: Boolean,
    val reason: String? = null,
)

/** The menu as the UI draws it: a [header] (`null`: none), the [entries] and the index of the [focused] one. */
data class MenuModel(
    val header: String?,
    val entries: List<MenuEntry>,
    val focused: Int = 0,
) {
    val focusedEntry: MenuEntry? get() = entries.getOrNull(focused)
}

enum class MenuKey { Up, Down, Home, End, Enter, Escape }

sealed interface MenuStep {
    data class Moved(
        val model: MenuModel,
    ) : MenuStep

    data class Activate(
        val action: MenuAction,
    ) : MenuStep

    data object Close : MenuStep

    data object NoOp : MenuStep
}

/** Pure construction and keyboard navigation of the part context menu (ADR-0092). */
object PartContextMenu {
    const val HEADER_CLIP = 40
    const val REASON_ALREADY_HIDDEN = "Already hidden"
    const val REASON_ALREADY_ISOLATED = "Already isolated"
    const val REASON_NOTHING_TO_SHOW = "Nothing is hidden or transparent"
    const val REASON_NOT_A_GROUP = "Select an assembly group"

    fun reasonSeveralParts(count: Int) = "Show in Script needs a single part ($count selected)"

    fun reasonGroupTooBig(count: Int) = "Group has $count parts, limit is $MAX_SELECTION"

    /**
     * The menu for the current selection. [overPart] says whether the pointer was on a part or tree row (the selection
     * may still be non-empty after a click on empty space). Empty space offers "Show All" only, and only when it
     * would change something; otherwise there is no menu (`null`). [showInScriptReason] is why Show in Script is not
     * possible for a single target (`null`: it is possible).
     */
    fun build(
        selection: SelectionState,
        table: PartTable,
        tree: AssemblyTree?,
        visibility: VisibilityState,
        scene: VisibilityScene,
        showInScriptReason: String?,
        overPart: Boolean,
    ): MenuModel? {
        val showAll =
            if (VisibilityMachine.anyNotVisible(visibility, scene)) {
                MenuEntry(MenuAction.ShowAll, "Show All", true)
            } else {
                MenuEntry(MenuAction.ShowAll, "Show All", false, REASON_NOTHING_TO_SHOW)
            }
        val node = selection.selectedGroup?.let { tree?.nodeOfKey(it) } ?: -1
        val group = node >= 0 && tree != null && tree.isGroup(node)
        val indices = SelectionMachine.partIndices(selection, tree, table)
        if (!overPart || indices.isEmpty()) {
            return if (showAll.enabled) MenuModel(null, listOf(showAll)) else null
        }
        val count = selection.parts.size
        val header =
            when {
                group -> DisplayName.escaped(tree!!.name(node), HEADER_CLIP)
                count == 1 -> DisplayName.escaped(selection.selected!!, HEADER_CLIP)
                else -> "$count parts"
            }
        val single = group || count == 1
        val showInScript =
            when {
                !single -> MenuEntry(MenuAction.ShowInScript, "Show in Script", false, reasonSeveralParts(count))
                showInScriptReason != null ->
                    MenuEntry(MenuAction.ShowInScript, "Show in Script", false, showInScriptReason)
                else -> MenuEntry(MenuAction.ShowInScript, "Show in Script", true)
            }
        val hide =
            if (VisibilityMachine.hideParts(visibility, scene, indices) === visibility) {
                MenuEntry(MenuAction.Hide, "Hide", false, REASON_ALREADY_HIDDEN)
            } else {
                MenuEntry(MenuAction.Hide, "Hide", true)
            }
        val isolateLabel = "Isolate (others transparent)"
        val isolate =
            if (VisibilityMachine.isolateParts(visibility, scene, indices) === visibility) {
                MenuEntry(MenuAction.Isolate, isolateLabel, false, REASON_ALREADY_ISOLATED)
            } else {
                MenuEntry(MenuAction.Isolate, isolateLabel, true)
            }
        val copy = MenuEntry(MenuAction.CopyName, if (single) "Copy Name" else "Copy Names", true)
        val children =
            when {
                !group -> MenuEntry(MenuAction.SelectChildren, "Select Children", false, REASON_NOT_A_GROUP)
                tree!!.leafCount(node) > MAX_SELECTION ->
                    MenuEntry(
                        MenuAction.SelectChildren,
                        "Select Children",
                        false,
                        reasonGroupTooBig(tree.leafCount(node)),
                    )
                else -> MenuEntry(MenuAction.SelectChildren, "Select Children", true)
            }
        val entries = listOf(showInScript, hide, isolate, showAll, copy, children)
        return MenuModel(header, entries, entries.indexOfFirst { it.enabled }.coerceAtLeast(0))
    }

    /** One key press in the open menu. Up and Down wrap around and also land on disabled entries. */
    fun navigate(
        model: MenuModel,
        key: MenuKey,
    ): MenuStep {
        val n = model.entries.size
        if (n == 0) return if (key == MenuKey.Escape) MenuStep.Close else MenuStep.NoOp
        return when (key) {
            MenuKey.Down -> MenuStep.Moved(model.copy(focused = (model.focused + 1) % n))
            MenuKey.Up -> MenuStep.Moved(model.copy(focused = (model.focused - 1 + n) % n))
            MenuKey.Home -> MenuStep.Moved(model.copy(focused = 0))
            MenuKey.End -> MenuStep.Moved(model.copy(focused = n - 1))
            MenuKey.Enter -> {
                val entry = model.focusedEntry
                if (entry != null && entry.enabled) MenuStep.Activate(entry.action) else MenuStep.NoOp
            }
            MenuKey.Escape -> MenuStep.Close
        }
    }
}
