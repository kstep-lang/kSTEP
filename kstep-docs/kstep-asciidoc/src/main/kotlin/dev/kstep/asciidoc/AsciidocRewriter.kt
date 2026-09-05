package dev.kstep.asciidoc

import dev.kstep.preview.PreviewContentKind
import dev.kstep.preview.PreviewDefaults
import dev.kstep.preview.PreviewEncodingException
import dev.kstep.preview.PreviewOutcome
import dev.kstep.preview.PreviewRenderer
import dev.kstep.preview.PreviewRequest
import dev.kstep.preview.PreviewSource
import dev.kstep.preview.RenderFormat
import dev.kstep.render.text.TextCardRenderer
import dev.kstep.script.KStepScriptOutcome
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Abort-vs-degrade policy for a script that fails to compile/validate/run -- see
 *  docs/adr/ADR-0019-kstep-asciidoc.adoc's Decision 3. Deliberately distinct from the
 *  geometry-unavailable Pflicht-Fallback, which is governed by [AsciidocOptions.requireGeometry]
 *  instead (an "Umgebung" failure, not an "Autoren-Fehler"). */
enum class OnErrorPolicy { FAIL, CARD }

data class AsciidocOptions(
    val defaultFormat: RenderFormat = RenderFormat.SVG,
    val width: Int = PreviewDefaults.WIDTH_PX,
    val height: Int = PreviewDefaults.HEIGHT_PX,
    val requireGeometry: Boolean = false,
    val onError: OnErrorPolicy = OnErrorPolicy.FAIL,
)

data class AsciidocDocument(
    val text: String,
    /** Directory of the SOURCE `.adoc` -- macro paths resolve against this. */
    val sourceDir: Path,
    /** Containment root a macro path must not escape (`--input-dir` in tree mode, [sourceDir] in
     *  single-file mode). */
    val inputRoot: Path,
    /** Basename without `.adoc`, used for the default image naming scheme. */
    val baseName: String,
)

data class RenderedImage(
    /** Forward-slash separated, relative to the OUTPUT `.adoc`'s directory. */
    val relativePath: String,
    val bytes: ByteArray,
)

sealed interface RewriteResult {
    class Rendered(
        val text: String,
        val images: List<RenderedImage>,
        val warnings: List<String>,
    ) : RewriteResult

    data class Failed(
        val line: Int,
        val message: String,
    ) : RewriteResult
}

/**
 * Scan -> render each `kstep` block -> reassemble. The orchestration layer between
 * [AsciidocScanner] (pure text -> segments) and [AsciidocProcessor] (file/tree I/O) -- see
 * docs/adr/ADR-0019-kstep-asciidoc.adoc.
 *
 * Partial-output guarantee: every block of [document] is rendered into memory before anything is
 * returned. Under [OnErrorPolicy.FAIL] (the default), the first script failure aborts the WHOLE
 * document -- [AsciidocProcessor] then writes neither the rewritten `.adoc` nor any of its
 * images. Under [OnErrorPolicy.CARD], a failing script instead becomes a rendered error card and
 * processing continues.
 */
