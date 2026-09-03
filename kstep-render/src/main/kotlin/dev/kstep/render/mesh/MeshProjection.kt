package dev.kstep.render.mesh

import dev.kstep.geometry.TriangleMesh
import kotlin.math.max
import kotlin.math.min

/**
 * Pure data transformation `TriangleMesh -> List<ProjectedTriangle>`. Knows nothing about
 * Compose (see this file's package -- `dev.kstep.render.mesh` -- and
 * `MeshPackageComposeBoundaryTest`'s automated import check) -- which is exactly why it is
 * testable without a window, and why both the Compose canvas AND the headless AWT
 * rasterizer/SVG writer consume the SAME list from THIS function, instead of several similar
 * paths.
 *
 * [project] is a pure, stateless function -- no object-level mutable state -- so it is safe to
 * call concurrently (e.g. from a Compose recomposition on one thread while a render call runs on
 * another).
 *
 * Moved from `kstep-viewer` into this module in kSTEP's headless-preview-rendering wave (see
 * docs/adr/ADR-0011-headless-preview-rendering.adoc) -- `kstep-viewer` now depends on
 * `kstep-render` instead of owning this package itself, so `kstep-cli`'s `render` command can
 * reuse the identical projection math without pulling Compose Desktop onto its classpath.
 *
 * Renamed from `IsometricProjection` in kSTEP's viewer-camera-interaction wave (see
 * docs/adr/ADR-0012-viewer-camera-interaction.adoc) once the camera pose stopped being a single
 * fixed isometric direction -- "isometric" is now just [Camera.ISOMETRIC]'s default pose, not
 * this object's only pose.
 */
object MeshProjection {
    /**
     * Fixed home view direction (from the viewer, into the scene), `normalize(-1, -1, -1)`.
     *
     * `private`, not `internal` like the pre-wave `VIEW_DIRECTION` this replaces: nothing outside
     * this object needs the raw home direction any more -- [Camera.ISOMETRIC] is the public way
     * to name this pose now, and [basisFor] is the only caller that ever reads this `val`. Kept
     * as its own named constant (rather than inlined into [RIGHT_HOME]/[TRUE_UP_HOME]'s
     * initializers) so [basisFor]'s ISOMETRIC fast path can return it directly.
     */
    private val VIEW_DIRECTION_HOME: Vec3 = Vec3(-1.0, -1.0, -1.0).normalized()

    /**
     * Fixed key-light direction (from the light, into the scene) -- DELIBERATELY NOT
     * [VIEW_DIRECTION_HOME]. Verified by actually rendering a box and looking at the PNG (see
     * `docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc`'s Stolperfallen): a light shining
     * exactly along the camera's own view direction makes every visible face of an
     * axis-aligned solid IDENTICALLY shaded -- the three visible faces' normals `(1,0,0)`,
     * `(0,1,0)`, `(0,0,1)` are each exactly `1/sqrt(3)` from `-VIEW_DIRECTION_HOME`, so a box
     * rendered that way is a flat, uniform-gray hexagon with no visible face separation at all
     * (the PNG this bug produced is preserved in this wave's implementation notes). Offsetting
     * the light from the camera -- a standard technique in isometric CAD/game rendering -- gives
     * the three faces genuinely different shades instead.
     *
     * As of the viewer-camera-interaction wave (see docs/adr/ADR-0012-viewer-camera-interaction.adoc)
     * this direction is fixed relative to the HOME camera basis, not to world space: [basisFor]
     * decomposes it into [LIGHT_RIGHT_COMPONENT]/[LIGHT_UP_COMPONENT]/[LIGHT_VIEW_COMPONENT] once,
     * below, and recombines those against each call's actual screen basis -- so the key light
     * orbits together with the camera instead of staying fixed in world space and going edge-on
     * (and the shaded faces flat again) once the camera has orbited far enough from home.
     */
    private val LIGHT_DIRECTION_HOME: Vec3 = Vec3(-0.2, -0.4, -1.0).normalized()

