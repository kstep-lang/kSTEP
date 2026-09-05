package dev.kstep.asciidoc

import dev.kstep.preview.RenderFormat

/** How a `kstep` preview block was written in the source `.adoc`. */
enum class BlockKind { MARKDOWN_FENCE, DELIMITED, MACRO }

/** Where a [AdocSegment.KStepBlock]'s script source comes from, as written -- resolution
 *  (reading an external file, checking it stays within the input root) happens later, in
 *  [AsciidocPaths]/[AsciidocRewriter], never in [AsciidocScanner] itself. */
sealed interface ScriptRef {
    data class Inline(
        val code: String,
    ) : ScriptRef

    /** Raw, UNvalidated path text exactly as written in the document. */
    data class External(
        val rawPath: String,
    ) : ScriptRef
}

/** Attributes parsed off a `kstep` block's attribute line/macro target -- `null` for anything
 *  not explicitly given, letting [AsciidocRewriter] apply document-level defaults. */
data class BlockAttributes(
    val target: String? = null,
    val format: RenderFormat? = null,
    val width: Int? = null,
    val height: Int? = null,
    val alt: String? = null,
    val title: String? = null,
)

/** One piece of a scanned `.adoc` document -- either verbatim passthrough text, or a recognized
 *  `kstep` preview block. See [AsciidocScanner]. */
sealed interface AdocSegment {
    /** Verbatim passthrough -- reassembling every [Text] segment of a block-free document must
     *  reproduce the input byte-for-byte. */
    data class Text(
        val lines: List<String>,
    ) : AdocSegment

    data class KStepBlock(
        val kind: BlockKind,
        /** 1-based line of the block's opening construct -- every error message cites it. */
        val startLine: Int,
        val endLine: Int,
        val script: ScriptRef,
        val attributes: BlockAttributes,
        /** The `:imagesdir:` value in effect at THIS block's position in the document -- the
         *  last `:imagesdir:` line scanned strictly BEFORE this block, or `null` if none has been
         *  seen yet. Asciidoctor evaluates `imagesdir` positionally at each `image::` macro's own
         *  location, not once for the whole document -- a block appearing before the document's
         *  only `:imagesdir:` line must NOT have it applied. See [ScanResult.Parsed.imagesDir]'s
         *  KDoc for how this differs from the document-level value. */
        val imagesDir: String?,
    ) : AdocSegment
}

/** Result of [AsciidocScanner.scan]. */
sealed interface ScanResult {
    data class Parsed(
        val segments: List<AdocSegment>,
        /** The LAST `:imagesdir:` value seen anywhere in the document (or `null` if none) --
         *  purely diagnostic/document-level. Rendering must NOT apply this uniformly to every
         *  block; use each [AdocSegment.KStepBlock.imagesDir] instead, which captures the value
         *  actually in effect at that block's own position. */
        val imagesDir: String?,
    ) : ScanResult

    data class Failed(
        val line: Int,
        val message: String,
    ) : ScanResult
}
