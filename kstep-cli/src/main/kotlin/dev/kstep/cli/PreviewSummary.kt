package dev.kstep.cli

import dev.kstep.geometry.OcctAvailability
import dev.kstep.script.KStepModel
import dev.kstep.step21.Part21Writer

private const val MAX_ENTITY_LINES = 200
private const val OCCT_INSTALL_COMMAND =
    "sudo apt-get install --no-install-recommends libocct-foundation-dev " +
        "libocct-modeling-data-dev libocct-modeling-algorithms-dev libocct-data-exchange-dev"

/** Fallback-reason codes carried by `kstep render`'s `--output json` document's
 *  `fallbackReason` field -- see docs/adr/ADR-0011-headless-preview-rendering.adoc's inhalts-/
 *  Exit-Code-Matrix. */
object RenderFallbackReasons {
    const val OCCT_UNAVAILABLE = "occt_unavailable"
    const val TRIANGULATION_FAILED = "triangulation_failed"
    const val SHAPE_CLOSED_BY_SCRIPT = "shape_closed_by_script"
}

/**
 * Pure `KStepModel -> List<String>` renderers for `kstep render`'s text-card content -- no I/O,
 * deterministic given a fixed [model] (its [dev.kstep.step21.Part21Header.timestamp] is already
 * defaulted by [dev.kstep.script.KStepScriptHost] before this is ever called). Reused verbatim by
 * ALL three containers ([RenderFormat.SVG]/[RenderFormat.PNG]/[RenderFormat.TEXT]) via
 * `dev.kstep.render.text.TextCardRenderer` -- see [RenderCommand].
 *
 * ASCII-only throughout (`--` instead of an em dash, `...` instead of an ellipsis) -- mirrors
 * `Part21EncodingException`'s own encoding stance and keeps generated SVG/`.txt` output free of
 * encoding surprises. Non-ASCII values FROM the model itself (a script's own `name`/`description`
 * strings) still reach the output verbatim here; [dev.kstep.render.svg.SvgEscaping] and this
 * function's own truncation are what keep them from breaking the container, not ASCII
 * normalization of the model's own field values.
 */
object PreviewSummary {
    /** The header block shared by every content case ("Model" line group is intentionally
     *  omitted here -- that only makes sense when a model exists at all; [noticeLines] covers
     *  the no-model-yet case, [modelLines] covers the model-present case). */
    private fun titleLine(scriptName: String): String = "kSTEP preview -- $scriptName"

    // Any absolute filesystem path embedded in an OcctAvailability string (occt.librarySource's
    // "bundled classpath resource (<path>)"/"override property (...=<path>)" forms, or an
    // UnsatisfiedLinkError's message inside occt.reason) is redacted to just its last path
    // segment before it is ever written into a rendered preview. These previews (SVG/PNG/.txt)
    // are the artifacts ADR-0011 explicitly means to be embedded in Obsidian notes and
    // Asciidoctor documents and thus committed/shared -- a literal `-Dkstep.occt.bridge.library=
    // /home/<user>/...` override path or a failed System.load() path can otherwise leak a local
    // username and directory layout into a shared artifact. The full, unredacted path still goes
    // to the log line in OcctNativeLibrary.load, which is not shared the same way.
    private val ABSOLUTE_PATH = Regex("""/[^\s()'"]+""")

    private fun redactPaths(text: String): String =
        ABSOLUTE_PATH.replace(text) { match ->
            val path = match.value
            val lastSlash = path.lastIndexOf('/')
            val baseName = if (lastSlash in 0 until path.length - 1) path.substring(lastSlash + 1) else ""
            if (baseName.isEmpty()) "<redacted>" else "<redacted>/$baseName"
        }

    private fun occtLines(occt: OcctAvailability): List<String> =
        when (occt) {
            is OcctAvailability.Available ->
                listOf("OCCT", "  available -- ${occt.occtVersion} (${redactPaths(occt.librarySource)})")
            is OcctAvailability.Unavailable ->
                listOf("OCCT", "  unavailable -- ${redactPaths(occt.reason)}")
        }