    /**
     * Fixed fill-light direction (from the light, into the scene), added on top of [AMBIENT] and
     * the key light in kSTEP's multi-shape-composition-and-fill-light wave (see
     * `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`) -- a second, dimmer light
     * that softens the shadow side a key-only render leaves at exactly [AMBIENT], without
     * touching [AMBIENT] or [KEY_WEIGHT] themselves.
     *
     * DELIBERATELY NOT the antiparallel of [LIGHT_DIRECTION_HOME] (i.e. not
     * `Vec3(0.2, 0.4, 1.0)`): an antiparallel fill would shine exactly opposite the key light, so
     * for a symmetric solid the two faces on either side of the key light would end up
     * identically lit again -- the same flattening failure mode `LIGHT_DIRECTION_HOME`'s own KDoc
     * already documents for a light aligned with the view direction, just relocated to the
     * fill/key pair instead of the key/view pair. Decomposed into the home camera basis and
     * recombined against the actual camera basis exactly like [LIGHT_DIRECTION_HOME] -- see
     * [FILL_RIGHT_COMPONENT]/[FILL_UP_COMPONENT]/[FILL_VIEW_COMPONENT] and [basisFor].
     */
    private val FILL_DIRECTION_HOME: Vec3 = Vec3(-0.8, -0.45, 0.35).normalized()

    /** World-space "up" for the screen basis. Not a camera degree of freedom -- every [Camera]
     *  pose in this wave orbits around this fixed axis; see [Camera]'s own KDoc. */
    private val UP: Vec3 = Vec3(0.0, 0.0, 1.0)

    const val MARGIN_FRACTION: Double = 0.05
    const val AMBIENT: Double = 0.25

    /** Key-light weight -- was inlined as `(1.0 - AMBIENT)` before kSTEP's
     *  multi-shape-composition-and-fill-light wave; named explicitly now that a second,
     *  [FILL_WEIGHT] term exists alongside it in [projectInternal]'s shading formula. */
    private const val KEY_WEIGHT: Double = 0.75

    /** Fill-light weight -- deliberately small relative to [KEY_WEIGHT], so the fill light softens
     *  the shadow side without washing out the key light's own face-to-face contrast. See
     *  `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc` for the measured effect on
     *  the darkest visible face (bumps it from exactly [AMBIENT] to comfortably above it). */
    private const val FILL_WEIGHT: Double = 0.18

    private const val DEGENERATE_LENGTH: Double = 1e-12

    // The home screen basis, computed once at class-init time -- this is BOTH (a) the exact
    // basis the ISOMETRIC fast path in basisFor() returns, bit-identical to what this codebase
    // computed before this wave, and (b) the fixed reference frame LIGHT_DIRECTION_HOME is
    // decomposed into below, so a non-ISOMETRIC call's recombined light is expressed relative to
    // this same frame regardless of which camera the caller actually asked for.
    private val RIGHT_HOME: Vec3 =
        (UP cross VIEW_DIRECTION_HOME).normalized().let { if (it == Vec3.ZERO) Vec3(1.0, 0.0, 0.0) else it }
    private val TRUE_UP_HOME: Vec3 =
        (VIEW_DIRECTION_HOME cross RIGHT_HOME).normalized().let { if (it == Vec3.ZERO) Vec3(0.0, 1.0, 0.0) else it }

    // LIGHT_DIRECTION_HOME expressed in the (RIGHT_HOME, TRUE_UP_HOME, VIEW_DIRECTION_HOME)
    // orthonormal basis. Recombining these three scalars against a DIFFERENT orthonormal basis in
    // basisFor() rotates the light together with whatever that basis represents.
    private val LIGHT_RIGHT_COMPONENT: Double = LIGHT_DIRECTION_HOME dot RIGHT_HOME
    private val LIGHT_UP_COMPONENT: Double = LIGHT_DIRECTION_HOME dot TRUE_UP_HOME
    private val LIGHT_VIEW_COMPONENT: Double = LIGHT_DIRECTION_HOME dot VIEW_DIRECTION_HOME

    // FILL_DIRECTION_HOME expressed in the same (RIGHT_HOME, TRUE_UP_HOME, VIEW_DIRECTION_HOME)
    // orthonormal basis -- see LIGHT_RIGHT_COMPONENT/LIGHT_UP_COMPONENT/LIGHT_VIEW_COMPONENT above
    // for why this decompose-once/recombine-per-call shape exists at all.
    private val FILL_RIGHT_COMPONENT: Double = FILL_DIRECTION_HOME dot RIGHT_HOME
    private val FILL_UP_COMPONENT: Double = FILL_DIRECTION_HOME dot TRUE_UP_HOME
    private val FILL_VIEW_COMPONENT: Double = FILL_DIRECTION_HOME dot VIEW_DIRECTION_HOME

