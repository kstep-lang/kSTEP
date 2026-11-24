package dev.kstep.desktop.menu

/** The icon-only toolbar buttons (ADR-0079). A pure model: no Compose, no AWT, no ui import. */
enum class ToolbarButtonId(
    val icon: MenuIconId,
    val toolbarName: String,
) {
    OPEN(MenuIconId.OPEN, "Open…"),
    EXPORT(MenuIconId.EXPORT, "Export…"),
    HOME(MenuIconId.HOME, "Home"),
    TREE(MenuIconId.TREE, "Tree"),
    AI(MenuIconId.AI_PANEL, "AI Panel"),
    SHOW_ALL(MenuIconId.SHOW_ALL, "Show All"),
    ISOLATE(MenuIconId.ISOLATE, "Isolate"),
    HIDE(MenuIconId.HIDE, "Hide"),
}

/** What a toolbar button shows: [selected] is null for a plain button, on/off for a toggle. */
data class ToolbarButtonState(
    val id: ToolbarButtonId,
    val enabled: Boolean,
    val reason: String?,
    val selected: Boolean?,
)

object ToolbarModel {
    const val PERCENT_PER_STEP = 5
}

private const val TOOLTIP_SEPARATOR = " — "

/** The tooltip: the name, and for a disabled button the reason after it. */
fun toolbarTooltip(state: ToolbarButtonState): String =
    if (!state.enabled && state.reason != null) {
        state.id.toolbarName + TOOLTIP_SEPARATOR + state.reason
    } else {
        state.id.toolbarName
    }

/** The tooltip of the opacity control: the percentage, or why there is nothing to set. */
fun opacityTooltip(
    alphaStep: Int,
    transparentCount: Int,
): String =
    if (transparentCount == 0) {
        "Opacity" + TOOLTIP_SEPARATOR + MenuReasons.NO_TRANSPARENT_PARTS
    } else {
        "Opacity of transparent parts: ${alphaStep * ToolbarModel.PERCENT_PER_STEP} %"
    }

/** The state description of a toggle for assistive technology. */
fun stateDescription(selected: Boolean): String = if (selected) "On" else "Off"

private fun toolbarState(
    id: ToolbarButtonId,
    state: ActionState,
    selected: Boolean? = null,
) = ToolbarButtonState(id, state.enabled, state.reason, selected)

fun openToolbarState(ctx: MenuContext): ToolbarButtonState =
    toolbarState(ToolbarButtonId.OPEN, stateOf(FileAction.OPEN, ctx))

fun exportToolbarState(ctx: MenuContext): ToolbarButtonState =
    toolbarState(ToolbarButtonId.EXPORT, stateOf(FileAction.EXPORT, ctx))

fun homeToolbarState(ctx: MenuContext): ToolbarButtonState =
    toolbarState(ToolbarButtonId.HOME, stateOf(ViewAction.HOME, ctx))

fun treeToolbarState(on: Boolean): ToolbarButtonState = ToolbarButtonState(ToolbarButtonId.TREE, true, null, on)

fun aiToolbarState(
    ctx: MenuContext,
    on: Boolean,
): ToolbarButtonState = toolbarState(ToolbarButtonId.AI, stateOf(ViewAction.AI_PANEL, ctx), on)

fun showAllToolbarState(ctx: MenuContext): ToolbarButtonState =
    toolbarState(ToolbarButtonId.SHOW_ALL, stateOf(ViewAction.SHOW_ALL, ctx))

fun isolateToolbarState(ctx: MenuContext): ToolbarButtonState =
    toolbarState(ToolbarButtonId.ISOLATE, stateOf(ViewAction.ISOLATE, ctx))

fun hideToolbarState(ctx: MenuContext): ToolbarButtonState =
    toolbarState(ToolbarButtonId.HIDE, stateOf(ViewAction.HIDE, ctx))
