package dev.kstep.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.ColoredMesh
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.mesh.Camera
import dev.kstep.render.mesh.MeshProjection
import dev.kstep.render.mesh.ScreenFrame
import dev.kstep.viewer.camera.CameraInteraction
import dev.kstep.viewer.camera.ViewerCameraState
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.VertexMode
import java.awt.Cursor
import org.jetbrains.skia.Color as SkiaColor
import org.jetbrains.skia.Paint as SkiaPaint

/** The controls hint shown over the viewer at the untouched HOME pose (ADR-0017); also measured by the desktop overlay layout (ADR-0094). */
const val VIEWER_CONTROLS_HINT = "Drag to orbit · Shift-drag to pan · Scroll to zoom · Double-click or R to reset"

/** The controls hint box including its 12 dp outer margin; the caller aligns it (BoxScope `Modifier.align`). */
@Composable
fun ViewerControlsHint(modifier: Modifier = Modifier) {
    Text(
        VIEWER_CONTROLS_HINT,
        modifier =
            modifier
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Renders [mesh] as an interactive, orbitable projection, painter's-algorithm-ordered and
 * flat-shaded. Uses the exact same [MeshProjection.project] call as `kstep-render`'s headless
 * rasterizer/SVG writer (moved there from this module in kSTEP's headless-preview-rendering
 * wave, see docs/adr/ADR-0011-headless-preview-rendering.adoc) -- no second, Compose-only
 * projection path to drift out of sync with it.
 *
 * Camera interaction (drag-to-orbit, scroll-to-zoom, double-click/`R`-to-reset -- see
 * docs/adr/ADR-0012-viewer-camera-interaction.adoc) was added in kSTEP's viewer-camera-interaction
 * wave. [state] defaults to an internally-`remember`ed [ViewerCameraState.HOME] so every existing
 * caller (`Main.kt`'s `ShapeCanvas(mesh, Modifier.fillMaxSize())`) keeps working unchanged; a
 * future caller (e.g. a V-2d toolbar) that needs to read or drive the camera from outside can
 * hoist its own [MutableState] and pass it in instead.
 *
 * @param state The full camera pose (orbit angles + zoom + pan). Deliberately a raw [MutableState]
 *   rather than this composable owning a private one unconditionally -- see the class doc above.
 * @param fitReference Optional mesh the auto-fit scale is computed from instead of [mesh] (ADR-0051): a live
 *   preview passes the first good mesh of a file, so the camera does not jump when the shape changes slightly.
 *   `null` (the default) keeps the pre-existing behaviour exactly: the scale is fitted to [mesh] itself.
 */
@Composable
fun ShapeCanvas(
    mesh: TriangleMesh,
    modifier: Modifier = Modifier,
    state: MutableState<ViewerCameraState> = remember { mutableStateOf(ViewerCameraState.HOME) },
    fitReference: TriangleMesh? = null,
) = ShapeCanvasInternal(mesh, coloredMesh = null, modifier, state, fitReference, null, null, null)

/**
 * Overload of [ShapeCanvas] for a [ColoredMesh] (see `dev.kstep.geometry.MeshComposition.
 * mergeColored`), added in kSTEP's viewer-pan-and-material-colors wave (see
 * docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc) -- draws each triangle in its own
 * [ColoredMesh.colorAt] color instead of the plain-[TriangleMesh] overload's uniform grayscale.
 */
@Composable
fun ShapeCanvas(
    coloredMesh: ColoredMesh,
    modifier: Modifier = Modifier,
    state: MutableState<ViewerCameraState> = remember { mutableStateOf(ViewerCameraState.HOME) },
    fitReference: TriangleMesh? = null,
) = ShapeCanvasInternal(coloredMesh.mesh, coloredMesh, modifier, state, fitReference, null, null, null)

/**
 * Tint instructions for part highlighting (ADR-0053). [triangleToPart] maps a SOURCE mesh triangle to a part index;
 * [selected] and [hovered] are part indices (`-1` for none). Each lit colour channel moves towards [accentRgb]
 * (0..1) by [selectT] (selected part) or [hoverT] (hovered part): a tint, not a brightening, so a part that is
 * already light stays distinguishable. [selectedLast] and [hoveredLast] (inclusive, default: the same part) widen
 * either one to a range of part indices, e.g. a whole assembly group (ADR-0054).
 */
class CanvasHighlight(
    val triangleToPart: IntArray,
    val selected: Int,
    val hovered: Int,
    val accentRgb: DoubleArray,
    val selectT: Double = DEFAULT_SELECT_T,
    val hoverT: Double = DEFAULT_HOVER_T,
    val selectedLast: Int = selected,
    val hoveredLast: Int = hovered,
    /**
     * One flag per part for a selection that is not one range (a multi-selection, ADR-0092); checked before the
     * [selected] range. Built once per selection change by the host, read-only afterwards.
     */
    val selectedMask: BooleanArray? = null,
    /** Tint strength of a selected part while its outline is drawn (ADR-0093); [selectT] when there is no outline. */
    val outlineSelectT: Double = DEFAULT_OUTLINE_SELECT_T,
    /** Background colour (0..1 RGB) for the halo under the outline; `null` draws the outline without a halo. */
    val haloRgb: DoubleArray? = null,
) {
    private fun partSelected(part: Int): Boolean =
        part >= 0 &&
            ((selectedMask != null && part < selectedMask.size && selectedMask[part]) || part in selected..selectedLast)

    /** Whether the SOURCE triangle belongs to a selected part (hover does not count). */
    internal fun isSelected(sourceIndex: Int): Boolean {
        if (sourceIndex < 0 || sourceIndex >= triangleToPart.size) return false
        return partSelected(triangleToPart[sourceIndex])
    }

    /** How many source triangles belong to selected parts; counted once per instance (ADR-0093). */
    internal val selectedTriangleCount: Int by lazy {
        var n = 0
        for (p in triangleToPart) if (partSelected(p)) n++
        n
    }

    internal fun tintFor(
        sourceIndex: Int,
        selectFactor: Double = selectT,
    ): Double {
        if (sourceIndex < 0 || sourceIndex >= triangleToPart.size) return 0.0
        val part = triangleToPart[sourceIndex]
        return when {
            part < 0 -> 0.0
            selectedMask != null && part < selectedMask.size && selectedMask[part] -> selectFactor
            part in selected..selectedLast -> selectFactor
            part in hovered..hoveredLast -> hoverT
            else -> 0.0
        }
    }

    companion object {
        const val DEFAULT_SELECT_T: Double = 0.45
        const val DEFAULT_OUTLINE_SELECT_T: Double = 0.30
        const val DEFAULT_HOVER_T: Double = 0.20
    }
}

/**
 * Per-part visibility for [ShapeCanvas] (ADR-0055). [triangleToPart] maps a SOURCE mesh triangle to a part index,
 * [partMode] holds one code per part ([VISIBLE], [TRANSPARENT] or [HIDDEN]), [alpha] (0..1) is the opacity of the
 * transparent parts. A triangle whose index or part is out of range is drawn in full, so a stale table never hides
 * anything by accident. Treated as read-only after construction.
 */
class CanvasVisibility(
    val triangleToPart: IntArray,
    val partMode: ByteArray,
    val alpha: Float,
) {
    /** Whether any part is hidden; known once, so the draw loop can skip the counting pass otherwise. */
    val hasHidden: Boolean = partMode.any { it.toInt() == HIDDEN }

    internal val alphaByte: Int =
        (if (alpha.isNaN()) DEFAULT_ALPHA else alpha.coerceIn(0f, 1f)).let { Math.round(it * MAX_CHANNEL) }

    internal fun modeOf(sourceIndex: Int): Int {
        if (sourceIndex < 0 || sourceIndex >= triangleToPart.size) return VISIBLE
        val part = triangleToPart[sourceIndex]
        if (part < 0 || part >= partMode.size) return VISIBLE
        return when (val mode = partMode[part].toInt()) {
            TRANSPARENT, HIDDEN -> mode
            else -> VISIBLE
        }
    }

    companion object {
        const val VISIBLE = 0
        const val TRANSPARENT = 1
        const val HIDDEN = 2
        private const val DEFAULT_ALPHA = 0.25f
        private const val MAX_CHANNEL = 255f
    }
}

/** The modifier keys held when a click was pressed (ADR-0092); the host decides what they mean. */
data class PickModifiers(
    val meta: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
)

/**
 * Pointer callbacks for picking, implemented by the host. [frame] is the screen mapping the canvas drew with last
 * (`null` before the first frame); the host inverts pixels with it, so picking cannot drift from the drawing.
 */
interface CanvasPicking {
    /** Raw pointer move with no button pressed; throttling is the host's job. */
    fun onHover(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    )

    /** A primary-button press and release that stayed below the drag threshold. */
    fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    )

    /** [onClick] with the modifier keys held at the press; by default they are ignored. */
    fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
        modifiers: PickModifiers,
    ) = onClick(x, y, frame)

    /** A secondary-button press and release that stayed below the drag threshold (ADR-0092); ignored by default. */
    fun onSecondaryClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
        modifiers: PickModifiers,
    ) {}

    /** The Menu key or Shift+F10 while the canvas has the keyboard focus; `true` if it was consumed. */
    fun onMenuKey(): Boolean = false

    fun onDragStart()

    fun onPointerExit()

    /** Escape while the canvas has the keyboard focus; `true` if it was consumed. */
    fun onEscape(): Boolean

    /** Whether the pointer currently rests on a part (picks the cursor). */
    fun isOverPart(): Boolean
}

