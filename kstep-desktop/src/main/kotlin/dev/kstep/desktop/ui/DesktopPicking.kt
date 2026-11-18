package dev.kstep.desktop.ui

import androidx.compose.runtime.MutableState
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.HoverThrottle
import dev.kstep.desktop.pick.PickCache
import dev.kstep.desktop.pick.PickIndex
import dev.kstep.desktop.pick.SelectionEvent
import dev.kstep.desktop.pick.SelectionMachine
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.rayFor
import dev.kstep.render.mesh.ScreenFrame
import dev.kstep.viewer.ui.CanvasPicking

/**
 * Connects the canvas pointer callbacks to the [PickIndex] and the [SelectionMachine] (ADR-0053). Picking runs
 * synchronously on the UI thread: the per-part boxes and the triangle limit keep one pick cheap, hover picks are
 * throttled to one per 33 ms, and the last pick is cached for an unchanged pixel and screen mapping.
 */
internal class DesktopPicking(
    private val index: PickIndex,
    private val table: PartTable,
    private val sceneGeneration: Long,
    private val selection: MutableState<SelectionState>,
    clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
) : CanvasPicking {
    private val throttle = HoverThrottle(clock)
    private val cache = PickCache()
    private var lastFrame: ScreenFrame? = null

    private fun dispatch(event: SelectionEvent) {
        selection.value = SelectionMachine.reduce(selection.value, event)
    }

    private fun partAt(
        x: Float,
        y: Float,
        frame: ScreenFrame,
    ): Int =
        cache.lookup(x, y, frame, sceneGeneration) {
            index.pick(rayFor(frame, x.toDouble(), y.toDouble(), index.sceneRadius)) ?: -1
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
    ) {
        val part = if (frame == null) -1 else partAt(x, y, frame)
        if (part >= 0) {
            val name = table.parts[part].name
            val occurrence = table.occurrenceOf(part)
            dispatch(SelectionEvent.ClickAt(part, name, occurrence))
        } else {
            dispatch(SelectionEvent.ClickAt(null, null))
        }
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
        if (selection.value.selected == null && selection.value.selectedGroup == null) return false
        dispatch(SelectionEvent.Escape)
        return true
    }

    override fun isOverPart(): Boolean = selection.value.hovered != null

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
