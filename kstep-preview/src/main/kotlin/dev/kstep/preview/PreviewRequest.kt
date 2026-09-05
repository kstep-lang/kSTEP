package dev.kstep.preview

import java.io.File

object PreviewDefaults {
    const val WIDTH_PX: Int = 1024
    const val HEIGHT_PX: Int = 768
}

/**
 * Where a preview's `*.kstep.kts` source comes from. Both variants land on the SAME
 * [dev.kstep.script.KStepScriptHost.eval] overload family -- the inline variant is what
 * `kstep asciidoc`'s fenced/delimited blocks use, and it needs no temp file
 * ([dev.kstep.script.KStepScriptHost] already has a `(code, fileName)` overload).
 */
sealed interface PreviewSource {
    /** Used in error messages and as the text card's title line. */
    val displayName: String

    data class ScriptFile(
        val file: File,
    ) : PreviewSource {
        override val displayName: String get() = file.name
    }

    /**
     * [displayName] SHOULD end in `.kstep.kts` -- it becomes the script source's file name in
     * compiler diagnostics, so e.g. `bracket.adoc-block2.kstep.kts` points a doc author straight
     * at the offending block.
     */
    data class InlineScript(
        val code: String,
        override val displayName: String,
    ) : PreviewSource
}

data class PreviewRequest(
    val source: PreviewSource,
    val format: RenderFormat = RenderFormat.AUTO,
    val width: Int = PreviewDefaults.WIDTH_PX,
    val height: Int = PreviewDefaults.HEIGHT_PX,
    val withStep: Boolean = false,
    /**
     * Step (2) of ADR-0011's `--format auto` resolution order (an explicit `--out`'s
     * extension). `null` skips that step -- `kstep asciidoc` always passes a concrete
     * [format] and therefore never needs it.
     */
    val outPathHint: String? = null,
)
