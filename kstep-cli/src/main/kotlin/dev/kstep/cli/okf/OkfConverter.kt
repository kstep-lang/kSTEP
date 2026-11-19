package dev.kstep.cli.okf

/**
 * Pure text conversion between a `*.kstep.kts` script and a Markdown note holding it in a fenced `kstep` block
 * (ADR-0060). No I/O and, above all, no script evaluation: scripts are only ever treated as text.
 *
 * Round trip: kts -> okf -> kts is lossless (up to one added trailing line break); okf -> kts -> okf is lossy (front
 * matter, prose and all other blocks are dropped).
 */
object OkfConverter {
    const val KSTEP_TAG = "kstep"
    const val SCRIPT_SUFFIX = ".kstep.kts"
    const val MAX_STEM_CHARS = 120

    /** Title budget: every code point shows as at most an 8 character marker, which keeps the header under its limit. */
    const val MAX_TITLE_CODE_POINTS = 100
    const val MAX_TAG_CODE_POINTS = 64
    private const val MAX_TITLE_CHARS = MAX_TITLE_CODE_POINTS
    private const val MAX_RUN_FOR_WRAP = OkfLimits.MAX_FENCE_RUN - 1

    sealed interface WrapResult {
        data class Ok(
            val note: String,
        ) : WrapResult

        data class Rejected(
            val reason: String,
        ) : WrapResult
    }

    sealed interface MetadataCheck {
        data class Ok(
            val title: String?,
            val tags: List<String>,
        ) : MetadataCheck

        data class Rejected(
            val reason: String,
        ) : MetadataCheck
    }

    data class ExtractedScript(
        val blockIndex: Int,
        val fileName: String,
        val source: String,
        val openLine: Int,
    )

    /** Why an extraction found nothing to write; a folder run skips the first two instead of failing (ADR-0061). */
    enum class RejectKind { NO_BLOCK, ALL_EMPTY, OTHER }

    sealed interface ExtractResult {
        data class Ok(
            val scripts: List<ExtractedScript>,
            val skippedEmptyLines: List<Int>,
        ) : ExtractResult

        data class Rejected(
            val reason: String,
            val line: Int?,
            val kind: RejectKind = RejectKind.OTHER,
        ) : ExtractResult
    }

    /**
     * Validates the user-chosen note metadata of `kstep convert --to okf` (ADR-0061). [title] null keeps the default
     * title; [extraTags] come after the mandatory `kstep` tag, exact duplicates are dropped silently. Nothing that could
     * break out of the YAML header or spoof the terminal (control, bidi, line separators, unpaired surrogates) passes.
     */
    fun checkMetadata(
        title: String?,
        extraTags: List<String>,
    ): MetadataCheck {
        var cleanTitle: String? = null
        if (title != null) {
            badCodePoint(title)?.let { return MetadataCheck.Rejected("the title contains $it") }
            val t = title.trim(' ')
            if (t.isEmpty() || t.isBlank()) return MetadataCheck.Rejected("the title is empty")
            if (t.codePointCount(0, t.length) > MAX_TITLE_CODE_POINTS) {
                return MetadataCheck.Rejected("the title is longer than $MAX_TITLE_CODE_POINTS characters")
            }
            cleanTitle = t
        }
        val tags = LinkedHashSet<String>()
        tags += KSTEP_TAG
        for (tag in extraTags) {
            if (tag.isEmpty()) return MetadataCheck.Rejected("a tag is empty")
            if (tag.startsWith("#")) return MetadataCheck.Rejected("write the tag without '#'")
            badCodePoint(tag)?.let { return MetadataCheck.Rejected("a tag contains $it") }
            if (tag.any { Character.isWhitespace(it) || Character.isSpaceChar(it) }) {
                return MetadataCheck.Rejected("a tag must not contain whitespace")
            }
            if (tag.codePointCount(0, tag.length) > MAX_TAG_CODE_POINTS) {
                return MetadataCheck.Rejected("a tag is longer than $MAX_TAG_CODE_POINTS characters")
            }
            tags += tag
        }
        if (tags.size > OkfLimits.FM_MAX_TAGS) {
            return MetadataCheck.Rejected("there are more than ${OkfLimits.FM_MAX_TAGS} tags")
        }
        val flowLength = tags.joinToString(", ") { yamlTag(it) }.length + 2
        if (flowLength > OkfLimits.FM_MAX_SCALAR * 4) return MetadataCheck.Rejected("the tags are too long together")
        return MetadataCheck.Ok(cleanTitle, tags.toList())
    }

