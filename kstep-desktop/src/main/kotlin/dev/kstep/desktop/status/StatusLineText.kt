package dev.kstep.desktop.status

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.run.PreviewOrigin

/** What the shown picture came from (ADR-0086). */
enum class PreviewOriginLabel(
    val text: String,
) {
    SAVED("Preview: saved file"),
    UNSAVED("Preview: unsaved buffer"),
    UNSAVED_OLDER("Preview: unsaved buffer (older)"),
}

/**
 * The label for the picture that is shown, or `null` when there is none. A buffer picture is "older" as soon as the
 * editor text differs from the text it was made from.
 */
fun previewOriginLabel(
    shown: PreviewOrigin?,
    editorText: String?,
): PreviewOriginLabel? =
    when (shown) {
        null -> null
        PreviewOrigin.SavedFile -> PreviewOriginLabel.SAVED
        is PreviewOrigin.Buffer ->
            if (shown.text == editorText) PreviewOriginLabel.UNSAVED else PreviewOriginLabel.UNSAVED_OLDER
    }

/** The pure texts of the status line (ADR-0064). No Compose here: the layout lives in the ui package. */
object StatusLineText {
    const val SEPARATOR = "  ·  "
    const val NO_FILE = "No file"
    private const val DEFAULT_NAME_MAX = 32
    private const val MIN_SHORTEN_CHARS = 8
    private const val MIN_HEAD = 3
    private const val ELLIPSIS_AND_HEAD_RESERVE = 6
    private const val SCRIPT_SUFFIX = ".kstep.kts"
    private val MARKER = Regex("‹U\\+[0-9A-F]{4,6}›")

    /** The file name for the status line: escaped (never a raw control or direction character), cut in the middle. */
    fun fileLabel(
        name: String?,
        maxChars: Int = DEFAULT_NAME_MAX,
    ): String = if (name == null) NO_FILE else shorten(DisplayName.escaped(name), maxChars)

    /**
     * [text] (already escaped) with at most [maxChars] characters: the extension stays, the middle becomes `…`. Works on
     * whole units (a code point or a `‹U+202E›` marker), so neither a surrogate pair nor a marker is ever cut.
     */
    internal fun shorten(
        text: String,
        maxChars: Int,
    ): String {
        if (maxChars < MIN_SHORTEN_CHARS || text.length <= maxChars) return text
        val extension = extensionOf(text, maxChars)
        if (extension.isNotEmpty()) {
            val head = takeHead(units(text.removeSuffix(extension)), maxChars - 1 - extension.length)
            if (head.length >= MIN_HEAD) return head + "…" + extension
        }
        return middle(text, maxChars, endShare = 0.5)
    }

    /** [text] cut in the middle to at most [maxChars] characters; [endShare] is the part of the budget kept at the end. */
    internal fun middle(
        text: String,
        maxChars: Int,
        endShare: Double = 0.5,
    ): String {
        if (text.length <= maxChars || maxChars < MIN_SHORTEN_CHARS) return text
        val budget = maxChars - 1
        val endBudget = (budget * endShare).toInt()
        val parts = units(text)
        val head = takeHead(parts, budget - endBudget)
        val tail = takeHead(parts.asReversed(), endBudget)
        return head + "…" + tail.reversedUnits()
    }

    private fun extensionOf(
        text: String,
        maxChars: Int,
    ): String {
        val limit = maxChars - ELLIPSIS_AND_HEAD_RESERVE
        val candidate =
            if (text.endsWith(SCRIPT_SUFFIX) && text.length > SCRIPT_SUFFIX.length) {
                SCRIPT_SUFFIX
            } else {
                val dot = text.lastIndexOf('.')
                if (dot > 0) text.substring(dot) else ""
            }
        return if (candidate.length <= limit) candidate else ""
    }

    /** The units of [text]: a marker is one unit, otherwise one code point. */
    private fun units(text: String): List<String> {
        val result = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val marker = MARKER.matchAt(text, i)
            val end = if (marker != null) marker.range.last + 1 else i + Character.charCount(text.codePointAt(i))
            result += text.substring(i, end)
            i = end
        }
        return result
    }

    /** The longest run of leading [parts] that fits into [budget] characters. */
    private fun takeHead(
        parts: List<String>,
        budget: Int,
    ): String {
        val out = StringBuilder()
        for (part in parts) {
            if (out.length + part.length > budget) break
            out.append(part)
        }
        return out.toString()
    }

    private fun String.reversedUnits(): String = units(this).asReversed().joinToString("")
}
