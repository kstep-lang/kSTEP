package dev.kstep.asciidoc

import dev.kstep.preview.RenderFormat
import dev.kstep.render.RenderLimits

private val IMAGESDIR_PATTERN = Regex("^:imagesdir:\\s*(.*)$")
private val IMAGESDIR_UNSET_PATTERN = Regex("^:(?:imagesdir!|!imagesdir):\\s*$")
private val MARKDOWN_FENCE_OPEN = Regex("^```kstep\\s*$")
private val MARKDOWN_FENCE_CLOSE = Regex("^```\\s*$")

// ANY other markdown fence opener (bare "```", "```asciidoc", "```kotlin", ...), of THREE OR MORE
// backticks -- checked AFTER MARKDOWN_FENCE_OPEN above, so the real "```kstep" fence is never
// mistaken for a foreign one. Asciidoctor accepts markdown fences natively as listing blocks
// inside a .adoc document, and this project's own documentation teaches the kstep macro/fence
// syntax by showing it verbatim inside exactly such a fence -- see
// docs/adr/ADR-0019-kstep-asciidoc.adoc's Stolperfalle (the same rationale as FOREIGN_DELIM below,
// just for the markdown-fence spelling instead of the AsciiDoc-delimiter spelling).
//
// The opening run of backticks is captured (group 1) and its length remembered: per CommonMark,
// a fence is closed only by a later line consisting of backticks alone (no info string) whose run
// is AT LEAST as long as the opener's -- never by a SHORTER run. Getting this backwards (accepting
// any run of >=3 backticks as an opener but requiring an exact 3-backtick line to close) makes an
// intentionally-nested example such as:
//   ````
//   ```kstep
//   val x = 1
//   ```
//   ````
// close at the INNER "```" line instead of the outer "````" one, silently reopen a second,
// never-closed foreign fence at the outer closer, and swallow the rest of the document from
// there on -- see AsciidocScannerTest A35/A36 for the regression this guards against.
private val MARKDOWN_FENCE_FOREIGN_OPEN = Regex("^(`{3,}).*$")

// A candidate foreign-fence CLOSER: a line consisting of backticks only (no info string), with
// only trailing whitespace permitted after them. Whether it actually closes the currently open
// foreign fence additionally requires its backtick run (group 1) to be >= the opener's run length
// -- checked where this is matched, not in the pattern itself, since that length varies per fence.
private val MARKDOWN_FENCE_FOREIGN_CLOSE_CANDIDATE = Regex("^(`+)\\s*$")
private val KSTEP_ATTR_LINE = Regex("^\\[kstep(?:\\s*,\\s*(.*))?]$")
private val SOURCE_KSTEP_ATTR_LINE = Regex("^\\[source\\s*,\\s*kstep]$")
private val MACRO_LINE = Regex("^kstep::([^\\[]*)\\[(.*)]$")
private val HYPHEN_DELIM = Regex("^-{4,}$")

// AsciiDoc's other delimited-block markers (listing/literal/example/sidebar/literal-alt/
// passthrough/comment) -- a line matching one of these that is NOT immediately preceded by a
// recognized kstep attribute line opens a FOREIGN block, whose entire content (including
// anything that looks like a kstep construct) is passed through verbatim. See
// docs/adr/ADR-0019-kstep-asciidoc.adoc's Stolperfalle on why this is not optional: this
// project's own documentation demonstrates the kstep macro syntax inside such blocks.
//
// Three of these seven characters ('-', '=', '+') double as Asciidoctor's own two-line
// ("setext") section-title underline characters (see SETEXT_UNDERLINE_CHARS/
// isSetextTitleUnderline below) -- a run of four or more of them is therefore syntactically
// ambiguous between "opens a foreign delimited block" and "underlines the title text on the
// line above it", and callers of this pattern must resolve that ambiguity themselves before
// treating a match as a genuine opener.
private val FOREIGN_DELIM = Regex("^(-{4,}|\\.{4,}|={4,}|\\*{4,}|_{4,}|\\+{4,}|/{4,})$")

// The full set of characters Asciidoctor accepts for a two-line ("setext") section-title
// underline -- see isSetextTitleUnderline below.
private val SETEXT_UNDERLINE_CHARS = setOf('=', '-', '~', '^', '+')

private val NAMED_ATTR = Regex("^([A-Za-z][A-Za-z0-9_-]*)=(.*)$")

