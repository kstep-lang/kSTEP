package dev.kstep.desktop.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.ClickIntent
import dev.kstep.desktop.pick.ClickIntents
import dev.kstep.desktop.pick.HoverThrottle
import dev.kstep.desktop.pick.PickCache
import dev.kstep.desktop.pick.PickIndex
import dev.kstep.desktop.pick.SelectionEvent
import dev.kstep.desktop.pick.SelectionMachine
import dev.kstep.desktop.pick.SelectionNotice
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.rayFor
import dev.kstep.render.mesh.ScreenFrame
import dev.kstep.viewer.ui.CanvasPicking
import dev.kstep.viewer.ui.PickModifiers

/**
 * Connects the canvas pointer callbacks to the [PickIndex] and the [SelectionMachine] (ADR-0053). Picking runs
 * synchronously on the UI thread: the per-part boxes and the triangle limit keep one pick cheap, hover picks are
 * throttled to one per 33 ms, and the last pick is cached for an unchanged pixel and screen mapping. Modifier clicks
 * and the secondary button follow the platform rule of [ClickIntents] (ADR-0092).
 */
internal class DesktopPicking(
    private val index: PickIndex,
    private val table: PartTable,
    private val sceneGeneration: Long,
    private val selection: MutableState<SelectionState>,
    private val visibility: State<VisibilityView?>? = null,
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val tree: AssemblyTree? = null,
    private val os: MenuOs = MenuOs.current(),
    private val menu: MutableState<MenuRequest?>? = null,
    private val onNotice: (SelectionNotice) -> Unit = {},
) : CanvasPicking {
    private val throttle = HoverThrottle(clock)
    private val cache = PickCache()
    private var lastFrame: ScreenFrame? = null
    private var lastView: VisibilityView? = null

    private fun dispatch(event: SelectionEvent) {
        val (next, notice) = SelectionMachine.reduceWithNotice(selection.value, event)
        selection.value = next
        notice?.let(onNotice)
    }

    private fun partAt(
        x: Float,
        y: Float,
        frame: ScreenFrame,
    ): Int {
        // the cache key knows pixel, mapping and scene, not the visibility: forget it when that changed
        val view = visibility?.value
        if (view !== lastView) {
            cache.invalidate()
            lastView = view
        }
        return cache.lookup(x, y, frame, sceneGeneration) {
            index.pick(rayFor(frame, x.toDouble(), y.toDouble(), index.sceneRadius), view?.arrays?.partMode) ?: -1
        }
    }

    /** Releases a pending hover point if its interval has passed; called from a ticker so the last point is not lost. */
    fun tick() {
        val frame = lastFrame ?: return
        val point = throttle.due() ?: return
        val part = partAt(point.first, point.second, frame)
        dispatch(SelectionEvent.HoverAt(if (part >= 0) part else null))
    }

    override fun onHover(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) {
        if (frame == null) return
        lastFrame = frame
        throttle.offer(x, y)
        tick()
    }

    override fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) = onClick(x, y, frame, PickModifiers())

    override fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
        modifiers: PickModifiers,
    ) {
        val part = if (frame == null) -1 else partAt(x, y, frame)
        when (ClickIntents.of(false, modifiers.meta, modifiers.ctrl, os)) {
            ClickIntent.Menu -> openMenu(x, y, part)
            ClickIntent.Toggle -> if (part >= 0) toggle(part)
            ClickIntent.Select ->
                if (part >= 0) {
                    dispatch(SelectionEvent.ClickAt(part, table.parts[part].name, table.occurrenceOf(part)))
                } else {
                    dispatch(SelectionEvent.ClickAt(null, null))
                }
        }
    }

    override fun onSecondaryClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
        modifiers: PickModifiers,
    ) = openMenu(x, y, if (frame == null) -1 else partAt(x, y, frame))

    override fun onMenuKey(): Boolean {
        val menu = menu ?: return false
        val s = selection.value
        menu.value =
            MenuRequest(MenuOrigin.Viewer, overPart = s.selected != null || s.selectedGroup != null, centered = true)
        return true
    }

    /** Opens the menu at the pointer; a part under it that is not selected replaces the selection first. */
    private fun openMenu(
        x: Float,
        y: Float,
        part: Int,
    ) {
        val menu = menu ?: return
        if (part >= 0) {
            dispatch(SelectionEvent.ContextAt(part, table.parts[part].name, table.occurrenceOf(part)))
        }
        menu.value = MenuRequest(MenuOrigin.Viewer, x, y, overPart = part >= 0)
    }

    private fun toggle(part: Int) {
        val node = selection.value.selectedGroup?.let { tree?.nodeOfKey(it) } ?: -1
        val resolved = node >= 0 && tree != null
        dispatch(
            SelectionEvent.TogglePart(
                part,
                table.parts[part].name,
                table.occurrenceOf(part),
                groupRefs = if (resolved) SelectionMachine.groupRefs(tree!!, node, table) else emptyList(),
                groupTotal = if (resolved) tree!!.leafCount(node) else 0,
            ),
        )
    }

    override fun onDragStart() {
        throttle.clear()
        dispatch(SelectionEvent.DragStart)
    }

    override fun onPointerExit() {
        throttle.clear()
        dispatch(SelectionEvent.PointerExit)
    }

    override fun onEscape(): Boolean {
        if (selection.value.parts.isEmpty() && selection.value.selectedGroup == null) return false
        dispatch(SelectionEvent.Escape)
        return true
    }

    override fun isOverPart(): Boolean = selection.value.hovered != null

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