    private data class RawTriangle(
        val ax: Double,
        val ay: Double,
        val bx: Double,
        val by: Double,
        val cx: Double,
        val cy: Double,
        val shade: Double,
        val depth: Double,
    )

    /** The resolved screen basis + key-light and fill-light directions for one [Camera] pose. */
    private data class ScreenBasis(
        val right: Vec3,
        val trueUp: Vec3,
        val view: Vec3,
        val lightDirection: Vec3,
        val fillDirection: Vec3,
    )

    /**
     * Resolves [camera] into a screen basis + key-light direction.
     *
     * The `camera === Camera.ISOMETRIC` fast path exists ONLY to guarantee bit-for-bit identical
     * output to this codebase's pre-wave behavior for the (extremely common -- every call site
     * before this wave, and the implicit default of both [project] and [fitScale] today) case
     * where no camera argument is given at all. A structurally equal but separately constructed
     * `Camera.of(45.0, 35.264389682754654)` deliberately does NOT take this fast path -- it goes
     * through the general trig + light-recombination path below like any other pose, and lands
     * within `1e-12` of the fast path's result rather than bit-identical to it (see
     * `CameraProjectionTest`).
     */
    private fun basisFor(camera: Camera): ScreenBasis {
        if (camera === Camera.ISOMETRIC) {
            return ScreenBasis(RIGHT_HOME, TRUE_UP_HOME, VIEW_DIRECTION_HOME, LIGHT_DIRECTION_HOME, FILL_DIRECTION_HOME)
        }

        val view = camera.viewDirection()
        // Same UP-cross-view degenerate fallback as the fixed-camera code this replaces -- UP is
        // never parallel to view for any camera in [-89, 89] elevation (Camera.of's own clamp),
        // so this is unreachable in practice, but keeps a future camera-construction change fail
        // safe (a degenerate, still-finite basis) instead of propagating NaNs downstream.
        val right = (UP cross view).normalized().let { if (it == Vec3.ZERO) Vec3(1.0, 0.0, 0.0) else it }
        val trueUp = (view cross right).normalized().let { if (it == Vec3.ZERO) Vec3(0.0, 1.0, 0.0) else it }

        val recombinedLight =
            Vec3(
                right.x * LIGHT_RIGHT_COMPONENT + trueUp.x * LIGHT_UP_COMPONENT + view.x * LIGHT_VIEW_COMPONENT,
                right.y * LIGHT_RIGHT_COMPONENT + trueUp.y * LIGHT_UP_COMPONENT + view.y * LIGHT_VIEW_COMPONENT,
                right.z * LIGHT_RIGHT_COMPONENT + trueUp.z * LIGHT_UP_COMPONENT + view.z * LIGHT_VIEW_COMPONENT,
            ).normalized()
        // The recombination of three orthonormal components is already unit length up to
        // rounding -- normalized() above is a no-op in practice, but the Vec3.ZERO fallback below
        // still protects against a future change to LIGHT_DIRECTION_HOME that made the light
        // exactly antiparallel to itself under some basis (never true today, kept fail-safe).
        val lightDirection = if (recombinedLight == Vec3.ZERO) LIGHT_DIRECTION_HOME else recombinedLight

        // Identical recombination for the fill light, against the same (right, trueUp, view)
        // basis -- see FILL_DIRECTION_HOME's own KDoc for why the fill direction is decomposed
        // and recombined exactly like the key light rather than left fixed in world space.
        val recombinedFill =
            Vec3(
                right.x * FILL_RIGHT_COMPONENT + trueUp.x * FILL_UP_COMPONENT + view.x * FILL_VIEW_COMPONENT,
                right.y * FILL_RIGHT_COMPONENT + trueUp.y * FILL_UP_COMPONENT + view.y * FILL_VIEW_COMPONENT,
                right.z * FILL_RIGHT_COMPONENT + trueUp.z * FILL_UP_COMPONENT + view.z * FILL_VIEW_COMPONENT,
            ).normalized()
        val fillDirection = if (recombinedFill == Vec3.ZERO) FILL_DIRECTION_HOME else recombinedFill

        return ScreenBasis(right, trueUp, view, lightDirection, fillDirection)
    }

