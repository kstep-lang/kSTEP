package dev.kstep.render.gltf

/**
 * What kSTEP writes into a GLB document's `asset.extras.kstep` object (see
 * [GlbWriter]/docs/adr/ADR-0016-gltf-glb-export.adoc). glTF consumers are free to ignore
 * `extras` entirely -- it is not part of the geometric contract -- but a kSTEP-aware consumer
 * (Folge-Welle G-1, an `obsidian-kstep` plugin) can use it to build a caption, or the entire
 * visible content when a script carries no geometry at all ([GlbWriter.writeEmptyScene]).
 *
 * [summaryLines] is attacker-reachable text -- a script's own `name`/`description`/`fileName`
 * values, surfaced the same way [dev.kstep.render.text.TextCardRenderer]'s card text is. It is
 * capped at [dev.kstep.render.RenderLimits.MAX_CARD_LINES] lines of at most
 * [dev.kstep.render.RenderLimits.MAX_CARD_LINE_CHARS] characters each when [GlbWriter] writes it,
 * and serialized via `kotlinx-serialization-json`'s `JsonObjectBuilder`/`JsonArrayBuilder` --
 * deliberately NOT [dev.kstep.render.svg.SvgEscaping], whose XML-escaping rules do not apply to
 * JSON (JSON allows literal non-ASCII text that XML would need entity-escaped, and needs its own
 * escaping for `"`, `\` and control characters that SVG escaping does not perform).
 */
data class GlbExtras(
    val scriptName: String? = null,
    val summaryLines: List<String> = emptyList(),
) {
    companion object {
        val EMPTY: GlbExtras = GlbExtras()
    }
}