/** [ShapeCanvas] with part highlighting and picking (ADR-0053); the camera interaction is unchanged. */
@Composable
fun ShapeCanvas(
    coloredMesh: ColoredMesh,
    modifier: Modifier,
    state: MutableState<ViewerCameraState>,
    fitReference: TriangleMesh?,
    highlight: State<CanvasHighlight?>,
    picking: CanvasPicking?,
) = ShapeCanvasInternal(coloredMesh.mesh, coloredMesh, modifier, state, fitReference, highlight, picking, null)

/**
 * [ShapeCanvas] with part highlighting, picking and per-part visibility (ADR-0055). [visibility] is read in the draw
 * phase only, like [highlight]; `null` draws every part in full, exactly as the overload without it.
 */
@Composable
fun ShapeCanvas(
    coloredMesh: ColoredMesh,
    modifier: Modifier,
    state: MutableState<ViewerCameraState>,
    fitReference: TriangleMesh?,
    highlight: State<CanvasHighlight?>,
    picking: CanvasPicking?,
    visibility: State<CanvasVisibility?>?,
    focusRequester: FocusRequester? = null,
) = ShapeCanvasInternal(
    coloredMesh.mesh,
    coloredMesh,
    modifier,
    state,
    fitReference,
    highlight,
    picking,
    visibility,
    focusRequester,
)