    /** Everything [project]/[fitScale] share: the culled, shaded, screen-space triangles for one
     *  call, plus the scale actually used -- either [requestedScale] itself, or (when it is
     *  `null`) the auto-fit scale this call computed from the projected silhouette's bounding
     *  box. [fitScale] is a thin wrapper that discards [ProjectionResult.triangles] and keeps only
     *  [ProjectionResult.scale] -- see [fitScale]'s own KDoc for why that (rather than a second,
     *  bbox-only code path) is this wave's deliberate choice. */
    private data class ProjectionResult(
        val triangles: List<ProjectedTriangle>,
        val scale: Double,
    )

    private fun projectInternal(
        mesh: TriangleMesh,
        canvasWidth: Double,
        canvasHeight: Double,
        camera: Camera,
        requestedScale: Double?,
    ): ProjectionResult {
        require(canvasWidth > 0.0 && canvasHeight > 0.0) {
            "canvasWidth/canvasHeight must be positive, got ($canvasWidth, $canvasHeight)"
        }
        if (mesh.triangleCount == 0) return ProjectionResult(emptyList(), requestedScale ?: 1.0)

        val basis = basisFor(camera)
        val negLightDirection = Vec3(-basis.lightDirection.x, -basis.lightDirection.y, -basis.lightDirection.z)
        val negFillDirection = Vec3(-basis.fillDirection.x, -basis.fillDirection.y, -basis.fillDirection.z)

        val raw = ArrayList<RawTriangle>(mesh.triangleCount)
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY

        val c = mesh.coordinates
        var i = 0
        while (i < c.size) {
            val v0 = Vec3(c[i], c[i + 1], c[i + 2])
            val v1 = Vec3(c[i + 3], c[i + 4], c[i + 5])
            val v2 = Vec3(c[i + 6], c[i + 7], c[i + 8])
            i += 9

            val normal = (v1 - v0) cross (v2 - v0)
            val normalLength = normal.length()
            if (normalLength <= DEGENERATE_LENGTH) {
                continue // degenerate (zero-area) triangle
            }

            // Backface culling: a normal pointing away from the viewer has a non-negative
            // component along the view direction (which points FROM the viewer INTO the scene).
            if ((normal dot basis.view) >= 0.0) continue

            val unitNormal = Vec3(normal.x / normalLength, normal.y / normalLength, normal.z / normalLength)
            // Additive two-light model: AMBIENT floor + a KEY_WEIGHT-weighted key light (as
            // before this wave) + a dimmer FILL_WEIGHT-weighted fill light on top (new in kSTEP's
            // multi-shape-composition-and-fill-light wave, see
            // docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc). coerceIn is
            // FUNCTIONALLY REQUIRED, not defensive polish: the brightest possible face
            // (unitNormal exactly anti-parallel to both lights) sums to
            // AMBIENT + KEY_WEIGHT + FILL_WEIGHT = 1.18, which must be clamped back into a valid
            // shade/brightness range.
            val shade =
                (
                    AMBIENT +
                        KEY_WEIGHT * max(0.0, unitNormal dot negLightDirection) +
                        FILL_WEIGHT * max(0.0, unitNormal dot negFillDirection)
                ).coerceIn(0.0, 1.0)

            val ax = v0 dot basis.right
            val ay = -(v0 dot basis.trueUp)
            val bx = v1 dot basis.right
            val by = -(v1 dot basis.trueUp)
            val cx = v2 dot basis.right
            val cy = -(v2 dot basis.trueUp)

            minX = min(minX, min(ax, min(bx, cx)))
            maxX = max(maxX, max(ax, max(bx, cx)))
            minY = min(minY, min(ay, min(by, cy)))
            maxY = max(maxY, max(ay, max(by, cy)))

            val centroid = Vec3((v0.x + v1.x + v2.x) / 3.0, (v0.y + v1.y + v2.y) / 3.0, (v0.z + v1.z + v2.z) / 3.0)
            val depth = centroid dot basis.view

            raw.add(RawTriangle(ax, ay, bx, by, cx, cy, shade, depth))
        }

        if (raw.isEmpty()) return ProjectionResult(emptyList(), requestedScale ?: 1.0)

        val bboxWidth = maxX - minX
        val bboxHeight = maxY - minY
        val widthFits = bboxWidth > DEGENERATE_LENGTH
        val heightFits = bboxHeight > DEGENERATE_LENGTH
        val autoFitScale =
            when {
                !widthFits && !heightFits -> 1.0
                !heightFits -> (canvasWidth * (1.0 - 2.0 * MARGIN_FRACTION)) / bboxWidth
                !widthFits -> (canvasHeight * (1.0 - 2.0 * MARGIN_FRACTION)) / bboxHeight
                else ->
                    min(
                        (canvasWidth * (1.0 - 2.0 * MARGIN_FRACTION)) / bboxWidth,
                        (canvasHeight * (1.0 - 2.0 * MARGIN_FRACTION)) / bboxHeight,
                    )
            }
        val effectiveScale = requestedScale ?: autoFitScale
        // Centering is ALWAYS computed from the current projected silhouette's bounding box,
        // regardless of whether the caller passed an explicit scale or left it to auto-fit -- a
        // caller-supplied zoom scale changes how large the silhouette appears, not where its own
        // center sits on screen.
        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0
        val halfW = canvasWidth / 2.0
        val halfH = canvasHeight / 2.0

        val triangles =
            raw
                .map { t ->
                    ProjectedTriangle(
                        ax = (t.ax - centerX) * effectiveScale + halfW,
                        ay = (t.ay - centerY) * effectiveScale + halfH,
                        bx = (t.bx - centerX) * effectiveScale + halfW,
                        by = (t.by - centerY) * effectiveScale + halfH,
                        cx = (t.cx - centerX) * effectiveScale + halfW,
                        cy = (t.cy - centerY) * effectiveScale + halfH,
                        shade = t.shade,
                        depth = t.depth,
                    )
                }.sortedByDescending { it.depth }

        return ProjectionResult(triangles, effectiveScale)
    }

