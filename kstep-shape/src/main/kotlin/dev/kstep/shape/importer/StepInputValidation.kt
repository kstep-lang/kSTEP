package dev.kstep.shape.importer

import dev.kstep.geometry.StepImportLimits
import dev.kstep.geometry.sanitizeImportText
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Error classes of `kstep import` (ADR-0030). [code] is the stable `KSTEP-I-*` identifier (the
 * geometry-unavailable case reuses the existing `KSTEP-S-007`), [jsonKind] the `errorKind` of the
 * JSON error document.
 */
enum class StepImportErrorKind(
    val code: String,
    val jsonKind: String,
) {
    INVALID_PATH("KSTEP-I-001", "invalid_path"),
    FILE_NOT_FOUND("KSTEP-I-002", "file_not_found"),
    SYMLINK_REJECTED("KSTEP-I-003", "symlink_rejected"),
    NOT_A_REGULAR_FILE("KSTEP-I-004", "not_a_regular_file"),
    EMPTY_FILE("KSTEP-I-005", "empty_file"),
    FILE_TOO_LARGE("KSTEP-I-006", "file_too_large"),
    NOT_STEP("KSTEP-I-007", "not_step"),
    EXTERNAL_REFERENCE_REJECTED("KSTEP-I-008", "external_reference"),
    READ_FAILED("KSTEP-I-009", "read_failed"),
    LIMIT_EXCEEDED("KSTEP-I-010", "limit_exceeded"),
    NO_CONTENT("KSTEP-I-011", "no_content"),
    GEOMETRY_UNAVAILABLE("KSTEP-S-007", "geometry_unavailable"),
    IO_ERROR("KSTEP-I-012", "io_error"),
}

/** Thrown by [StepInputValidation]; carries the [kind] that maps to the CLI error document. */
class StepInputException(
    val kind: StepImportErrorKind,
    message: String,
) : Exception(message)

/**
 * What the OCCT-free pre-scan learned from the file text.
 *
 * @property ignoredCategories counts of entity instances in categories kSTEP does not import
 *   (`colours`, `layers`, `pmi`); only non-zero entries are present.
 */
data class StepPrescan(
    val schemas: List<String>,
    val headerFileName: String?,
    val originatingSystem: String?,
    val ignoredCategories: Map<String, Int>,
)

/**
 * OCCT-free input hardening for `kstep import` (ADR-0030): path and file checks before any byte is
 * read, and a keyword pre-scan that rejects extern-file references before OCCT ever sees the text.
 * The CONTENT (bytes) is what travels on to OCCT -- OCCT never opens the file itself, so there is no
 * check-to-use gap.
 */
object StepInputValidation {
    private const val HEADER_WINDOW = 64 * 1024
    private const val MAX_TEXT_CHARS = 256

    /**
     * Keywords whose presence means the file references other files. `DOCUMENT_FILE` and
     * `APPLIED_DOCUMENT_REFERENCE` are the entities OCCT's `STEPConstruct_ExternRefs` loads extern
     * references from (`StepBasic_DocumentFile`, `StepAP214_AppliedDocumentReference`, see its public
     * header); `PRODUCT_DEFINITION_WITH_ASSOCIATED_DOCUMENTS` is the product-side document link.
     * This textual scan is only a fast first filter: the authoritative check is the typed one on the
     * parsed model in the native reader (it cannot be evaded by entity spelling).
     */
    private val EXTERNAL_REFERENCE_KEYWORDS =
        setOf(
            "DOCUMENT_FILE",
            "APPLIED_DOCUMENT_REFERENCE",
            "PRODUCT_DEFINITION_WITH_ASSOCIATED_DOCUMENTS",
            // AP203/AP214 short names OCCT also recognises (RWStepAP214_ReadWriteModule), and the
            // base DOCUMENT entity whose name STEPConstruct_ExternRefs treats as a file name.
            "PDWAD",
            "APDCRF",
            "DCMFL",
            "DCMNT",
            "DOCUMENT",
        )