class AsciidocRewriter(
    private val options: AsciidocOptions,
) {
    fun process(document: AsciidocDocument): RewriteResult {
        val scanResult = AsciidocScanner.scan(document.text)
        val parsed =
            when (scanResult) {
                is ScanResult.Failed -> return RewriteResult.Failed(scanResult.line, scanResult.message)
                is ScanResult.Parsed -> scanResult
            }

        val outputLines = mutableListOf<String>()
        val images = mutableListOf<RenderedImage>()
        val warnings = mutableListOf<String>()
        val usedNames = mutableSetOf<String>()
        var blockIndex = 0

        for (segment in parsed.segments) {
            when (segment) {
                is AdocSegment.Text -> outputLines += segment.lines
                is AdocSegment.KStepBlock -> {
                    blockIndex++
                    val rendered = renderBlock(document, segment, blockIndex, segment.imagesDir)
                    when (rendered) {
                        is BlockResult.Failure -> return RewriteResult.Failed(segment.startLine, rendered.message)
                        is BlockResult.Success -> {
                            if (!usedNames.add(rendered.image.relativePath)) {
                                return RewriteResult.Failed(
                                    segment.startLine,
                                    "image name collision: '${rendered.image.relativePath}' is produced by more " +
                                        "than one kstep block in this document",
                                )
                            }
                            images += rendered.image
                            warnings += rendered.warnings
                            outputLines += rendered.macroLines
                        }
                    }
                }
            }
        }

        return RewriteResult.Rendered(outputLines.joinToString("\n"), images, warnings)
    }

    private sealed interface BlockResult {
        data class Success(
            val image: RenderedImage,
            val macroLines: List<String>,
            val warnings: List<String>,
        ) : BlockResult

        data class Failure(
            val message: String,
        ) : BlockResult
    }

    private fun renderBlock(
        document: AsciidocDocument,
        block: AdocSegment.KStepBlock,
        blockIndex: Int,
        imagesDir: String?,
    ): BlockResult {
        val displayName = "${document.baseName}-block$blockIndex.kstep.kts"
        val source =
            when (val ref = block.script) {
                is ScriptRef.Inline -> PreviewSource.InlineScript(ref.code, displayName)
                is ScriptRef.External -> {
                    val resolved =
                        AsciidocPaths.resolveScriptPath(document.sourceDir, document.inputRoot, ref.rawPath)
                            ?: return BlockResult.Failure(
                                "macro script path '${ref.rawPath}' escapes the allowed input directory",
                            )
                    if (!Files.isRegularFile(resolved)) {
                        return BlockResult.Failure("macro script file not found: '${ref.rawPath}'")
                    }
                    val code =
                        try {
                            Files.readString(resolved)
                        } catch (e: IOException) {
                            return BlockResult.Failure("failed to read '${ref.rawPath}': ${e.message}")
                        }
                    PreviewSource.InlineScript(code, resolved.fileName.toString())
                }
            }

        val format = block.attributes.format ?: options.defaultFormat
        val width = block.attributes.width ?: options.width
        val height = block.attributes.height ?: options.height

        val request =
            PreviewRequest(
                source = source,
                format = format,
                width = width,
                height = height,
                withStep = false,
                outPathHint = null,
            )

        val bytes: ByteArray
        var warnings: List<String> = emptyList()
        // PreviewRenderer.render (PNG-format cards it renders internally) and encodeCard below
        // (the PNG error-card path this class renders itself) both call ImageIO.write and both
        // throw PreviewEncodingException if the JVM has no registered PNG ImageWriter -- see that
        // class's own KDoc. Caught here, once, for both callers, so a kstep block failure never
        // escapes as a raw RuntimeException/stack trace but instead becomes an ordinary
        // BlockResult.Failure -> RewriteResult.Failed, exactly like every other per-block failure
        // this function reports.
        try {
            when (val outcome = PreviewRenderer.render(request)) {
                is PreviewOutcome.InvalidRequest -> return BlockResult.Failure(outcome.message)
                is PreviewOutcome.ScriptFailed -> {
                    if (options.onError == OnErrorPolicy.FAIL) {
                        return BlockResult.Failure(scriptFailureMessage(outcome.outcome))
                    }
                    bytes = encodeCard(scriptFailureCardLines(outcome.outcome), format, width, height)
                }
                is PreviewOutcome.Rendered -> {
                    if (outcome.contentKind == PreviewContentKind.NOTICE && options.requireGeometry) {
                        return BlockResult.Failure(
                            "geometry preview unavailable (${outcome.fallbackReason}) and --require-geometry is set",
                        )
                    }
                    bytes = outcome.bytes
                    warnings = outcome.warnings
                }
            }
        } catch (e: PreviewEncodingException) {
            return BlockResult.Failure("image encoding failed: ${e.message}")
        }

        val baseName = block.attributes.target ?: "${document.baseName}-$blockIndex"
        val extension = if (format == RenderFormat.PNG) "png" else "svg"
        val fileName = "$baseName.$extension"
        val relativePath = if (imagesDir != null) "$imagesDir/$fileName" else fileName

        val macroLines = mutableListOf<String>()
        block.attributes.title?.let { macroLines += ".$it" }
        val altPart = block.attributes.alt?.let { "\"$it\"" } ?: ""
        macroLines += "image::$fileName[$altPart]"

        return BlockResult.Success(RenderedImage(relativePath, bytes), macroLines, warnings)
    }

    private fun scriptFailureMessage(outcome: KStepScriptOutcome): String =
        when (outcome) {
            is KStepScriptOutcome.CompilationError ->
                "Script failed to compile: " +
                    outcome.diagnostics.joinToString("; ") { d -> "[${d.severity}] ${d.message}" }
            is KStepScriptOutcome.NoModelProduced -> "Script produced no exportable model: ${outcome.message}"
            is KStepScriptOutcome.ValidationErrors ->
                "Validation failed: " +
                    outcome.violations.joinToString("; ") { "[${it.code}] ${it.entityName}: ${it.message}" }
            is KStepScriptOutcome.RuntimeError -> "Script failed (${outcome.exceptionClass}): ${outcome.message}"
            is KStepScriptOutcome.Success -> error("scriptFailureMessage must never be called with Success")
        }

    private fun scriptFailureCardLines(outcome: KStepScriptOutcome): List<String> =
        when (outcome) {
            is KStepScriptOutcome.CompilationError ->
                listOf("Script failed to compile:") +
                    outcome.diagnostics.map { d ->
                        val location = if (d.line != null) " (line ${d.line}, column ${d.column ?: "?"})" else ""
                        "  [${d.severity}]$location ${d.message}"
                    }
            is KStepScriptOutcome.NoModelProduced ->
                listOf("Script produced no exportable model:", "  ${outcome.message}")
            is KStepScriptOutcome.ValidationErrors ->
                listOf("Validation failed:") +
                    outcome.violations.map { "  [${it.code}] ${it.entityName}: ${it.message}" }
            is KStepScriptOutcome.RuntimeError ->
                listOf("Script failed (${outcome.exceptionClass}):", "  ${outcome.message}")
            is KStepScriptOutcome.Success -> error("scriptFailureCardLines must never be called with Success")
        }

    /** @throws PreviewEncodingException if no PNG `ImageWriter` is registered -- mirrors
     *  [PreviewRenderer]'s own `ImageIO.write` return-value check (see its KDoc) so this class's
     *  OWN PNG encoding path (the error card, rendered without going through [PreviewRenderer] at
     *  all) cannot silently write a 0-byte, corrupt PNG on the same failure mode. */
    private fun encodeCard(
        lines: List<String>,
        format: RenderFormat,
        width: Int,
        height: Int,
    ): ByteArray =
        if (format == RenderFormat.PNG) {
            val image = TextCardRenderer.toImage(lines, width, height)
            val out = ByteArrayOutputStream()
            val wrote = ImageIO.write(image, "png", out)
            if (!wrote) {
                throw PreviewEncodingException("no ImageIO writer available for 'png'")
            }
            out.toByteArray()
        } else {
            TextCardRenderer.toSvg(lines, width, height).toByteArray(Charsets.UTF_8)
        }
}