// Generic AsciiDoc block-attribute line ("[TIP]", "[NOTE]", "[source,kotlin]", "[[anchor]]", ...),
// block title ("(dot)Example caption"), and attribute entry ("(colon)imagesdir(colon)",
// "(colon)toc!(colon)") lines -- none of these can EVER be the title text of a genuine two-line
// setext section title, so a would-be underline candidate directly beneath one of them must never
// be treated as a setext title underline. See isSetextTitleUnderline below: without this, an
// admonition such as
//   [TIP]
//   ====
//   kstep::demo.kstep.kts[]
//   ====
// has its own "====" opener mistaken for a setext underline of the "[TIP]" line above it (both
// are within one character of each other in length, purely by coincidence), so the admonition's
// "====" never opens as a foreign delimited block and the kstep macro INSIDE the admonition gets
// rendered for real instead of being shielded verbatim -- exactly the failure mode ADR-0019's
// Stolperfalle 3 exists to prevent, reopened for this one spelling. See AsciidocScannerTest
// A41/A42.
private val BLOCK_ATTR_LINE = Regex("^\\[.*]$")
private val BLOCK_TITLE_LINE = Regex("^\\.\\S.*$")
private val ATTRIBUTE_ENTRY_LINE = Regex("^:!?[A-Za-z0-9_-]+!?:.*$")

private sealed interface AttrParseResult {
    data class Success(
        val attributes: BlockAttributes,
    ) : AttrParseResult

    data class Failure(
        val message: String,
    ) : AttrParseResult
}

/**
 * Pure, I/O-free, script-free scan of one `.adoc` document's text into a list of [AdocSegment]s
 * -- verbatim [AdocSegment.Text] passthrough plus recognized `kstep` preview blocks (the three
 * accepted forms: a markdown fence, an AsciiDoc delimited block with a `[kstep]`/`[source,kstep]`
 * attribute line, or a `kstep::path[]` block macro -- see
 * docs/adr/ADR-0019-kstep-asciidoc.adoc's syntax contract).
 *
 * Never executes a script, never touches the filesystem, never resolves a macro path against a
 * real directory -- [AsciidocRewriter]/[AsciidocProcessor] do that once a concrete document
 * location is known. Deliberately strict: an unrecognized block attribute, an out-of-range
 * `width=`/`height=`, an invalid `target=`, or a malformed `:imagesdir:` value all fail the WHOLE
 * scan rather than being silently ignored.
 */