    /**
     * Reads [rawPath] after checking, in order: path syntax, existence (no symlink following), not a
     * symbolic link (last path component only -- parent directories may be links, e.g. macOS `/tmp`),
     * a regular file (no directory, FIFO or device), non-empty, within [maxBytes]. The file is opened
     * with `NOFOLLOW_LINKS` (`O_NOFOLLOW`, closing the symlink check-to-open gap) and read bounded to
     * `maxBytes + 1` so a file that grew after the size check is still caught.
     *
     * Messages name only the path as given by the caller, never a resolved real path.
     */
    fun readValidated(
        rawPath: String,
        maxBytes: Long = StepImportLimits.MAX_FILE_BYTES,
    ): ByteArray {
        val path: Path =
            try {
                Path.of(rawPath)
            } catch (e: InvalidPathException) {
                throw StepInputException(StepImportErrorKind.INVALID_PATH, "invalid path: ${sanitize(rawPath)}")
            }
        val noFollow = LinkOption.NOFOLLOW_LINKS
        try {
            if (!Files.exists(path, noFollow)) {
                throw StepInputException(StepImportErrorKind.FILE_NOT_FOUND, "file not found: ${sanitize(rawPath)}")
            }
            if (Files.isSymbolicLink(path)) {
                throw StepInputException(
                    StepImportErrorKind.SYMLINK_REJECTED,
                    "refusing to follow a symbolic link: ${sanitize(rawPath)}",
                )
            }
            if (!Files.isRegularFile(path, noFollow)) {
                throw StepInputException(
                    StepImportErrorKind.NOT_A_REGULAR_FILE,
                    "not a regular file: ${sanitize(rawPath)}",
                )
            }
            val size = Files.readAttributes(path, BasicFileAttributes::class.java, noFollow).size()
            if (size ==
                0L
            ) {
                throw StepInputException(StepImportErrorKind.EMPTY_FILE, "file is empty: ${sanitize(rawPath)}")
            }
            if (size > maxBytes) {
                throw StepInputException(
                    StepImportErrorKind.FILE_TOO_LARGE,
                    "file is larger than $maxBytes bytes: ${sanitize(rawPath)}",
                )
            }
            val bytes =
                Files.newInputStream(path, noFollow).use { input ->
                    input.readNBytes(
                        maxBytes.toInt().coerceAtLeast(0) + 1,
                    )
                }
            if (bytes.size > maxBytes) {
                throw StepInputException(
                    StepImportErrorKind.FILE_TOO_LARGE,
                    "file is larger than $maxBytes bytes: ${sanitize(rawPath)}",
                )
            }
            if (bytes.isEmpty()) {
                throw StepInputException(
                    StepImportErrorKind.EMPTY_FILE,
                    "file is empty: ${sanitize(rawPath)}",
                )
            }
            return bytes
        } catch (e: IOException) {
            throw StepInputException(StepImportErrorKind.IO_ERROR, "cannot read file: ${sanitize(rawPath)}")
        } catch (e: UnsupportedOperationException) {
            throw StepInputException(StepImportErrorKind.IO_ERROR, "cannot read file: ${sanitize(rawPath)}")
        }
    }

    /**
     * Light-weight scan of [content]: must start with `ISO-10303-21;` (an optional UTF-8 BOM and leading
     * white space are accepted), then every keyword outside string literals and comments is counted.
     *
     * @throws StepInputException [StepImportErrorKind.NOT_STEP] or
     *   [StepImportErrorKind.EXTERNAL_REFERENCE_REJECTED].
     */
    fun prescan(content: ByteArray): StepPrescan {
        var start = 0
        if (content.size >= 3 &&
            content[0] == 0xEF.toByte() &&
            content[1] == 0xBB.toByte() &&
            content[2] == 0xBF.toByte()
        ) {
            start = 3
        }
        while (start < content.size && isWhitespace(content[start])) start++
        val magic = "ISO-10303-21;"
        val looksStep =
            content.size - start >= magic.length &&
                String(content, start, magic.length, StandardCharsets.ISO_8859_1).equals(magic, ignoreCase = true)
        if (!looksStep) throw StepInputException(StepImportErrorKind.NOT_STEP, notStepMessage(content, start))

        var colours = 0
        var layers = 0
        var pmi = 0
        var i = start
        val n = content.size
        val sb = StringBuilder()
        while (i < n) {
            val c = content[i].toInt() and 0xFF
            when {
                c == '\''.code -> {
                    i++
                    while (i < n && content[i] != '\''.code.toByte()) i++
                    i++
                }
                c == '/'.code && i + 1 < n && content[i + 1] == '*'.code.toByte() -> {
                    i += 2
                    while (i + 1 < n && !(content[i] == '*'.code.toByte() && content[i + 1] == '/'.code.toByte())) i++
                    i += 2
                }
                isKeywordStart(c) -> {
                    sb.setLength(0)
                    while (i < n && isKeywordPart(content[i].toInt() and 0xFF)) {
                        if (sb.length < 128) sb.append(Char((content[i].toInt() and 0xFF)).uppercaseChar())
                        i++
                    }
                    val keyword = sb.toString()
                    if (keyword in EXTERNAL_REFERENCE_KEYWORDS) {
                        throw StepInputException(
                            StepImportErrorKind.EXTERNAL_REFERENCE_REJECTED,
                            "the file references external documents ($keyword); external references are not followed",
                        )
                    }
                    when {
                        keyword == "STYLED_ITEM" || keyword == "OVER_RIDING_STYLED_ITEM" -> colours++
                        keyword == "PRESENTATION_LAYER_ASSIGNMENT" -> layers++
                        keyword.startsWith("GEOMETRIC_TOLERANCE") ||
                            keyword.startsWith("DIMENSIONAL_") ||
                            keyword.startsWith("DATUM") -> pmi++
                    }
                }
                else -> i++
            }
        }
        val header = parseHeader(content, start)
        val ignored = linkedMapOf<String, Int>()
        if (colours > 0) ignored["colours"] = colours
        if (layers > 0) ignored["layers"] = layers
        if (pmi > 0) ignored["pmi"] = pmi
        return StepPrescan(header.schemas, header.fileName, header.originatingSystem, ignored)
    }