    /** Renders a model that DID produce previewable content -- either a real geometry render
     *  (see [RenderCommand]) or a plain product-structure summary (no shapes registered). Both
     *  share this one header/instance-list rendering; [contentLines] carries whatever differs
     *  (a "geometry" section vs. a "product structure only" line). */
    fun modelLines(
        scriptName: String,
        model: KStepModel,
        occt: OcctAvailability,
        withStep: Boolean,
        contentLines: List<String> = emptyList(),
    ): List<String> {
        val lines = mutableListOf<String>()
        lines += titleLine(scriptName)
        lines += ""
        lines += "Model"
        lines += "  file name   ${model.header.fileName}"
        lines += "  schema      ${model.header.schemaIdentifiers.joinToString(", ")}"
        lines += "  timestamp   ${model.header.timestamp}"
        lines += "  roots       ${model.roots.size}"
        lines += "  shapes      ${model.shapes.size}"
        lines += if (model.hasGeometry) "  geometry    present" else "  geometry    none -- product structure only"
        if (contentLines.isNotEmpty()) {
            lines += ""
            lines += contentLines
        }
        lines += ""
        lines += "Validation"
        lines += "  OK -- 0 violations"
        lines += ""
        lines += occtLines(occt)
        lines += ""
        lines += entityListLines(model)
        if (withStep) {
            lines += ""
            lines += part21TextLines(model)
        }
        return lines
    }

    /** Renders the "geometry present, but could not be rendered this run" fallback -- the
     *  Pflicht-Fallback content, painted into whichever container was requested (see
     *  `RenderFormat`'s "Container-Regel"). [model] is `null` only when the script never even
     *  finished evaluating far enough to produce a [KStepModel] -- not a case [RenderCommand]
     *  currently reaches (an evaluation failure short-circuits before this is called), kept
     *  nullable so this function's contract does not silently assume otherwise. */
    fun noticeLines(
        scriptName: String,
        reason: String,
        occt: OcctAvailability,
        model: KStepModel?,
    ): List<String> {
        val lines = mutableListOf<String>()
        lines += titleLine(scriptName)
        lines += ""
        lines += "Geometry preview unavailable"
        lines += "  This script builds geometry, but no image could be rendered this run, so a"
        lines += "  text preview is shown instead."
        lines += ""
        lines += "  reason: ${redactPaths(reason)}"
        lines += ""
        if (occt is OcctAvailability.Unavailable) {
            lines += "  Install the OCCT dev packages, then rebuild:"
            lines += "    $OCCT_INSTALL_COMMAND"
            lines += ""
        }
        lines += occtLines(occt)
        if (model != null) {
            lines += ""
            lines += "  roots       ${model.roots.size}"
            lines += "  shapes      ${model.shapes.size}"
            lines += ""
            lines += entityListLines(model)
        }
        return lines
    }

    // Mirrors part21TextLines' own try/catch below (same RuntimeException catch, same
    // "unavailable: <message>" wording) -- Part21Writer.emit throws Part21WriteException the
    // moment ANY object reachable from model.roots is not one of the twelve supported
    // kstep-core AP242 V1/support entity types (see Part21Writer.unsupportedInstanceType). This
    // function is called from BOTH modelLines (the "everything is fine" content path) and
    // noticeLines (the Pflicht-Fallback path ADR-0011 promises never crashes) -- an unguarded
    // call here would let an unsupported-type model take down the whole render, including the
    // one path whose entire job is to degrade gracefully instead of throwing.
    private fun entityListLines(model: KStepModel): List<String> {
        val emitted =
            try {
                Part21Writer.emit(model.roots, startId = 1)
            } catch (e: RuntimeException) {
                val detail = e.message ?: e::class.simpleName ?: "unknown error"
                return listOf("Part 21 instances (unavailable: ${redactPaths(detail)})")
            }
        val header = "Part 21 instances (${emitted.instances.size})"
        val body =
            emitted.instances.take(MAX_ENTITY_LINES).map { instance ->
                "  #${instance.id}   ${instance.entityName}"
            }
        val truncation =
            if (emitted.instances.size > MAX_ENTITY_LINES) {
                listOf("  ... and ${emitted.instances.size - MAX_ENTITY_LINES} more")
            } else {
                emptyList()
            }
        return listOf(header) + body + truncation
    }

    /** `--with-step`'s addition: the full rendered Part-21 text, or a one-line explanation if
     *  [Part21Writer.write] itself fails for this model (see [RenderCommand]'s KDoc on why that
     *  must never abort the whole preview). */
    private fun part21TextLines(model: KStepModel): List<String> {
        val text =
            try {
                Part21Writer.write(model.header, model.roots)
            } catch (e: RuntimeException) {
                val detail = e.message ?: e::class.simpleName ?: "unknown error"
                return listOf("Part 21 text", "  unavailable: ${redactPaths(detail)}")
            }
        return listOf("Part 21 text") + text.lines().map { "  $it" }
    }
}