object AsciidocScanner {
    fun scan(text: String): ScanResult {
        if (text.toByteArray(Charsets.UTF_8).size.toLong() > AsciidocLimits.MAX_ADOC_BYTES) {
            return ScanResult.Failed(0, "document exceeds ${AsciidocLimits.MAX_ADOC_BYTES} bytes -- refusing to scan")
        }

        // Splitting on "\n" alone (never stripping a trailing "\r") and later rejoining with
        // "\n" is what makes a block-free document's Text lines byte-identical to the original
        // (a CRLF document keeps every "\r" attached to the end of its own line) -- see this
        // object's own KDoc and AsciidocScannerTest's A1/A20 cases.
        val rawLines = text.split("\n")
        val segments = mutableListOf<AdocSegment>()
        val textBuffer = mutableListOf<String>()
        val seenTargets = mutableSetOf<String>()
        var imagesDir: String? = null
        // Set instead of failing the scan outright whenever the CURRENT `:imagesdir:` value is
        // syntactically unusable (an attribute reference, or unsafe) -- see the two `return
        // ScanResult.Failed(...)` sites this replaced, below. A prose-only document that sets such
        // a value but never has a kstep block in scope while it is active must pass through
        // unchanged (Asciidoctor itself never even looks at `:imagesdir:` for a document with no
        // image macro); the error only actually matters once a kstep block would need to route its
        // rendered image through this value, so it is deferred to that point -- see
        // AsciidocScannerTest A28/A29.
        var imagesDirError: String? = null
        var blockCount = 0
        var foreignDelimiter: String? = null
        // Non-null while inside a foreign AsciiDoc delimited block (----/..../====/****/____/
        // ++++//////, see FOREIGN_DELIM below); holds the line the block OPENED at, for the
        // unclosed-at-EOF failure below -- the exact analogue of foreignFenceOpenLine just below,
        // for the delimiter spelling instead of the markdown-fence spelling. See
        // AsciidocScannerTest A37 for the regression this guards against.
        var foreignDelimiterOpenLine: Int? = null
        // Non-null while inside a foreign markdown fence (bare "```", "```asciidoc", a longer
        // "````" run, ...); holds the line the fence OPENED at (for the unclosed-at-EOF failure
        // below) together with the backtick run length that a closing line must meet or exceed --
        // see MARKDOWN_FENCE_FOREIGN_OPEN's KDoc above.
        var foreignFenceOpenLine: Int? = null
        var foreignFenceBacktickLen = 0
        var i = 0

        fun flushText() {
            if (textBuffer.isNotEmpty()) {
                segments += AdocSegment.Text(textBuffer.toList())
                textBuffer.clear()
            }
        }

        fun registerBlock(): String? {
            blockCount++
            if (blockCount > AsciidocLimits.MAX_BLOCKS_PER_DOCUMENT) {
                return "too many kstep blocks in one document (max ${AsciidocLimits.MAX_BLOCKS_PER_DOCUMENT})"
            }
            return null
        }

        fun registerTarget(
            target: String?,
            startLine: Int,
        ): String? {
            if (target != null && !seenTargets.add(target)) {
                return "duplicate target '$target' at line $startLine -- each kstep block in a " +
                    "document must have a unique target"
            }
            return null
        }

        // Whether the region a still-open foreign fence/delimiter swallowed (its opening line
        // through EOF) actually contains something that would have been recognized as a kstep
        // construct had the foreign block not swallowed it. Used ONLY by the two EOF guards below,
        // to decide whether an unbalanced foreign delimiter is a genuine authoring error (a real
        // kstep construct got swallowed and silently dropped -- AsciidocScannerTest A36/A37) or
        // just a kstep-FREE document using legacy AsciiDoc/Markdown syntax this scanner never needed
        // to understand in the first place (e.g. a two-line setext title underline, which is
        // syntactically indistinguishable from an unterminated "====="/"-----" delimited block).
        // Failing the latter would break the exact Pass-Through guarantee ADR-0019 promises for
        // documents with no kstep content at all -- see that ADR's ":imagesdir:" Stolperfalle for
        // the same reasoning applied to a different attribute, and AsciidocScannerTest A38.
        //
        // This is a plain textual re-scan of the swallowed lines against the same four patterns
        // that open a real kstep block elsewhere in this function -- deliberately NOT aware of
        // further nested foreign blocks that might re-swallow a match inside the region. That
        // makes it a conservative, false-positive-leaning check (an inner, properly closed foreign
        // block containing what LOOKS like a kstep construct still triggers Failed), never a
        // false-negative one -- the one direction that would silently drop a real kstep construct
        // is never mistaken for pass-through-safe.
        // Resolves the FOREIGN_DELIM ambiguity described in that pattern's own KDoc: a run of
        // '=', '-', or '+' that is ALSO a valid Asciidoctor setext section-title underline must
        // never be treated as a delimited-block opener, because Asciidoctor itself never would
        // -- doing so anyway is what let a document such as
        //   Section One
        //   -----------
        //
        //   kstep::demo.kstep.kts[]
        // silently swallow its own kstep macro as if it were inside an unterminated "-----"
        // listing block (no diagnostic, no rewritten image -- see AsciidocScannerTest A39), and
        // let a document that legitimately DEMONSTRATES the delimited-block syntax under a
        // setext-titled section (ADR-0019's own Stolperfalle 3 case, just with a setext title
        // instead of a "==" one) abort the ENTIRE tree at that section's title line instead of
        // processing normally (AsciidocScannerTest A40). Mirrors Asciidoctor's own rule: the line
        // immediately above must be present, non-blank, and itself ordinary title text -- not
        // another delimiter, attribute line, fence opener, or macro line -- and the underline's
        // length must be within one character of that title line's length.
        fun isSetextTitleUnderline(
            underline: String,
            lineIndex: Int,
        ): Boolean {
            if (lineIndex == 0) return false
            val previousLine = rawLines[lineIndex - 1].removeSuffix("\r")
            if (previousLine.isBlank()) return false
            if (FOREIGN_DELIM.matches(previousLine) ||
                HYPHEN_DELIM.matches(previousLine) ||
                KSTEP_ATTR_LINE.matches(previousLine) ||
                SOURCE_KSTEP_ATTR_LINE.matches(previousLine) ||
                MACRO_LINE.matches(previousLine) ||
                MARKDOWN_FENCE_OPEN.matches(previousLine) ||
                MARKDOWN_FENCE_FOREIGN_OPEN.matches(previousLine) ||
                BLOCK_ATTR_LINE.matches(previousLine) ||
                BLOCK_TITLE_LINE.matches(previousLine) ||
                ATTRIBUTE_ENTRY_LINE.matches(previousLine)
            ) {
                return false
            }
            // Asciidoctor itself only ever recognizes a two-line setext title at the very start
            // of the document or directly after a blank line -- a "title-shaped" line/underline
            // pair in the middle of a running paragraph is NOT a title to Asciidoctor, and must
            // not be one to this scanner either. Without this, a delimiter that genuinely opens a
            // foreign block mid-paragraph (e.g. "Ein Absatz.\nWie folgt:\n----------\n...") gets
            // mistaken for a setext underline purely because its length happens to match the
            // paragraph line above it, so the foreign block never opens and whatever looks like a
            // kstep construct inside it gets executed for real instead of passing through as the
            // inert paragraph text Asciidoctor itself would render it as. See AsciidocScannerTest
            // A43.
            if (lineIndex >= 2 && !rawLines[lineIndex - 2].removeSuffix("\r").isBlank()) return false
            if (underline.firstOrNull() !in SETEXT_UNDERLINE_CHARS) return false
            return kotlin.math.abs(underline.length - previousLine.length) <= 1
        }

        fun swallowedRegionHasKstepConstruct(openLine: Int): Boolean {
            for (idx in openLine until rawLines.size) {
                val candidate = rawLines[idx].removeSuffix("\r")
                if (MARKDOWN_FENCE_OPEN.matches(candidate) ||
                    KSTEP_ATTR_LINE.matches(candidate) ||
                    SOURCE_KSTEP_ATTR_LINE.matches(candidate) ||
                    MACRO_LINE.matches(candidate)
                ) {
                    return true
                }
            }
            return false
        }

        while (i < rawLines.size) {
            val raw = rawLines[i]
            val line = raw.removeSuffix("\r")
            val lineNo = i + 1

            if (foreignDelimiter != null) {
                textBuffer += raw
                if (line == foreignDelimiter) {
                    foreignDelimiter = null
                    foreignDelimiterOpenLine = null
                }
                i++
                continue
            }

            if (foreignFenceOpenLine != null) {
                textBuffer += raw
                val closeCandidate = MARKDOWN_FENCE_FOREIGN_CLOSE_CANDIDATE.matchEntire(line)
                if (closeCandidate != null && closeCandidate.groupValues[1].length >= foreignFenceBacktickLen) {
                    foreignFenceOpenLine = null
                }
                i++
                continue
            }

            if (IMAGESDIR_UNSET_PATTERN.matches(line)) {
                // AsciiDoc's canonical UNSET syntax -- `:imagesdir!:` or `:!imagesdir:` -- resets
                // the attribute exactly like a bare `:imagesdir:` does. Checked BEFORE
                // IMAGESDIR_PATTERN below (which never matches either spelling: both have a `!`
                // where that pattern's literal `:imagesdir:` expects a `:`), so a document that
                // sets, then unsets, `imagesdir` before a kstep block must not silently keep
                // routing that block's image into the earlier value -- see AsciidocScannerTest
                // A27.
                imagesDir = null
                imagesDirError = null
            } else {
                IMAGESDIR_PATTERN.matchEntire(line)?.let { m ->
                    val value = m.groupValues[1].trim()
                    if (value.isEmpty()) {
                        // `:imagesdir:` with no value is AsciiDoc's usual way to RESET the
                        // attribute back to the document's own directory -- not a malformed path.
                        // Asciidoctor itself accepts this; failing the whole scan over it would
                        // break pass-through for prose documents that happen to reset an
                        // attribute they never even use for a kstep block.
                        imagesDir = null
                        imagesDirError = null
                    } else {
                        // Asciidoctor tolerates (and normalizes away) a trailing slash, e.g.
                        // ":imagesdir: images/" -- strip it before the strict segment check so
                        // this common, harmless spelling does not fail the whole scan either.
                        val normalizedValue = value.trimEnd('/', '\\')
                        if (normalizedValue.contains('{')) {
                            // An AsciiDoc attribute reference (e.g. "{partsdir}/images") is never
                            // expanded by this scanner -- it has no attribute table, only the
                            // literal document text. Materializing it verbatim would silently
                            // create a directory literally named "{partsdir}" and route the
                            // rendered image somewhere Asciidoctor's own later conversion resolves
                            // completely differently (a broken image, with no warning). This value
                            // is common and entirely legitimate in Antora/Asciidoctor trees for
                            // documents that never reach a kstep block, though -- see the KDoc on
                            // `imagesDirError` above -- so it is recorded as unusable rather than
                            // failing the scan here; only a kstep block that would actually consume
                            // it turns this into a hard failure, at that block's own line. See
                            // AsciidocScannerTest A28/A29.
                            imagesDir = null
                            imagesDirError =
                                "the ':imagesdir:' value '$value' set at line $lineNo is invalid -- " +
                                "attribute references (e.g. '{name}') are not supported"
                        } else if (normalizedValue.isEmpty() ||
                            !AsciidocPaths.isSyntacticallySafeRelativePath(normalizedValue)
                        ) {
                            // Unlike the attribute-reference case above, this value is already
                            // suspicious on its own (an absolute path, or one escaping upward via
                            // '..') regardless of whether any kstep block ever consumes it -- an
                            // immediate hard failure here (rather than deferring, as `{...}` does)
                            // remains a defensible, deliberate choice. See AsciidocScannerTest A19.
                            return ScanResult.Failed(
                                lineNo,
                                "invalid :imagesdir: value '$value' -- must be a relative path with " +
                                    "no '..' segments",
                            )
                        } else {
                            imagesDir = normalizedValue
                            imagesDirError = null
                        }
                    }
                }
            }

            if (MARKDOWN_FENCE_OPEN.matches(line)) {
                flushText()
                val startLine = lineNo
                val codeLines = mutableListOf<String>()
                var j = i + 1
                var closed = false
                while (j < rawLines.size) {
                    val inner = rawLines[j].removeSuffix("\r")
                    if (MARKDOWN_FENCE_CLOSE.matches(inner)) {
                        closed = true
                        break
                    }
                    codeLines += inner
                    j++
                }
                if (!closed) return ScanResult.Failed(startLine, "unclosed markdown \"kstep\" code fence")
                registerBlock()?.let { return ScanResult.Failed(startLine, it) }
                imagesDirError?.let { return ScanResult.Failed(startLine, it) }
                segments +=
                    AdocSegment.KStepBlock(
                        BlockKind.MARKDOWN_FENCE,
                        startLine,
                        j + 1,
                        ScriptRef.Inline(codeLines.joinToString("\n")),
                        BlockAttributes(),
                        imagesDir,
                    )
                i = j + 1
                continue
            }

            val foreignOpenMatch = MARKDOWN_FENCE_FOREIGN_OPEN.matchEntire(line)
            if (foreignOpenMatch != null) {
                textBuffer += raw
                foreignFenceOpenLine = lineNo
                foreignFenceBacktickLen = foreignOpenMatch.groupValues[1].length
                i++
                continue
            }

            val kstepMatch = KSTEP_ATTR_LINE.matchEntire(line)
            val sourceKstepMatch = SOURCE_KSTEP_ATTR_LINE.matchEntire(line)
            if (kstepMatch != null || sourceKstepMatch != null) {
                flushText()
                val startLine = lineNo
                val attrListText = kstepMatch?.groupValues?.get(1)
                val attrsResult = parseBlockAttributes(attrListText)
                if (attrsResult is AttrParseResult.Failure) return ScanResult.Failed(startLine, attrsResult.message)
                val attrs = (attrsResult as AttrParseResult.Success).attributes

                val delimLineIdx = i + 1
                if (delimLineIdx >= rawLines.size || !HYPHEN_DELIM.matches(rawLines[delimLineIdx].removeSuffix("\r"))) {
                    return ScanResult.Failed(
                        startLine,
                        "a '[kstep]'/'[source,kstep]' attribute line must be immediately followed by " +
                            "a '----' delimiter line",
                    )
                }
                val delimiter = rawLines[delimLineIdx].removeSuffix("\r")
                var j = delimLineIdx + 1
                val codeLines = mutableListOf<String>()
                var closed = false
                while (j < rawLines.size) {
                    val inner = rawLines[j].removeSuffix("\r")
                    if (inner == delimiter) {
                        closed = true
                        break
                    }
                    codeLines += inner
                    j++
                }
                if (!closed) return ScanResult.Failed(startLine, "unclosed '----' delimited kstep block")
                registerBlock()?.let { return ScanResult.Failed(startLine, it) }
                registerTarget(attrs.target, startLine)?.let { return ScanResult.Failed(startLine, it) }
                imagesDirError?.let { return ScanResult.Failed(startLine, it) }
                segments +=
                    AdocSegment.KStepBlock(
                        BlockKind.DELIMITED,
                        startLine,
                        j + 1,
                        ScriptRef.Inline(codeLines.joinToString("\n")),
                        attrs,
                        imagesDir,
                    )
                i = j + 1
                continue
            }

            val macroMatch = MACRO_LINE.matchEntire(line)
            if (macroMatch != null) {
                flushText()
                val rawPath = macroMatch.groupValues[1]
                val attrListText = macroMatch.groupValues[2]
                val attrsResult = parseBlockAttributes(attrListText)
                if (attrsResult is AttrParseResult.Failure) return ScanResult.Failed(lineNo, attrsResult.message)
                val attrs = (attrsResult as AttrParseResult.Success).attributes
                registerBlock()?.let { return ScanResult.Failed(lineNo, it) }
                registerTarget(attrs.target, lineNo)?.let { return ScanResult.Failed(lineNo, it) }
                imagesDirError?.let { return ScanResult.Failed(lineNo, it) }
                segments +=
                    AdocSegment.KStepBlock(
                        BlockKind.MACRO,
                        lineNo,
                        lineNo,
                        ScriptRef.External(rawPath),
                        attrs,
                        imagesDir,
                    )
                i++
                continue
            }

            if (FOREIGN_DELIM.matches(line) && !isSetextTitleUnderline(line, i)) {
                textBuffer += raw
                foreignDelimiter = line
                foreignDelimiterOpenLine = lineNo
                i++
                continue
            }

            textBuffer += raw
            i++
        }

        // A foreign fence still open at EOF is a genuine authoring error ONLY if a real kstep
        // construct got swallowed as fence content instead of being scanned -- exactly like an
        // unclosed real kstep fence at :211 above. Silently returning Parsed unconditionally here
        // (as before the A36 fix) is what let an unbalanced or wrongly-nested foreign fence
        // deactivate all further block recognition for the rest of the document with no
        // diagnostic -- see this object's own KDoc ("...rather than being silently ignored") and
        // AsciidocScannerTest A36. But failing UNCONDITIONALLY (as the A36 fix first did) breaks
        // Pass-Through for a kstep-free document that never needed this scanner's attention at all
        // -- see AsciidocScannerTest A38 and swallowedRegionHasKstepConstruct's KDoc above.
        foreignFenceOpenLine?.let {
            if (swallowedRegionHasKstepConstruct(it)) {
                return ScanResult.Failed(it, "unclosed markdown code fence")
            }
        }

        // The exact same authoring error, one delimiter spelling over: a foreign AsciiDoc
        // delimited block (----/..../====/****/____/++++//////) still open at EOF only means a
        // real kstep construct was swallowed if the region actually contains one -- otherwise this
        // is legitimate legacy AsciiDoc syntax that must pass through unchanged. Before the
        // A36/A37 fix this case fell all the way through to the "Parsed" return below with no
        // diagnostic at all; failing unconditionally regressed Pass-Through instead -- see
        // AsciidocScannerTest A37 (genuine failure, kstep construct present) and A38 (Pass-Through,
        // no kstep construct present). A setext title underline never even reaches this guard any
        // more: isSetextTitleUnderline above stops it from opening foreignDelimiter in the first
        // place (an unbalanced-length setext-LIKE underline that fails that check -- e.g. one two
        // characters longer than its title line -- is genuinely ambiguous the same way Asciidoctor
        // itself treats it, and still relies on this guard exactly like any other foreign
        // delimiter).
        foreignDelimiterOpenLine?.let {
            if (swallowedRegionHasKstepConstruct(it)) {
                return ScanResult.Failed(it, "unclosed '$foreignDelimiter' block")
            }
        }

        flushText()
        return ScanResult.Parsed(segments, imagesDir)
    }

