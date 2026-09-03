package dev.kstep.render

/**
 * Shared size/DoS guards for every renderer in this module -- see
 * docs/adr/ADR-0011-headless-preview-rendering.adoc's Security section for the reasoning behind
 * each bound. Centralized here so `kstep-cli`'s `render` command and this module's own writers
 * validate against the exact same numbers rather than two hand-copied constants drifting apart.
 */
object RenderLimits {
    /** Smallest accepted `--width`/`--height`, in pixels. */
    const val MIN_DIMENSION_PX: Int = 16

    /** Largest accepted `--width`/`--height`, in pixels. */
    const val MAX_DIMENSION_PX: Int = 4096

    /** `width * height` must not exceed this -- caps the raster buffer at roughly 32 MB
     *  (`TYPE_INT_RGB`, 4 bytes/pixel) before any allocation happens. */
    const val MAX_TOTAL_PIXELS: Int = 8_000_000

    /** Above this many `<polygon>` elements, [dev.kstep.render.svg.TriangleSvgWriter] output is
     *  considered "large" for logging/warning purposes -- not a hard cap, since
     *  [dev.kstep.geometry.OcctKernel.MAX_TRIANGLES] already bounds the underlying triangle
     *  count natively. */
    const val SVG_TRIANGLE_WARN_THRESHOLD: Int = 50_000

    /** Maximum lines a [dev.kstep.render.text.TextCardRenderer] card renders before truncating
     *  with a summary line. */
    const val MAX_CARD_LINES: Int = 60

    /** Maximum characters per line before a [dev.kstep.render.text.TextCardRenderer] line is
     *  truncated with a trailing marker. */
    const val MAX_CARD_LINE_CHARS: Int = 110

    /** Above this many triangles, a [dev.kstep.render.gltf.GlbWriter] export is logged as
     *  "large" -- not a hard cap ([dev.kstep.geometry.OcctKernel.MAX_TRIANGLES] already bounds
     *  the underlying triangle count natively, and [dev.kstep.render.gltf.GlbWriter] itself
     *  re-checks that same bound before allocating anything), just a signal. Measured: the native
     *  ceiling of 130 000 triangles produces an 8.93 MiB GLB (see
     *  docs/adr/ADR-0016-gltf-glb-export.adoc). */
    const val GLB_TRIANGLE_WARN_THRESHOLD: Int = 50_000
}
