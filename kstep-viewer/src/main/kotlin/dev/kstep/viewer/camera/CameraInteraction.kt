package dev.kstep.viewer.camera

import dev.kstep.render.mesh.Camera
import kotlin.math.pow

/**
 * Pure, Compose-free translation from raw pointer/scroll deltas into a new [ViewerCameraState].
 * No `androidx.compose.*` import anywhere in this file -- deliberately testable (see
 * `CameraInteractionTest`) without a window or even Compose on the test classpath, the same
 * reasoning `dev.kstep.render.mesh`'s package-boundary test applies to the projection math this
 * feeds into ([dev.kstep.render.mesh.MeshProjection]).
 */
object CameraInteraction {
    /** Degrees of orbit per pixel of drag. Tuned by feel, not derived from anything physical --
     *  a full canvas-width drag (a few hundred pixels) should orbit noticeably more than a few
     *  degrees, but a single accidental-jitter pixel should not visibly move the camera. */
    private const val DEG_PER_PIXEL = 0.5

    /** Zoom multiplier applied per scroll "notch" (one unit of scroll delta). */
    private const val ZOOM_FACTOR_PER_NOTCH = 1.1

    /** Caps how much a SINGLE scroll event can zoom by, independent of how large a raw delta the
     *  host OS/input device reports for one event -- see [onScroll]'s KDoc. */
    private const val MAX_NOTCHES_PER_EVENT = 10.0

    private const val MIN_ZOOM = 0.05
    private const val MAX_ZOOM = 50.0

    /**
     * Clamp bound for [ViewerCameraState.panX]/[panY], in the same width/height fraction unit --
     * see [onPan]'s KDoc. Derived from `dev.kstep.render.mesh.MeshProjection.MARGIN_FRACTION`
     * (`0.05`): auto-fit already leaves the model filling `1 - 2 * 0.05 = 0.9` of the canvas at
     * `zoom = 1.0`, so panning the FARTHEST edge of that silhouette back to the canvas center
     * needs at most `0.45` of a fraction unit. Rounded up to `0.5` for a little headroom, not
     * derived to the last decimal -- pan is a feel-tuned interaction, not a physical quantity
     * (same spirit as [DEG_PER_PIXEL] above). See docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc.
     */
    private const val MAX_PAN_FRACTION = 0.5

    /**
     * Applies one drag delta (screen pixels moved since the last event) to [state]'s camera:
     * horizontal drag orbits azimuth, vertical drag orbits elevation.
     *
     * A non-finite [dx]/[dy] -- which [Camera.of] would otherwise reject with
     * [IllegalArgumentException] -- is checked HERE, before ever calling [Camera.of], and simply
     * leaves [state] unchanged. This is deliberate, not an oversight of relying on [Camera.of]'s
     * own validation: a drag/scroll handler runs inside a Compose pointer-input coroutine, and an
     * uncaught exception there does not degrade gracefully -- see this wave's design notes
     * (docs/adr/ADR-0012-viewer-camera-interaction.adoc) -- it kills the whole window. Dropping one
     * malformed input event on the floor is a far better failure mode than that.
     */
    fun onDrag(
        state: ViewerCameraState,
        dx: Float,
        dy: Float,
    ): ViewerCameraState {
        if (!dx.isFinite() || !dy.isFinite()) return state
        // Dragging right (positive dx) should feel like orbiting the camera around the object
        // towards the left -- i.e. DECREASING azimuth -- so the object appears to follow the
        // cursor. Dragging down (positive dy) increases elevation, tilting the view down towards
        // the object's top face, matching the same "content follows the cursor" convention.
        val newAzimuth = state.camera.azimuthDeg - DEG_PER_PIXEL * dx
        val newElevation = state.camera.elevationDeg + DEG_PER_PIXEL * dy
        return state.copy(camera = Camera.of(newAzimuth, newElevation))
    }