private fun androidx.compose.ui.input.pointer.PointerButtons.anyPressed(): Boolean =
    isPrimaryPressed || isSecondaryPressed || isTertiaryPressed

/** Test hook (ADR-0068): counts compositions per scope ("canvas", "body", "cursor"). Null outside tests. */
internal var recompositionProbe: ((String) -> Unit)? = null

private val HAND_CURSOR_ICON = PointerIcon(Cursor(Cursor.HAND_CURSOR))
private val MOVE_CURSOR_ICON = PointerIcon(Cursor(Cursor.MOVE_CURSOR))

/**
 * A [Box] that sets the hover cursor from [picking]. Not inline on purpose: the hover state is read here, in this
 * scope, so a cursor change recomposes only this function while [content] (the same lambda instance) is skipped.
 */
@Composable
private fun CursorHost(
    picking: CanvasPicking?,
    modifier: Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    SideEffect { recompositionProbe?.invoke("cursor") }
    val icon =
        when {
            picking == null -> PointerIcon.Hand
            picking.isOverPart() -> HAND_CURSOR_ICON
            else -> MOVE_CURSOR_ICON
        }
    Box(modifier.pointerHoverIcon(icon), content = content)
}

private class FrameHolder {
    var frame: ScreenFrame? = null

    /** Outline hash tables, reused every frame; a plain field, never state (ADR-0093). */
    val outlineScratch = OutlineScratch()
}

