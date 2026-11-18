package dev.kstep.desktop.export

import java.text.Normalizer

/**
 * File names of an export (ADR-0057). Pure. A script name is attacker-influenced text (a file name of a downloaded
 * project), so everything that ends up in a suggested name is cleaned: control and bidi-override characters, path
 * separators and characters Windows does not allow are dropped; reserved Windows device names get a prefix; the length
 * is bounded on a code point boundary.
 */
object ExportNaming {
    const val MAX_STEM_CODE_POINTS: Int = 120
    const val MAX_FILE_NAME_BYTES: Int = 255
    const val FALLBACK_STEM: String = "export"

    private const val SCRIPT_SUFFIX = ".kstep.kts"
    private const val PLAIN_SUFFIX = ".kts"
    private val forbiddenChars = "/\\:*?\"<>|".toSet()
    private val bidiAndSeparators: Set<Int> =
        setOf(
            0x202A,
            0x202B,
            0x202C,
            0x202D,
            0x202E,
            0x2066,
            0x2067,
            0x2068,
            0x2069,
            0x200E,
            0x200F,
            0x061C,
            0x2028,
            0x2029,
        )
    private val reserved = Regex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$", RegexOption.IGNORE_CASE)
    private val knownExtensions = listOf(".step", ".stp", ".glb", ".stl", ".3mf")

    /** The script's name without `.kstep.kts` (else `.kts`), cleaned; [FALLBACK_STEM] if nothing is left. */
    fun stem(scriptFileName: String): String {
        val lower = scriptFileName.lowercase()
        val base =
            when {
                lower.endsWith(SCRIPT_SUFFIX) -> scriptFileName.dropLast(SCRIPT_SUFFIX.length)
                lower.endsWith(PLAIN_SUFFIX) -> scriptFileName.dropLast(PLAIN_SUFFIX.length)
                else -> scriptFileName
            }
        return finish(clean(base), "")
    }

    fun defaultFileName(
        scriptFileName: String,
        format: ExportFormat,
    ): String = finish(clean(stem(scriptFileName)), format.extension)

    /**
     * Makes [userFileName] end with the extension of [format]: a missing one is appended, another known export extension
     * is replaced, an unknown one is kept (`my.v2` becomes `my.v2.step`), so nothing is cut silently.
     */
    fun withExtension(
        userFileName: String,
        format: ExportFormat,
    ): String {
        val cleaned = clean(userFileName)
        val lower = cleaned.lowercase()
        val known = knownExtensions.firstOrNull { lower.endsWith(it) }
        val stem = if (known != null) cleaned.dropLast(known.length) else cleaned
        return finish(stem, format.extension)
    }

    private fun clean(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val out = StringBuilder()
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            val control = Character.isISOControl(cp) || cp in bidiAndSeparators
            if (control || (cp < 0x10000 && cp.toChar() in forbiddenChars)) continue
            out.appendCodePoint(cp)
        }
        return out.toString().trimStart('.', ' ').trimEnd('.', ' ')
    }

    /** Cuts the stem to the limit, adds the extension, prefixes a reserved Windows name, keeps the whole name in bytes. */
    private fun finish(
        cleanedStem: String,
        extension: String,
    ): String {
        var stem = cleanedStem.ifEmpty { FALLBACK_STEM }
        stem = cutCodePoints(stem, MAX_STEM_CODE_POINTS).trimEnd('.', ' ').ifEmpty { FALLBACK_STEM }
        var name = stem + extension
        if (reserved.matches(name)) name = "_$name"
        while (name.toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES && stem.length > 1) {
            stem = cutCodePoints(stem, stem.codePointCount(0, stem.length) - 1)
            name = (if (reserved.matches(stem + extension)) "_" else "") + stem + extension
        }
        return name
    }

    private fun cutCodePoints(
        s: String,
        max: Int,
    ): String = if (s.codePointCount(0, s.length) <= max) s else s.substring(0, s.offsetByCodePoints(0, max))
}