    private fun parseBlockAttributes(attrListText: String?): AttrParseResult {
        if (attrListText.isNullOrBlank()) return AttrParseResult.Success(BlockAttributes())
        val tokens =
            splitAttributeList(attrListText)
                ?: return AttrParseResult.Failure("malformed attribute list (unterminated quote): '$attrListText'")

        var target: String? = null
        var format: RenderFormat? = null
        var width: Int? = null
        var height: Int? = null
        var alt: String? = null
        var title: String? = null
        var positionalIndex = 0

        for (rawToken in tokens) {
            val token = rawToken.trim()
            if (token.isEmpty()) continue
            val namedMatch = NAMED_ATTR.matchEntire(token)
            if (namedMatch != null) {
                val key = namedMatch.groupValues[1]
                val value = stripQuotes(namedMatch.groupValues[2])
                when (key) {
                    "target" ->
                        target =
                            AsciidocPaths.validateTargetName(value)
                                ?: return AttrParseResult.Failure("invalid target '$value'")
                    "format" ->
                        format =
                            parseBlockFormat(value)
                                ?: return AttrParseResult.Failure(
                                    "unsupported format '$value' -- only 'svg'/'png' are allowed for a kstep block",
                                )
                    "width" -> {
                        val w =
                            value.toIntOrNull()
                                ?: return AttrParseResult.Failure("width must be an integer, got '$value'")
                        if (w < RenderLimits.MIN_DIMENSION_PX || w > RenderLimits.MAX_DIMENSION_PX) {
                            return AttrParseResult.Failure(
                                "width must be between ${RenderLimits.MIN_DIMENSION_PX} and " +
                                    "${RenderLimits.MAX_DIMENSION_PX}, got $w",
                            )
                        }
                        width = w
                    }
                    "height" -> {
                        val h =
                            value.toIntOrNull()
                                ?: return AttrParseResult.Failure("height must be an integer, got '$value'")
                        if (h < RenderLimits.MIN_DIMENSION_PX || h > RenderLimits.MAX_DIMENSION_PX) {
                            return AttrParseResult.Failure(
                                "height must be between ${RenderLimits.MIN_DIMENSION_PX} and " +
                                    "${RenderLimits.MAX_DIMENSION_PX}, got $h",
                            )
                        }
                        height = h
                    }
                    "alt" -> alt = validateAttributeText(value) ?: return AttrParseResult.Failure("invalid 'alt' text")
                    "title" ->
                        title =
                            validateAttributeText(value) ?: return AttrParseResult.Failure("invalid 'title' text")
                    else -> return AttrParseResult.Failure("unknown kstep block attribute '$key'")
                }
            } else {
                val value = stripQuotes(token)
                when (positionalIndex) {
                    0 ->
                        target =
                            AsciidocPaths.validateTargetName(value)
                                ?: return AttrParseResult.Failure("invalid target '$value'")
                    1 ->
                        format =
                            parseBlockFormat(value)
                                ?: return AttrParseResult.Failure(
                                    "unsupported format '$value' -- only 'svg'/'png' are allowed for a kstep block",
                                )
                    else -> return AttrParseResult.Failure("too many positional attributes: '$value'")
                }
                positionalIndex++
            }
        }

        if (width != null && height != null && width.toLong() * height.toLong() > RenderLimits.MAX_TOTAL_PIXELS) {
            return AttrParseResult.Failure("width * height must not exceed ${RenderLimits.MAX_TOTAL_PIXELS} pixels")
        }
        return AttrParseResult.Success(BlockAttributes(target, format, width, height, alt, title))
    }

    private fun splitAttributeList(text: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in text) {
            when {
                c == '"' -> {
                    inQuotes = !inQuotes
                    current.append(c)
                }
                c == ',' && !inQuotes -> {
                    tokens += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
        }
        if (inQuotes) return null
        tokens += current.toString()
        return tokens
    }

    private fun stripQuotes(value: String): String =
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value.substring(1, value.length - 1)
        } else {
            value
        }

    private fun validateAttributeText(value: String): String? {
        if (value.length > AsciidocLimits.MAX_ATTRIBUTE_TEXT_CHARS) return null
        if (value.any { it == '"' || it == ']' || it == '\n' || it.isISOControl() }) return null
        return value
    }

    private fun parseBlockFormat(value: String): RenderFormat? =
        when (value.lowercase()) {
            "svg" -> RenderFormat.SVG
            "png" -> RenderFormat.PNG
            else -> null
        }
}
