package dev.kstep.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.mesh.Camera
import dev.kstep.render.mesh.MeshProjection
import dev.kstep.viewer.camera.CameraInteraction
import dev.kstep.viewer.camera.ViewerCameraState

private const val HINT_TEXT = "Drag to orbit · Scroll to zoom · Double-click or R to reset"

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
 * @param state The full camera pose (orbit angles + zoom). Deliberately a raw [MutableState]
 *   rather than this composable owning a private one unconditionally -- see the class doc above.
 */
@OptIn(ExperimentalComposeUiApi::class) // Modifier.onPointerEvent (scroll-to-zoom) is experimental
// in this Compose Multiplatform version -- no unstable behavior relied on beyond the API shape
// itself, and there is no stable scroll-event API in this version to use instead.
@Composable
fun ShapeCanvas(
    mesh: TriangleMesh,
    modifier: Modifier = Modifier,
    state: MutableState<ViewerCameraState> = remember { mutableStateOf(ViewerCameraState.HOME) },
) {
    val focusRequester = remember { FocusRequester() }
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
        remember(mesh, canvasSize) {
            if (canvasSize.width <= 0 || canvasSize.height <= 0) {
                null
            } else {
                MeshProjection.fitScale(
                    mesh,
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

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        state.value = CameraInteraction.onDrag(state.value, drag.x, drag.y)
                    }
                }.pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { state.value = CameraInteraction.reset() })
                }.onPointerEvent(PointerEventType.Scroll) { event ->
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
                    } else {
                        false
                    }
                }.pointerHoverIcon(PointerIcon.Hand),
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
            val triangles =
                MeshProjection.project(
                    mesh,
                    size.width.toDouble(),
                    size.height.toDouble(),
                    cameraState.camera,
                    scale = fitScale * cameraState.zoom,
                )
            for (t in triangles) {
                val path =
                    Path().apply {
                        moveTo(t.ax.toFloat(), t.ay.toFloat())
                        lineTo(t.bx.toFloat(), t.by.toFloat())
                        lineTo(t.cx.toFloat(), t.cy.toFloat())
                        close()
                    }
                val shade = t.shade.toFloat()
                val color = Color(shade, shade, shade)
                drawPath(path, color, style = Fill)
                // Same color, 1px stroke -- closes the antialiasing seams between coplanar
                // neighboring triangles (Atkinson's "haarriss" fix, see ADR-0010).
                drawPath(path, color, style = Stroke(width = 1f))
            }
        }

        // Only shown at the untouched HOME pose -- once the user has actually orbited/zoomed/
        // reset, they already know the controls; leaving the hint up permanently would just be
        // visual clutter sitting on top of the shape (Raskin's "don't make me dismiss what I've
        // already learned").
        if (showHint) {
            Text(
                HINT_TEXT,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