private val DRAG_THRESHOLD = 4.dp
private val OUTLINE_WIDTH = 2.dp
private val OUTLINE_HALO_WIDTH = 4.dp

private fun outlinePaint(
    rgb: DoubleArray,
    width: Float,
): SkiaPaint =
    SkiaPaint().apply {
        isAntiAlias = true
        mode = PaintMode.STROKE
        strokeWidth = width
        strokeCap = PaintStrokeCap.ROUND
        color =
            SkiaColor.makeARGB(
                255,
                (rgb[0].coerceIn(0.0, 1.0) * 255.0).toInt(),
                (rgb[1].coerceIn(0.0, 1.0) * 255.0).toInt(),
                (rgb[2].coerceIn(0.0, 1.0) * 255.0).toInt(),
            )
    }

/**
 * The first press of ANY mouse button, consumed or not. The stock `awaitFirstDown` only reacts to the primary button, so
 * a secondary press (the context menu, ADR-0092, and the orbit by right-drag) would never start the gesture.
 */
private suspend fun AwaitPointerEventScope.awaitAnyButtonDown(): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.let { return it }
    }
}

@OptIn(ExperimentalComposeUiApi::class) // Modifier.onPointerEvent (scroll-to-zoom) is experimental
// in this Compose Multiplatform version -- no unstable behavior relied on beyond the API shape
// itself, and there is no stable scroll-event API in this version to use instead.
@Composable
private fun ShapeCanvasInternal(
    mesh: TriangleMesh,
    coloredMesh: ColoredMesh?,
    modifier: Modifier,
    state: MutableState<ViewerCameraState>,
    fitReference: TriangleMesh?,
    highlight: State<CanvasHighlight?>?,
    picking: CanvasPicking?,
    visibility: State<CanvasVisibility?>?,
    externalFocus: FocusRequester? = null,
) {
    val frameHolder = remember { FrameHolder() }
    val ownFocus = remember { FocusRequester() }
    val focusRequester = externalFocus ?: ownFocus
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // The auto-fit scale for the CURRENT (mesh, canvasSize) pair, computed once per resize --
    // NOT inside the Canvas draw lambda below. Deliberate: DrawScope's draw lambda is not a
    // @Composable context, so it cannot call `remember` itself, and writing a mutableStateOf
    // FROM INSIDE a draw phase (the naive alternative) risks the draw phase triggering its own
    // recomposition -- see docs/adr/ADR-0012-viewer-camera-interaction.adoc's design notes. This
    // `remember` sits in composition, keyed on `mesh` and `canvasSize` (itself only written by
    // `onSizeChanged` below, i.e. only on an actual resize) -- the draw lambda below only READS
    // `homeFitScale`, it never writes anything, so only an actual resize or mesh swap triggers a
    // recomposition of this value; a camera drag/scroll/zoom only invalidates the draw phase.
    val homeFitScale =
        remember(fitReference ?: mesh, canvasSize) {
            if (canvasSize.width <= 0 || canvasSize.height <= 0) {
                null
            } else {
                MeshProjection.fitScale(
                    fitReference ?: mesh,
                    canvasSize.width.toDouble(),
                    canvasSize.height.toDouble(),
                    Camera.ISOMETRIC,
                )
            }
        }

    // Auto-focus on first composition so scroll-to-zoom/R-to-reset work without the user having
    // to click the canvas first -- there is nothing else in this window to steal focus instead.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Only the HOME/not-HOME BOOLEAN is read where it drives composition below (the hint
    // `Text`'s presence), never raw `state.value` itself. `detectDragGestures`/`onScroll` write
    // `state.value` on every single pointer-move/scroll delta during an active gesture -- reading
    // `state.value` directly at a composition-scoped call site (as opposed to inside the `Canvas`
    // draw lambda, which is what design decision 4 in docs/adr/ADR-0012-viewer-camera-interaction.adoc
    // is actually about) would subscribe that composition scope to EVERY one of those writes, i.e.
    // recompose this `Box`'s whole content lambda -- rebuilding `Canvas`'s entire `Modifier` chain,
    // gesture detectors included -- on every drag-move event, not just when the camera actually
    // leaves or returns to HOME. `derivedStateOf` still re-evaluates its block on every one of
    // those writes, but only notifies readers of `showHint` when the computed boolean itself
    // flips, which is what actually keeps a camera-only change scoped to invalidating the draw
    // phase instead of the composable tree.
    val showHint by remember { derivedStateOf { state.value == ViewerCameraState.HOME } }

    SideEffect { recompositionProbe?.invoke("canvas") }
    // The cursor depends on the hover state; it is read inside CursorHost only (ADR-0068), so a hover change
    // recomposes that small scope and never this function.
    CursorHost(picking, modifier) {
        SideEffect { recompositionProbe?.invoke("body") }
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(picking) {
                    // ONE gesture handler for both orbit and pan -- not a second, competing
                    // pointerInput(Unit) block armed with its own detectDragGestures -- so exactly
                    // one of onDrag/onPan runs per drag, decided once at the initial press by
                    // whether it looks like a "pan" gesture (middle-button OR held Shift; see
                    // VIEWER_CONTROLS_HINT and docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc). Built from
                    // the same public gesture-detection primitives detectDragGestures itself uses
                    // internally (awaitFirstDown/awaitTouchSlopOrCancel/drag), so touch-slop
                    // behavior (no jitter-orbit from a near-stationary press) is preserved exactly.
                    awaitEachGesture {
                        val down = awaitAnyButtonDown()
                        val isPan =
                            currentEvent.buttons.isTertiaryPressed || currentEvent.keyboardModifiers.isShiftPressed
                        if (picking != null) {
                            // Escape must reach THIS canvas afterwards, not the script editor.
                            focusRequester.requestFocus()
                            val primary = currentEvent.buttons.isPrimaryPressed && !isPan
                            val secondary = currentEvent.buttons.isSecondaryPressed && !isPan
                            val mods =
                                currentEvent.keyboardModifiers.let {
                                    PickModifiers(
                                        meta = it.isMetaPressed,
                                        ctrl = it.isCtrlPressed,
                                        alt = it.isAltPressed,
                                    )
                                }
                            val threshold = DRAG_THRESHOLD.toPx()
                            var dragging = false
                            var released = false
                            // A hand-written loop instead of drag(): drag() reports "false" for a press that ends
                            // without any movement, but exactly that press-and-release is a click here.
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    released = true
                                    break
                                }
                                if (change.isConsumed) break
                                if (!dragging) {
                                    if ((change.position - down.position).getDistance() <= threshold) continue
                                    dragging = true
                                    picking.onDragStart()
                                }
                                val amount = change.positionChange()
                                change.consume()
                                state.value =
                                    if (isPan) {
                                        CameraInteraction.onPan(
                                            state.value,
                                            amount.x,
                                            amount.y,
                                            size.width.toDouble(),
                                            size.height.toDouble(),
                                        )
                                    } else {
                                        CameraInteraction.onDrag(state.value, amount.x, amount.y)
                                    }
                            }
                            if (released && !dragging && primary) {
                                picking.onClick(down.position.x, down.position.y, frameHolder.frame, mods)
                            } else if (released && !dragging && secondary) {
                                picking.onSecondaryClick(down.position.x, down.position.y, frameHolder.frame, mods)
                            }
                            return@awaitEachGesture
                        }
                        val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (slopChange != null) {
                            drag(down.id) { change ->
                                val amount = change.positionChange()
                                change.consume()
                                state.value =
                                    if (isPan) {
                                        CameraInteraction.onPan(
                                            state.value,
                                            amount.x,
                                            amount.y,
                                            size.width.toDouble(),
                                            size.height.toDouble(),
                                        )
                                    } else {
                                        CameraInteraction.onDrag(state.value, amount.x, amount.y)
                                    }
                            }
                        }
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { state.value = CameraInteraction.reset() })
                }.onPointerEvent(PointerEventType.Move) { event ->
                    // Hover for picking: only with no button pressed (a drag is an orbit/pan, not a hover).
                    if (picking != null && !event.buttons.anyPressed()) {
                        event.changes
                            .firstOrNull()
                            ?.position
                            ?.let { picking.onHover(it.x, it.y, frameHolder.frame) }
                    }
                }.onPointerEvent(PointerEventType.Enter) { event ->
                    if (picking != null && !event.buttons.anyPressed()) {
                        event.changes
                            .firstOrNull()
                            ?.position
                            ?.let { picking.onHover(it.x, it.y, frameHolder.frame) }
                    }
                }.onPointerEvent(PointerEventType.Exit) { picking?.onPointerExit() }
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    // firstOrNull, not first(): an empty `changes` list for a reported scroll
                    // event should be a silent no-op, not a NoSuchElementException that takes the
                    // whole window down with it (same "never crash from an input handler"
                    // principle CameraInteraction.onDrag/onScroll apply to malformed deltas).
                    val deltaY =
                        event.changes
                            .firstOrNull()
                            ?.scrollDelta
                            ?.y ?: return@onPointerEvent
                    state.value = CameraInteraction.onScroll(state.value, deltaY)
                }.focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.R) {
                        state.value = CameraInteraction.reset()
                        true
                    } else if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape && picking != null) {
                        picking.onEscape()
                    } else if (keyEvent.type == KeyEventType.KeyDown &&
                        picking != null &&
                        (keyEvent.key == Key.Menu || (keyEvent.key == Key.F10 && keyEvent.isShiftPressed))
                    ) {
                        picking.onMenuKey()
                    } else {
                        false
                    }
                },
        ) {
            // MeshProjection.project() requires a positive canvas size (see its own `require`).
            // A layout pass can legitimately hand this scope a zero-sized `size` -- e.g. a
            // modifier that (temporarily) collapses to 0 width/height before the surrounding
            // layout settles -- and that must draw nothing for this frame, not throw out of the
            // Compose draw phase and take the whole window down with it. See
            // docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc. Checked FIRST, before any
            // read of `homeFitScale` below.
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            // `homeFitScale` can still be null on the very first draw of a real (non-zero) size,
            // before `onSizeChanged` above has had a chance to update `canvasSize` and this
            // composable has recomposed with the resulting value -- draw nothing for that one
            // frame rather than falling back to an arbitrary scale; the very next frame has it.
            val fitScale = homeFitScale ?: return@Canvas
            val cameraState = state.value
            // panX/panY are stored on ViewerCameraState as a width/height FRACTION at zoom 1.0
            // (see ViewerCameraState's own KDoc) -- converted to MeshProjection's pixel-space
            // panX/panY here, at draw time, the same way `fitScale * cameraState.zoom` converts
            // the auto-fit scale into this frame's actual scale.
            val panXPixels = cameraState.panX * size.width.toDouble() * cameraState.zoom
            val panYPixels = cameraState.panY * size.height.toDouble() * cameraState.zoom
            val framed =
                if (coloredMesh != null) {
                    MeshProjection.projectFramed(
                        coloredMesh,
                        size.width.toDouble(),
                        size.height.toDouble(),
                        cameraState.camera,
                        scale = fitScale * cameraState.zoom,
                        panX = panXPixels,
                        panY = panYPixels,
                    )
                } else {
                    MeshProjection.projectFramed(
                        mesh,
                        size.width.toDouble(),
                        size.height.toDouble(),
                        cameraState.camera,
                        scale = fitScale * cameraState.zoom,
                        panX = panXPixels,
                        panY = panYPixels,
                    )
                }
            val triangles = framed.triangles
            // A plain field, NOT state: writing a State from the draw phase is forbidden (ADR-0012).
            frameHolder.frame = framed.frame
            // Read INSIDE the draw lambda: a hover change invalidates only the draw phase, no recomposition.
            val highlightNow = highlight?.value
            val accent = highlightNow?.accentRgb
            // Read here too, so a visibility change invalidates the draw phase only (ADR-0012, ADR-0055).
            val visibilityNow = visibility?.value
            // The selection outline (ADR-0093): computed before the fill so the tint can be lighter when it shows.
            val outline =
                if (highlightNow != null) {
                    SelectionOutline.compute(triangles, highlightNow, visibilityNow, frameHolder.outlineScratch)
                } else {
                    OutlineResult.None
                }
            val selectFactor =
                if (highlightNow != null && outline is OutlineResult.Segments) {
                    highlightNow.outlineSelectT
                } else {
                    highlightNow?.selectT ?: 0.0
                }
            // Two DISJOINT draw paths, never mixed within one frame's triangle list -- see
            // docs/adr/ADR-0018-smooth-vertex-normals.adoc. `triangles` is homogeneous by
            // construction: `MeshProjection.projectInternal` sets EVERY triangle's
            // `vertexShades` from the SAME source mesh's `vertexNormals` (either every surviving
            // triangle gets one, or `mesh.vertexNormals` was `null` and none do) -- so checking
            // just the first triangle correctly decides the whole frame.
            if (triangles.isNotEmpty() && triangles[0].vertexShades != null) {
                // Smooth (Gouraud) shading, via Skia's drawVertices -- ONE batched draw call for
                // every triangle, in the SAME painter's-algorithm (depth-descending) order
                // `triangles` is already sorted in, so array order alone reproduces correct
                // back-to-front overlap (empirically verified: later-listed triangles win on
                // overlap within a single drawVertices call, exactly like drawPath called
                // sequentially would). BlendMode.DST is REQUIRED, not a stylistic choice --
                // empirically verified against this project's pinned Skiko 0.144.6: with a
                // colors array present, DST reproduces the vertex-interpolated colors verbatim,
                // while SRC/SRC_OVER/MODULATE-with-a-non-white-paint would tint or discard them.
                // No 1px stroke pass here (unlike the flat path below) -- drawVertices rasterizes
                // adjacent triangles without an antialiasing seam between them, and adding a
                // flat-color stroke on top would paint OVER the smooth interpolation right at
                // every triangle edge.
                // Hidden triangles are left out of the batch altogether: no fill, no tint, no outline. A
                // transparent one stays IN the same batch and keeps its place in the global back-to-front
                // order, with its alpha in the vertex colours (empirically verified: BlendMode.DST takes the
                // per-vertex alpha, see SkiaVertexAlphaProbeTest and ADR-0055). Two passes (opaque first, then
                // transparent) would paint a transparent triangle over a nearer opaque one.
                var count = triangles.size
                if (visibilityNow != null && visibilityNow.hasHidden) {
                    count = 0
                    for (t in triangles) if (visibilityNow.modeOf(t.sourceIndex) != CanvasVisibility.HIDDEN) count++
                }
                val positions = FloatArray(count * 6)
                val colors = IntArray(count * 3)
                var pIndex = 0
                var cIndex = 0
                for (t in triangles) {
                    val mode = visibilityNow?.modeOf(t.sourceIndex) ?: CanvasVisibility.VISIBLE
                    if (mode == CanvasVisibility.HIDDEN) continue
                    val alphaByte = if (mode == CanvasVisibility.TRANSPARENT) visibilityNow!!.alphaByte else 255
                    positions[pIndex++] = t.ax.toFloat()
                    positions[pIndex++] = t.ay.toFloat()
                    positions[pIndex++] = t.bx.toFloat()
                    positions[pIndex++] = t.by.toFloat()
                    positions[pIndex++] = t.cx.toFloat()
                    positions[pIndex++] = t.cy.toFloat()
                    val tint = highlightNow?.tintFor(t.sourceIndex, selectFactor) ?: 0.0
                    for (corner in 0..2) {
                        val rgb = t.litRgbAt(corner)
                        if (tint > 0.0 && accent != null) {
                            for (k in 0..2) rgb[k] = rgb[k] + (accent[k] - rgb[k]) * tint
                        }
                        colors[cIndex++] =
                            SkiaColor.makeARGB(
                                alphaByte,
                                (rgb[0].coerceIn(0.0, 1.0) * 255.0).toInt(),
                                (rgb[1].coerceIn(0.0, 1.0) * 255.0).toInt(),
                                (rgb[2].coerceIn(0.0, 1.0) * 255.0).toInt(),
                            )
                    }
                }
                drawIntoCanvas { canvas ->
                    canvas.skiaCanvas.drawVertices(
                        VertexMode.TRIANGLES,
                        positions,
                        colors,
                        null,
                        null,
                        BlendMode.DST,
                        SkiaPaint(),
                    )
                }
            } else {
                for (t in triangles) {
                    val mode = visibilityNow?.modeOf(t.sourceIndex) ?: CanvasVisibility.VISIBLE
                    if (mode == CanvasVisibility.HIDDEN) continue
                    val path =
                        Path().apply {
                            moveTo(t.ax.toFloat(), t.ay.toFloat())
                            lineTo(t.bx.toFloat(), t.by.toFloat())
                            lineTo(t.cx.toFloat(), t.cy.toFloat())
                            close()
                        }
                    // litR/litG/litB already combine shade with the triangle's MeshColor
                    // multiplicatively (see ProjectedTriangle's own KDoc) -- both already in [0, 1]
                    // by construction (shade is coerced in MeshProjection, color channels are
                    // guaranteed in 0.0..1.0 by MeshColor's own init check), so no extra coerceIn is
                    // needed here, matching this file's pre-wave style.
                    val tint = highlightNow?.tintFor(t.sourceIndex, selectFactor) ?: 0.0
                    val color =
                        if (tint > 0.0 && accent != null) {
                            Color(
                                (t.litR + (accent[0] - t.litR) * tint).coerceIn(0.0, 1.0).toFloat(),
                                (t.litG + (accent[1] - t.litG) * tint).coerceIn(0.0, 1.0).toFloat(),
                                (t.litB + (accent[2] - t.litB) * tint).coerceIn(0.0, 1.0).toFloat(),
                            )
                        } else {
                            Color(t.litR.toFloat(), t.litG.toFloat(), t.litB.toFloat())
                        }
                    if (mode == CanvasVisibility.TRANSPARENT) {
                        // Fill only: a 1 px outline in the same alpha would blend twice along every shared edge and
                        // draw a grid over the part. The seams the outline closes are a lesser evil here.
                        drawPath(path, color.copy(alpha = visibilityNow!!.alphaByte / 255f), style = Fill)
                        continue
                    }
                    drawPath(path, color, style = Fill)
                    // Same color, 1px stroke -- closes the antialiasing seams between coplanar
                    // neighboring triangles (Atkinson's "haarriss" fix, see ADR-0010).
                    drawPath(path, color, style = Stroke(width = 1f))
                }
            }
            if (outline is OutlineResult.Segments && highlightNow != null) {
                val lines = outline.points.copyOf(outline.count * 4)
                val halo = highlightNow.haloRgb
                val haloWidth = OUTLINE_HALO_WIDTH.toPx()
                val lineWidth = OUTLINE_WIDTH.toPx()
                drawIntoCanvas { canvas ->
                    // Two opaque passes: a wider halo in the background colour, then the accent line on top.
                    if (halo != null) canvas.skiaCanvas.drawLines(lines, outlinePaint(halo, haloWidth))
                    if (accent != null) canvas.skiaCanvas.drawLines(lines, outlinePaint(accent, lineWidth))
                }
            }
        }

        // Only shown at the untouched HOME pose -- once the user has actually orbited/zoomed/
        // reset, they already know the controls; leaving the hint up permanently would just be
        // visual clutter sitting on top of the shape (Raskin's "don't make me dismiss what I've
        // already learned").
        if (showHint) {
            ViewerControlsHint(Modifier.align(Alignment.BottomCenter))
        }
    }
}
