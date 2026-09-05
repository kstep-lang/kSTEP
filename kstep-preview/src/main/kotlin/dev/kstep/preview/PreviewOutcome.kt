package dev.kstep.preview

import dev.kstep.geometry.OcctAvailability
import dev.kstep.render.gltf.GlbWriteResult
import dev.kstep.script.KStepScriptOutcome

enum class PreviewContentKind { GEOMETRY, SUMMARY, NOTICE }

data class PreviewGeometryStats(
    val shapeCount: Int,
    val previewedShapeIndex: Int,
    /** 2D-projected, painter-culled -- `kstep render --output json`'s `triangleCount`. */
    val projectedTriangleCount: Int,
    /** Raw world-space mesh -- `kstep render --output json`'s `meshTriangleCount`. */
    val meshTriangleCount: Int,
)

/**
 * Deliberately NOT the [dev.kstep.script.KStepModel] itself: [PreviewRenderer] closes every
 * registered `OcctShape` before returning, so handing a caller the model back would hand it a
 * half-closed object. These three numbers are everything `kstep render --output json` ever read
 * off it.
 */
data class PreviewModelStats(
    val rootCount: Int,
    val shapeCount: Int,
    val hasGeometry: Boolean,
)

sealed interface PreviewOutcome {
    /**
     * A container was produced -- ALWAYS, per ADR-0011's Container-Regel: geometry, a
     * product-structure summary card, or a Pflicht-Fallback notice card, in the requested
     * container either way. Deliberately a plain class, not a data class: [bytes] is a
     * `ByteArray`, and a data class's generated `equals`/`hashCode` would compare it by
     * identity -- a silent trap for any future test doing `outcomeA shouldBe outcomeB`.
     */
    class Rendered(
        val bytes: ByteArray,
        /** Concrete -- never [RenderFormat.AUTO]. */
        val format: RenderFormat,
        val contentKind: PreviewContentKind,
        /**
         * Non-null iff [contentKind] == [PreviewContentKind.NOTICE]; one of
         * [RenderFallbackReasons]' constants.
         */
        val fallbackReason: String?,
        /** Non-empty iff NOTICE -- the lines `kstep render` prints to stderr unconditionally. */
        val noticeLines: List<String>,
        val occt: OcctAvailability,
        /**
         * `null` only for the "script threw OcctUnavailableException before stepFile ran"
         * fallback, where no `KStepModel` was ever produced.
         */
        val model: PreviewModelStats?,
        val geometry: PreviewGeometryStats?,
        val glb: GlbWriteResult?,
        /**
         * Non-fatal diagnostics the CALLER prints (the multi-shape R-3 warning). Moved out of
         * `resolveContent`'s direct `System.err.println` so this module stays I/O-free and
         * `CliRenderIntegrationTest`'s R21 stderr assertions keep passing verbatim.
         */
        val warnings: List<String>,
    ) : PreviewOutcome

    /**
     * The script itself failed (compile/validation/runtime, excluding the OCCT-unavailable
     * special case). Carries the raw outcome so `kstep-cli`'s existing, unchanged
     * `printExportError` renders it exactly as before.
     */
    data class ScriptFailed(
        val outcome: KStepScriptOutcome,
    ) : PreviewOutcome

    /**
     * The request itself is out of bounds (width/height vs `RenderLimits`). [message] is the
     * VERBATIM text `runRender` printed before this extraction -- do not reword it.
     */
    data class InvalidRequest(
        val message: String,
    ) : PreviewOutcome
}
