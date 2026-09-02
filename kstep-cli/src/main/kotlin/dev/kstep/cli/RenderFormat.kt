package dev.kstep.cli

private const val SCRIPT_EXTENSION = ".kstep.kts"

/** `kstep render`'s output container -- see docs/adr/ADR-0011-headless-preview-rendering.adoc's
 *  "Container-Regel": the requested/resolved format is always honored, only the CONTENT painted
 *  into it varies (a real isometric render, or a text-card fallback rendered into the same
 *  container). Pure, side-effect-free -- directly testable from `kstep-tests` without a
 *  subprocess. */
enum class RenderFormat {
    AUTO,
    SVG,
    PNG,
    TEXT,
    ;

    companion object {
        /** Parses a `--format` flag value. `null` for anything unrecognized -- the caller
         *  resolves that to `ShowUsage(1)`, never a partially-filled command. */
        fun parse(value: String): RenderFormat? =
            when (value.lowercase()) {
                "auto" -> AUTO
                "svg" -> SVG
                "png" -> PNG
                "text" -> TEXT
                else -> null
            }

        /** Format implied by an explicit `--out` path's extension. `null` if the extension is
         *  not one of `.svg`/`.png`/`.txt` -- callers fall through to the next resolution step
         *  (see [RenderCommand]'s format-resolution order). */
        fun fromExtension(path: String): RenderFormat? =
            when {
                path.endsWith(".svg", ignoreCase = true) -> SVG
                path.endsWith(".png", ignoreCase = true) -> PNG
                path.endsWith(".txt", ignoreCase = true) -> TEXT
                else -> null
            }
    }
}

/**
 * Derives the default output path for `kstep render <scriptPath>` when `--out` was not given,
 * mirroring `kstep export`'s `deriveOutputPath` (`Main.kt`) but with a format-dependent
 * extension: `bracket.kstep.kts` -> `bracket.svg`/`bracket.png`/`bracket.txt`. A script path not
 * ending in the conventional `.kstep.kts` extension just gets the extension appended to its full
 * path instead, same as the export command's identical convention.
 *
 * [format] must already be resolved to a concrete container ([RenderFormat.SVG]/[RenderFormat.PNG]/
 * [RenderFormat.TEXT]) -- passing [RenderFormat.AUTO] is a programming error (this function has
 * no content to resolve `auto` against; that resolution happens earlier, see [RenderCommand]).
 */
fun deriveRenderOutputPath(
    scriptPath: String,
    format: RenderFormat,
): String {
    require(format != RenderFormat.AUTO) { "deriveRenderOutputPath requires a concrete format, got AUTO" }
    val extension =
        when (format) {
            RenderFormat.SVG -> ".svg"
            RenderFormat.PNG -> ".png"
            RenderFormat.TEXT -> ".txt"
            RenderFormat.AUTO -> error("unreachable")
        }
    val base = if (scriptPath.endsWith(SCRIPT_EXTENSION)) scriptPath.removeSuffix(SCRIPT_EXTENSION) else scriptPath
    return base + extension
}