    private fun badCodePoint(s: String): String? {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (DisplayName.isSuspicious(cp) || cp == 0xFEFF || cp in 0xD800..0xDFFF) {
                return "a character that is not allowed (U+${cp.toString(16).uppercase().padStart(4, '0')})"
            }
            i += Character.charCount(cp)
        }
        return null
    }

    fun wrapAsOkf(
        script: String,
        title: String,
        tags: List<String> = listOf(KSTEP_TAG),
    ): WrapResult {
        if (script.isBlank()) return WrapResult.Rejected("the script is empty")
        if (utf8Size(script) > OkfLimits.MAX_BLOCK_BYTES) {
            return WrapResult.Rejected("the script is larger than ${OkfLimits.MAX_BLOCK_BYTES / 1024} KiB")
        }
        val fence =
            fenceFor(script)
                ?: return WrapResult.Rejected("the script has fence lines that cannot be wrapped")
        val eol = lineEnding(script)
        val body = stripOneTerminator(script)
        val tagList = tags.joinToString(", ") { yamlTag(it) }
        val note =
            buildString {
                append("---").append(eol)
                append("title: ").append(yamlQuoted(title)).append(eol)
                append("tags: [").append(tagList).append(']').append(eol)
                append("---").append(eol)
                append(eol)
                append("# ").append(title).append(eol)
                append(eol)
                append(fence).append(KSTEP_TAG).append(eol)
                append(body).append(lastTerminator(script) ?: eol)
                append(fence).append(eol)
            }
        // Defensive: the note must read back through the workspace's own parsers exactly as intended.
        val fm = FrontmatterParser.parse(note)
        val ex = MarkdownBlockExtractor.extract(note, fm.bodyStartLine)
        if (fm.ignored ||
            fm.title != title ||
            fm.tags != tags ||
            ex.blocks.size != 1 ||
            ex.blocks[0].state != BlockState.OK
        ) {
            return WrapResult.Rejected("the script cannot be wrapped into a readable note")
        }
        return WrapResult.Ok(note)
    }

    fun extractScripts(
        note: String,
        noteStem: String,
    ): ExtractResult {
        val fm = FrontmatterParser.parse(note)
        val ex = MarkdownBlockExtractor.extract(note, fm.bodyStartLine)
        if (ex.tooManyBlocks) {
            return ExtractResult.Rejected("the note has more than ${OkfLimits.MAX_BLOCKS} kstep blocks", null)
        }
        ex.blocks.firstOrNull { it.state == BlockState.UNCLOSED }?.let {
            return ExtractResult.Rejected("the kstep block opened in line ${it.openLine} is never closed", it.openLine)
        }
        ex.blocks.firstOrNull { it.state == BlockState.TOO_LARGE }?.let {
            return ExtractResult.Rejected(
                "the kstep block in line ${it.openLine} is larger than ${OkfLimits.MAX_BLOCK_BYTES / 1024} KiB",
                it.openLine,
            )
        }
        if (ex.blocks.isEmpty()) return ExtractResult.Rejected("no kstep block found", null, RejectKind.NO_BLOCK)
        val stem = sanitizeStem(noteStem)
        val single = ex.blocks.size == 1
        val scripts = ArrayList<ExtractedScript>()
        val skipped = ArrayList<Int>()
        for (b in ex.blocks) {
            if (b.state == BlockState.EMPTY) {
                skipped += b.openLine
                continue
            }
            val content = MarkdownBlockExtractor.content(note, b)
            val name = if (single) "$stem$SCRIPT_SUFFIX" else "$stem-${b.index + 1}$SCRIPT_SUFFIX"
            val closeStart = b.closeLineRange?.first
            val terminator =
                (if (closeStart != null && closeStart >= b.contentEnd) note.substring(b.contentEnd, closeStart) else "")
                    .ifEmpty { lineEnding(content) }
            scripts += ExtractedScript(b.index, name, content + terminator, b.openLine)
        }
        if (scripts.isEmpty()) return ExtractResult.Rejected("every kstep block is empty", null, RejectKind.ALL_EMPTY)
        return ExtractResult.Ok(scripts, skipped)
    }

    /** Reduces a name to `[A-Za-z0-9._-]`; never empty, never hidden, never a Windows device name. */
    fun sanitizeStem(raw: String): String {
        val last = raw.substring(maxOf(raw.lastIndexOf('/'), raw.lastIndexOf('\\')) + 1)
        val sb = StringBuilder()
        var i = 0
        while (i < last.length && sb.length < MAX_STEM_CHARS * 4) {
            val cp = last.codePointAt(i)
            val ok = cp < 0x80 && (cp.toChar().isLetterOrDigit() || cp == '.'.code || cp == '_'.code || cp == '-'.code)
            sb.append(if (ok) cp.toChar() else '_')
            i += Character.charCount(cp)
        }
        var s = trimEdges(sb.toString())
        if (s.length > MAX_STEM_CHARS) s = trimEdges(s.substring(0, MAX_STEM_CHARS))
        if (s.isEmpty()) s = "block"
        if (isWindowsReserved(s)) s = "_$s"
        return s
    }

    fun titleFromStem(rawStem: String): String = DisplayName.escaped(rawStem, MAX_TITLE_CHARS)

    internal fun fenceFor(script: String): String? {
        val bt = longestRun(script, '`')
        if (bt <= MAX_RUN_FOR_WRAP) return "`".repeat(maxOf(3, bt + 1))
        val tl = longestRun(script, '~')
        if (tl <= MAX_RUN_FOR_WRAP) return "~".repeat(maxOf(3, tl + 1))
        return null
    }

    internal fun lineEnding(text: String): String {
        for (k in text.indices) {
            val c = text[k]
            if (c == '\r') return if (k + 1 < text.length && text[k + 1] == '\n') "\r\n" else "\r"
            if (c == '\n') return "\n"
        }
        return "\n"
    }

    internal fun yamlQuoted(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    internal fun yamlTag(tag: String): String =
        if (tag.all {
                it.isLetterOrDigit() || it == '-' || it == '_'
            }
        ) {
            tag
        } else {
            yamlQuoted(tag)
        }

    /** The longest run of [c] at the start of any line (at most 3 spaces of indent, a leading BOM ignored). */
    private fun longestRun(
        text: String,
        c: Char,
    ): Int {
        var best = 0
        var i = 0
        val n = text.length
        var first = true
        while (i <= n) {
            var p = i
            if (first && p < n && text[p] == '\uFEFF') p++
            first = false
            var spaces = 0
            while (p < n && text[p] == ' ' && spaces <= 3) {
                p++
                spaces++
            }
            if (spaces <= 3) {
                var q = p
                while (q < n && text[q] == c) q++
                if (q - p > best) best = q - p
            }
            while (p < n && text[p] != '\n' && text[p] != '\r') p++
            if (p >= n) break
            i = if (text[p] == '\r' && p + 1 < n && text[p + 1] == '\n') p + 2 else p + 1
        }
        return best
    }

    private fun lastTerminator(s: String): String? =
        when {
            s.endsWith("\r\n") -> "\r\n"
            s.endsWith("\n") -> "\n"
            s.endsWith("\r") -> "\r"
            else -> null
        }

    private fun stripOneTerminator(s: String): String =
        when {
            s.endsWith("\r\n") -> s.substring(0, s.length - 2)
            s.endsWith("\n") || s.endsWith("\r") -> s.substring(0, s.length - 1)
            else -> s
        }

    private fun trimEdges(s: String): String = s.trim { it == '.' || it == '_' || it == '-' }

    private fun isWindowsReserved(s: String): Boolean {
        val base = s.substringBefore('.').uppercase()
        if (base == "CON" || base == "PRN" || base == "AUX" || base == "NUL") return true
        return base.length == 4 && (base.startsWith("COM") || base.startsWith("LPT")) && base[3] in '1'..'9'
    }

    internal fun utf8Size(s: String): Long {
        var bytes = 0L
        var k = 0
        while (k < s.length) {
            val cp = s.codePointAt(k)
            bytes +=
                when {
                    cp < 0x80 -> 1
                    cp < 0x800 -> 2
                    cp < 0x10000 -> 3
                    else -> 4
                }
            k += Character.charCount(cp)
        }
        return bytes
    }
}