    private class HeaderInfo(
        val schemas: List<String>,
        val fileName: String?,
        val originatingSystem: String?,
    )

    private fun parseHeader(
        content: ByteArray,
        start: Int,
    ): HeaderInfo {
        val window = String(content, start, minOf(content.size - start, HEADER_WINDOW), StandardCharsets.ISO_8859_1)
        val schemaArgs = headerArguments(window, "FILE_SCHEMA")
        val schemas =
            schemaArgs
                ?.firstOrNull()
                ?.let { stringLiterals(it) }
                .orEmpty()
                .map { sanitize(it) }
        val nameArgs = headerArguments(window, "FILE_NAME")
        val fileName = nameArgs?.getOrNull(0)?.let { stringLiterals(it).firstOrNull() }?.let { sanitize(it) }
        val originating = nameArgs?.getOrNull(5)?.let { stringLiterals(it).firstOrNull() }?.let { sanitize(it) }
        return HeaderInfo(schemas, fileName?.takeIf { it.isNotEmpty() }, originating?.takeIf { it.isNotEmpty() })
    }

    /** The top-level arguments of `KEYWORD( ... );` inside the HEADER window, or null if absent. */
    private fun headerArguments(
        text: String,
        keyword: String,
    ): List<String>? {
        var searchFrom = 0
        while (true) {
            val at = text.indexOf(keyword, searchFrom, ignoreCase = true)
            if (at < 0) return null
            searchFrom = at + keyword.length
            var p = at + keyword.length
            while (p < text.length && text[p].isWhitespace()) p++
            if (p >= text.length || text[p] != '(') continue
            val args = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var inString = false
            p++
            depth = 1
            while (p < text.length) {
                val ch = text[p]
                if (inString) {
                    current.append(ch)
                    if (ch == '\'') inString = false
                } else {
                    when (ch) {
                        '\'' -> {
                            inString = true
                            current.append(ch)
                        }
                        '(' -> {
                            depth++
                            current.append(ch)
                        }
                        ')' -> {
                            depth--
                            if (depth == 0) {
                                args.add(current.toString())
                                return args
                            }
                            current.append(ch)
                        }
                        ',' ->
                            if (depth == 1) {
                                args.add(current.toString())
                                current.setLength(0)
                            } else {
                                current.append(ch)
                            }
                        else -> current.append(ch)
                    }
                }
                p++
            }
            return null
        }
    }

    private fun stringLiterals(argument: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < argument.length) {
            if (argument[i] == '\'') {
                val sb = StringBuilder()
                i++
                while (i < argument.length) {
                    if (argument[i] == '\'') {
                        if (i + 1 < argument.length && argument[i + 1] == '\'') {
                            sb.append('\'')
                            i += 2
                            continue
                        }
                        break
                    }
                    sb.append(argument[i])
                    i++
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }

    /** Removes control characters and truncates -- everything that reaches text or JSON output passes here. */
    internal fun sanitize(raw: String): String {
        val cleaned = sanitizeImportText(raw)
        return if (cleaned.length > MAX_TEXT_CHARS) cleaned.substring(0, MAX_TEXT_CHARS) else cleaned
    }

    private fun notStepMessage(
        content: ByteArray,
        start: Int,
    ): String {
        val hint =
            when {
                content.size - start >= 5 && String(content, start, 5, StandardCharsets.ISO_8859_1) == "<?xml" ->
                    " (STEP-XML is not supported)"
                content.size - start >= 2 &&
                    content[start] == 'P'.code.toByte() &&
                    content[start + 1] == 'K'.code.toByte() ->
                    " (compressed stpZ/ZIP is not supported)"
                else -> ""
            }
        return "not an ISO 10303-21 STEP file$hint"
    }

    private fun isWhitespace(b: Byte): Boolean =
        b == ' '.code.toByte() || b == '\t'.code.toByte() || b == '\r'.code.toByte() || b == '\n'.code.toByte()

    private fun isKeywordStart(c: Int): Boolean =
        (c >= 'A'.code && c <= 'Z'.code) || (c >= 'a'.code && c <= 'z'.code) || c == '_'.code

    private fun isKeywordPart(c: Int): Boolean = isKeywordStart(c) || (c >= '0'.code && c <= '9'.code)
}