    /**
     * Applies one scroll event's vertical delta to [state]'s zoom: a positive [scrollDeltaY]
     * (scrolling down/away on most platforms) zooms out, negative zooms in.
     *
     * [scrollDeltaY] is clamped to +/-[MAX_NOTCHES_PER_EVENT] BEFORE being used as an exponent --
     * different pointing devices and OS scroll-acceleration settings report wildly different
     * magnitudes for what is semantically "one scroll gesture" (a trackpad's inertial fling can
     * report a single event with a delta in the hundreds), and without this clamp such an event
     * would multiply zoom by [ZOOM_FACTOR_PER_NOTCH] raised to that same wildly varying exponent
     * -- e.g. `1.1^300` -- overflowing towards [Double.POSITIVE_INFINITY] in one event before
     * [MAX_ZOOM]'s `coerceIn` below ever gets a chance to clamp the RESULT rather than the input.
     * Both guards matter: this one bounds how much a single event can move the value, [MAX_ZOOM]/
     * [MIN_ZOOM] bound the value itself across arbitrarily many events.
     *
     * Like [onDrag], a non-finite [scrollDeltaY] leaves [state] unchanged rather than reaching
     * [Camera.of]'s (inapplicable here -- zoom is not a [Camera] field) or any other validation
     * path -- see [onDrag]'s KDoc for why that matters inside a Compose pointer-input handler.
     */
    fun onScroll(
        state: ViewerCameraState,
        scrollDeltaY: Float,
    ): ViewerCameraState {
        if (!scrollDeltaY.isFinite()) return state
        val notches = scrollDeltaY.toDouble().coerceIn(-MAX_NOTCHES_PER_EVENT, MAX_NOTCHES_PER_EVENT)
        val newZoom = (state.zoom * ZOOM_FACTOR_PER_NOTCH.pow(-notches)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        return state.copy(zoom = newZoom)
    }

    /**
     * Applies one pan delta (screen pixels moved since the last event -- the SAME raw unit
     * [onDrag]'s `dx`/`dy` already use) to [state]'s pan, added in kSTEP's
     * viewer-pan-and-material-colors wave (see docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc).
     *
     * Converts the raw pixel delta into [ViewerCameraState.panX]/[panY]'s width/height-fraction
     * unit by dividing by `canvasWidth * state.zoom` (resp. `canvasHeight * state.zoom`) BEFORE
     * accumulating -- not storing raw pixels and converting only at draw time. Dividing by
     * `state.zoom` here (not just `canvasWidth`/[canvasHeight]) is what makes the SAME physical
     * mouse-pixel movement translate to a smaller fraction-unit change at a higher zoom, which is
     * exactly the behavior a caller wants: at 2x zoom, panning by one screen pixel should move the
     * (now twice as large) silhouette by the same ONE pixel on screen, not by two. Dividing by
     * `canvasWidth`/[canvasHeight] themselves is what makes the resulting fraction resize-stable --
     * a window resize changes `canvasWidth`/[canvasHeight] for the NEXT event, not the already-
     * accumulated `panX`/`panY` value, so an existing pan offset keeps the same relative on-screen
     * position across a resize.
     *
     * Clamped to +/-[MAX_PAN_FRACTION] after accumulating, exactly like [onScroll] clamps zoom to
     * [MIN_ZOOM]/[MAX_ZOOM] -- bounds the accumulated VALUE, independent of how many pan events
     * got it there.
     *
     * A non-finite [dx]/[dy]/[canvasWidth]/[canvasHeight], or a non-positive [canvasWidth]/
     * [canvasHeight] (which would divide by zero or a negative number), leaves [state] unchanged
     * -- same "never crash from an input handler" principle [onDrag]/[onScroll] already apply to
     * their own malformed inputs.
     */
    fun onPan(
        state: ViewerCameraState,
        dx: Float,
        dy: Float,
        canvasWidth: Double,
        canvasHeight: Double,
    ): ViewerCameraState {
        if (!dx.isFinite() ||
            !dy.isFinite() ||
            !canvasWidth.isFinite() ||
            !canvasHeight.isFinite() ||
            canvasWidth <= 0.0 ||
            canvasHeight <= 0.0
        ) {
            return state
        }
        val newPanX = (state.panX + dx / (canvasWidth * state.zoom)).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION)
        val newPanY = (state.panY + dy / (canvasHeight * state.zoom)).coerceIn(-MAX_PAN_FRACTION, MAX_PAN_FRACTION)
        return state.copy(panX = newPanX, panY = newPanY)
    }

    /** The home pose/zoom/pan -- what double-click and the `R` key reset to in
     *  [dev.kstep.viewer.ui.ShapeCanvas]. */
    fun reset(): ViewerCameraState = ViewerCameraState.HOME
}