    /**
     * The scale factor [project] would use to auto-fit [mesh]'s silhouette (as seen from
     * [camera]) into a [canvasWidth] x [canvasHeight] canvas, without building the projected
     * triangle list.
     *
     * Implemented as a thin wrapper around the same private pipeline [project] uses (called with
     * `requestedScale = null`, i.e. auto-fit) rather than a second, bbox-only code path -- a
     * second path risks silently drifting out of sync with [project]'s actual auto-fit formula.
     * This means a [fitScale] call pays for the full cull/shade/triangle-build pipeline just to
     * extract one `Double` out of it; deliberately accepted for this wave, because `kstep-viewer`
     * calls this at most once per window resize or camera reset (see
     * `dev.kstep.viewer.ui.ShapeCanvas`), never once per frame -- see
     * docs/adr/ADR-0012-viewer-camera-interaction.adoc for the reasoning, so a future wave that
     * DOES need this in a hot path knows this was a conscious tradeoff, not an oversight.
     */
    fun fitScale(
        mesh: TriangleMesh,
        canvasWidth: Double,
        canvasHeight: Double,
        camera: Camera = Camera.ISOMETRIC,
    ): Double = projectInternal(mesh, canvasWidth, canvasHeight, camera, requestedScale = null).scale

    /**
     * Projects [mesh] as seen from [camera] onto a [canvasWidth] x [canvasHeight] canvas,
     * flat-shaded and painter's-algorithm-ordered (see [ProjectedTriangle.depth]).
     *
     * [camera] defaults to [Camera.ISOMETRIC] and [scale] defaults to `null` (auto-fit) -- calling
     * this with no arguments beyond [mesh]/[canvasWidth]/[canvasHeight] reproduces this
     * codebase's pre-wave behavior exactly (see `CameraProjectionTest`'s determinism guard, which
     * asserts the implicit-default call and the fully-explicit
     * `project(mesh, w, h, Camera.ISOMETRIC, null)` call return identical output).
     *
     * @param scale `null` auto-fits [mesh]'s silhouette to the canvas, same as this codebase's
     *   only behavior before this wave. A non-null value is used AS THE SCALE FACTOR directly
     *   (e.g. `fitScale(...) * zoomFactor` from a caller that wants to zoom around the auto-fit
     *   size) -- must be finite and strictly positive.
     */
    fun project(
        mesh: TriangleMesh,
        canvasWidth: Double,
        canvasHeight: Double,
        camera: Camera = Camera.ISOMETRIC,
        scale: Double? = null,
    ): List<ProjectedTriangle> {
        require(scale == null || (scale.isFinite() && scale > 0.0)) {
            "scale must be null (auto-fit) or a finite, positive value, got $scale"
        }
        return projectInternal(mesh, canvasWidth, canvasHeight, camera, scale).triangles
    }
}
