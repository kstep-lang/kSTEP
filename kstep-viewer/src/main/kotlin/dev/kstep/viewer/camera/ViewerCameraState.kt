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
 *
 * [panX]/[panY], added in kSTEP's viewer-pan-and-material-colors wave (see
 * docs/adr/ADR-0017-viewer-pan-and-part-colors.adoc), are trailing parameters defaulting to
 * `0.0` -- every pre-wave `ViewerCameraState(camera, zoom)` call site keeps compiling and
 * behaving unchanged.
 *
 * @property panX @property panY Camera pan, as a FRACTION of canvas width/height at zoom 1.0 --
 *   NOT pixels. Converted to pixels as `panX * canvasWidth * zoom` (resp. `panY * canvasHeight *
 *   zoom`) right before calling `dev.kstep.render.mesh.MeshProjection.project`'s own pixel-space
 *   `panX`/`panY`. This fraction unit is deliberately resize- and zoom-invariant: the same value
 *   keeps the same silhouette offset relative to the canvas after a window resize or a zoom
 *   change, unlike a raw-pixel value (see [dev.kstep.viewer.camera.CameraInteraction.onPan]'s
 *   KDoc for the full derivation).
 */
data class ViewerCameraState(
    val camera: Camera,
    val zoom: Double,
    val panX: Double = 0.0,
    val panY: Double = 0.0,
) {
    companion object {
        /** The state [dev.kstep.viewer.ui.ShapeCanvas] starts in, and returns to on reset (double-
         *  click, `R` key) -- [Camera.ISOMETRIC] pose, no extra zoom, no pan. */
        val HOME = ViewerCameraState(Camera.ISOMETRIC, 1.0, 0.0, 0.0)
    }
}
