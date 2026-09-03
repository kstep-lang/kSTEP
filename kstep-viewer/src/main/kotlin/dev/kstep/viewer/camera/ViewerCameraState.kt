package dev.kstep.viewer.camera

import dev.kstep.render.mesh.Camera

/**
 * The interactive viewer's full camera state: an orbit pose plus a zoom multiplier applied on top
 * of whatever auto-fit scale the current mesh/canvas size computes.
 *
 * Zoom is deliberately its own field, NOT folded into [Camera] itself -- [Camera] models a pure
 * orbit direction (see its own KDoc) and is shared with `kstep-render`'s headless preview path
 * (`kstep-cli`'s `render` command, a future R-2 `--azimuth`/`--elevation` flag), neither of which
 * has a notion of "zoom relative to auto-fit". Keeping [zoom] here, `kstep-viewer`-side, means
 * [Camera] itself never needs a concept that only makes sense next to an interactive canvas.
 */
data class ViewerCameraState(
    val camera: Camera,
    val zoom: Double,
) {
    companion object {
        /** The state [dev.kstep.viewer.ui.ShapeCanvas] starts in, and returns to on reset (double-
         *  click, `R` key) -- [Camera.ISOMETRIC] pose, no extra zoom. */
        val HOME = ViewerCameraState(Camera.ISOMETRIC, 1.0)
    }
}
