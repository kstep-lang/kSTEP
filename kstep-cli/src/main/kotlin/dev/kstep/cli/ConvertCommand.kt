package dev.kstep.cli

import dev.kstep.cli.okf.ConvertFiles
import dev.kstep.cli.okf.ConvertSourceScanner
import dev.kstep.cli.okf.ConvertTreeScanner
import dev.kstep.cli.okf.DisplayName
import dev.kstep.cli.okf.OkfConverter
import dev.kstep.cli.okf.OkfLimits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/** Error families of `kstep convert` (ADR-0061); the text is the stable `KSTEP-C-*` code. */
internal enum class ConvertErrorKind(
    val code: String,
    val jsonKind: String,
) {
    INVALID_PATH("KSTEP-C-001", "invalid_path"),
    WRONG_INPUT_TYPE("KSTEP-C-002", "wrong_input_type"),
    INPUT_UNREADABLE("KSTEP-C-003", "input_unreadable"),
    INPUT_TOO_LARGE("KSTEP-C-004", "input_too_large"),
    CONVERSION_REJECTED("KSTEP-C-005", "conversion_rejected"),
    INVALID_METADATA("KSTEP-C-006", "invalid_metadata"),
    OUTPUT_FOLDER("KSTEP-C-007", "output_folder_invalid"),
    TARGET_EXISTS("KSTEP-C-008", "target_exists"),
    TARGET_INVALID("KSTEP-C-009", "target_invalid"),
    WRITE_FAILED("KSTEP-C-010", "write_failed"),
    LIMIT_EXCEEDED("KSTEP-C-011", "limit_exceeded"),
    DEPTH_EXCEEDED("KSTEP-C-012", "depth_exceeded"),
    PATH_ESCAPE("KSTEP-C-013", "path_escape"),
    INVALID_BLOCK_NAME("KSTEP-C-014", "invalid_block_name"),
    DUPLICATE_BLOCK_NAME("KSTEP-C-015", "duplicate_block_name"),
}

/** Warnings never change the exit code (ADR-0071). */
internal enum class ConvertWarningKind(
    val code: String,
    val jsonKind: String,
) {
    UNKNOWN_BLOCK_ATTRIBUTE("KSTEP-C-016", "unknown_block_attribute"),
    UNTERMINATED_BLOCK_ATTRIBUTE("KSTEP-C-017", "unterminated_block_attribute"),
}

internal data class ConvertWarning(
    val kind: ConvertWarningKind,
    val source: String,
    val line: Int?,
    val message: String,
)

internal data class ConvertFailure(
    val kind: ConvertErrorKind,
    val message: String,
    val source: String? = null,
    /** Escaped targets of the pre-write TARGET_EXISTS check (all of them); empty for every other failure. */
    val conflicts: List<String> = emptyList(),
)

/** [name] and [source] are already shown safely (`DisplayName.escaped`). */
internal data class WrittenFile(
    val name: String,
    val source: String,
    val block: Int?,
    val target: String? = null,
    val blockName: String? = null,
)

internal enum class SkipReason(
    val jsonKind: String,
) {
    EMPTY_BLOCK("empty_block"),
    NO_KSTEP_BLOCK("no_kstep_block"),
    EMPTY_SCRIPT("empty_script"),
    SYMBOLIC_LINK("symbolic_link"),
    NOT_REGULAR("not_regular_file"),
    HIDDEN("hidden"),
    HIDDEN_DIR("hidden_dir"),
    OUTPUT_DIR("output_dir"),
}

internal data class Skipped(
    val source: String,
    val reason: SkipReason,
    val line: Int? = null,
)

internal sealed interface ConvertReport {
    val warnings: List<ConvertWarning>

    data class Success(
        val written: List<WrittenFile>,
        val skipped: List<Skipped>,
        override val warnings: List<ConvertWarning> = emptyList(),
    ) : ConvertReport

    data class Failed(
        val failures: List<ConvertFailure>,
        val written: List<WrittenFile>,
        val skipped: List<Skipped>,
        override val warnings: List<ConvertWarning> = emptyList(),
    ) : ConvertReport
}

private fun ConvertReport.withWarnings(w: List<ConvertWarning>): ConvertReport =
    when (this) {
        is ConvertReport.Success -> copy(warnings = w)
        is ConvertReport.Failed -> copy(warnings = w)
    }

/** Injectable so that tests can run with tiny bounds instead of thousands of files. */
internal data class ConvertDirLimits(
    val maxEntries: Int = OkfLimits.MAX_DIR_ENTRIES,
    val maxFiles: Int = OkfLimits.MAX_MD_FILES,
    val maxBytes: Long = OkfLimits.MAX_SCAN_BYTES,
    val maxTargets: Int = OkfLimits.MAX_CONVERT_TARGETS,
    val maxReportedErrors: Int = 50,
    val maxDepth: Int = OkfLimits.MAX_DEPTH,
)

/** What `kstep convert` writes; see ADR-0060 and ADR-0061. */
private class PlannedFile(
    val name: String,
    val bytes: ByteArray,
    val source: String,
    val block: Int?,
    val relDir: List<String> = emptyList(),
    val blockName: String? = null,
) {
    val relPath: String get() = (relDir + name).joinToString("/")
}

private sealed interface One {
    class Ok(
        val files: List<PlannedFile>,
        val skipped: List<Skipped>,
        val warnings: List<ConvertWarning> = emptyList(),
    ) : One

    class Fail(
        val failure: ConvertFailure,
    ) : One
}

internal data class ConvertRun(
    val dirMode: Boolean,
    val report: ConvertReport,
)

/**
 * The single code path of a conversion (CLI and desktop): plans, checks and writes, and returns the report. Prints
 * nothing itself; [onWritten] and [onPlanned] are the hooks the CLI prints through.
 */
@Suppress("LongParameterList")
internal fun computeConvert(
    command: CliCommand.Convert,
    limits: ConvertDirLimits = ConvertDirLimits(),
    beforeMove: () -> Unit = {},
    beforeDescend: (Path) -> Unit = {},
    onWritten: (WrittenFile) -> Unit = {},
    onPlanned: (List<Skipped>) -> Unit = {},
): ConvertRun {
    val dirMode = isFolderInput(command.inputPath)
    val warnings = ArrayList<ConvertWarning>()
    val report =
        executeConvert(
            command,
            dirMode,
            limits,
            beforeMove,
            onWritten = onWritten,
            onPlanned = onPlanned,
            warnings = warnings,
            beforeDescend = beforeDescend,
        ).withWarnings(warnings)
    return ConvertRun(dirMode, report)
}

/**
 * Runs `kstep convert`: pure text conversion, never evaluates a script. Returns the exit code (0 or 1) and does NOT call
 * `exitProcess`, so it can be tested in-process. Nothing is written unless every target has been checked first.
 */
internal fun runConvert(
    command: CliCommand.Convert,
    out: PrintStream = System.out,
    err: PrintStream = System.err,
    beforeMove: () -> Unit = {},
    limits: ConvertDirLimits = ConvertDirLimits(),
    beforeDescend: (Path) -> Unit = {},
): Int {
    val json = command.jsonOutput
    val hintDirMode = isFolderInput(command.inputPath)
    val (dirMode, report) =
        computeConvert(
            command,
            limits,
            beforeMove,
            beforeDescend,
            onWritten = { w -> if (!json) out.println("Wrote ${w.target ?: w.name}") },
            onPlanned = { skipped -> if (!json) printSkipHints(err, skipped, hintDirMode) },
        )
    if (json) {
        out.println(
            convertReportJson(command.target, dirMode, report, limits.maxReportedErrors, command.recursive).toString(),
        )
    } else {
        for (w in report.warnings.take(limits.maxReportedErrors)) err.println("kstep convert: ${w.message}")
        if (report.warnings.size > limits.maxReportedErrors) {
            err.println("kstep convert: ... and ${report.warnings.size - limits.maxReportedErrors} more warnings")
        }
    }
    if (!json && report is ConvertReport.Failed) {
        val max = limits.maxReportedErrors
        for (f in report.failures.take(max)) err.println("kstep convert: ${f.message}")
        if (report.failures.size > max) err.println("kstep convert: ... and ${report.failures.size - max} more errors")
        if (report.written.isNotEmpty()) {
            err.println("kstep convert: already written: ${report.written.joinToString(", ") { it.target ?: it.name }}")
        } else if (report.failures.size > 1) {
            err.println("kstep convert: nothing was written")
        }
    }
    return if (report is ConvertReport.Success) 0 else 1
}

private fun isFolderInput(path: String): Boolean =
    try {
        Files.isDirectory(Paths.get(path))
    } catch (_: InvalidPathException) {
        false
    }

private fun printSkipHints(
    err: PrintStream,
    skipped: List<Skipped>,
    dirMode: Boolean,
) {
    for (s in skipped) {
        val text =
            when (s.reason) {
                SkipReason.EMPTY_BLOCK ->
                    if (s.line == null) {
                        "${s.source}: every kstep block is empty, skipped"
                    } else if (dirMode) {
                        "${s.source}: skipped the empty kstep block in line ${s.line}"
                    } else {
                        "skipped the empty kstep block in line ${s.line}"
                    }
                SkipReason.NO_KSTEP_BLOCK -> "${s.source}: no kstep block, skipped"
                SkipReason.EMPTY_SCRIPT -> "${s.source}: empty script, skipped"
                SkipReason.SYMBOLIC_LINK -> "skipped ${s.source} (symbolic link)"
                SkipReason.NOT_REGULAR -> "skipped ${s.source} (not a regular file)"
                SkipReason.HIDDEN -> "skipped ${s.source} (hidden file)"
                SkipReason.HIDDEN_DIR -> "skipped ${s.source} (hidden folder)"
                SkipReason.OUTPUT_DIR -> "skipped ${s.source} (output folder)"
            }
        err.println("kstep convert: $text")
    }
}

/** The single JSON document of `--output json`; names and sources are escaped, no absolute path, no file content. */
internal fun convertReportJson(
    target: ConvertTarget,
    dirMode: Boolean,
    report: ConvertReport,
    maxReportedErrors: Int = 50,
    recursive: Boolean = false,
): JsonObject =
    buildJsonObject {
        fun writtenJson(w: WrittenFile) =
            buildJsonObject {
                put("name", w.name)
                put("source", w.source)
                if (w.block != null) put("block", w.block)
                if (w.target != null) put("target", w.target)
                if (w.blockName != null) put("blockName", w.blockName)
            }

        fun warningsJson() {
            if (report.warnings.isEmpty()) return
            put(
                "warnings",
                buildJsonArray {
                    for (w in report.warnings.take(maxReportedErrors)) {
                        add(
                            buildJsonObject {
                                put("source", w.source)
                                put("kind", w.kind.jsonKind)
                                put("code", w.kind.code)
                                if (w.line != null) put("line", w.line)
                                put("message", w.message)
                            },
                        )
                    }
                },
            )
            if (report.warnings.size > maxReportedErrors) put("moreWarnings", report.warnings.size - maxReportedErrors)
        }

        fun skippedJson(s: Skipped) =
            buildJsonObject {
                put("source", s.source)
                put("reason", s.reason.jsonKind)
                if (s.line != null) put("line", s.line)
            }
        when (report) {
            is ConvertReport.Success -> {
                put("status", "success")
                put("command", "convert")
                put("target", target.name.lowercase(Locale.ROOT))
                if (recursive) put("recursive", true)
                put("written", buildJsonArray { report.written.forEach { add(writtenJson(it)) } })
                put("skipped", buildJsonArray { report.skipped.forEach { add(skippedJson(it)) } })
                warningsJson()
            }
            is ConvertReport.Failed -> {
                val first = report.failures.first()
                put("status", "error")
                put("command", "convert")
                if (recursive) put("recursive", true)
                put("errorKind", first.kind.jsonKind)
                put("code", first.kind.code)
                put("message", first.message)
                if (first.source != null) put("source", first.source)
                if (dirMode) {
                    put(
                        "errors",
                        buildJsonArray {
                            for (f in report.failures.take(maxReportedErrors)) {
                                add(
                                    buildJsonObject {
                                        if (f.source != null) put("source", f.source)
                                        put("errorKind", f.kind.jsonKind)
                                        put("code", f.kind.code)
                                        put("message", f.message)
                                    },
                                )
                            }
                        },
                    )
                    if (report.failures.size > maxReportedErrors) {
                        put("moreErrors", report.failures.size - maxReportedErrors)
                    }
                }
                if (report.written.isNotEmpty()) {
                    put("written", buildJsonArray { report.written.forEach { add(writtenJson(it)) } })
                }
                if (report.skipped.isNotEmpty()) {
                    put("skipped", buildJsonArray { report.skipped.forEach { add(skippedJson(it)) } })
                }
                warningsJson()
            }
        }
    }

@Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
private fun executeConvert(
    command: CliCommand.Convert,
    dirMode: Boolean,
    limits: ConvertDirLimits,
    beforeMove: () -> Unit,
    onWritten: (WrittenFile) -> Unit,
    onPlanned: (List<Skipped>) -> Unit,
    warnings: MutableList<ConvertWarning>,
    beforeDescend: (Path) -> Unit,
): ConvertReport {
    fun failed(
        kind: ConvertErrorKind,
        message: String,
        source: String? = null,
        skipped: List<Skipped> = emptyList(),
    ) = ConvertReport.Failed(listOf(ConvertFailure(kind, message, source)), emptyList(), skipped)

    val inputPath =
        try {
            Paths.get(command.inputPath)
        } catch (_: InvalidPathException) {
            return failed(ConvertErrorKind.INVALID_PATH, "the input path is not valid")
        }
    if (command.recursive && !dirMode) {
        return failed(ConvertErrorKind.WRONG_INPUT_TYPE, "--recursive expects a folder")
    }
    val okf = command.target == ConvertTarget.OKF
    var title: String? = null
    var tags: List<String> = listOf(OkfConverter.KSTEP_TAG)
    if (okf) {
        when (val m = OkfConverter.checkMetadata(command.title, command.tags)) {
            is OkfConverter.MetadataCheck.Ok -> {
                title = m.title
                tags = m.tags
            }
            is OkfConverter.MetadataCheck.Rejected -> return failed(ConvertErrorKind.INVALID_METADATA, m.reason)
        }
        if (dirMode && command.title != null) {
            return failed(ConvertErrorKind.INVALID_METADATA, "--title cannot be used with a folder")
        }
    } else if (command.title != null || command.tags.isNotEmpty()) {
        return failed(ConvertErrorKind.INVALID_METADATA, "--title and --tag apply only to --to okf")
    }
    val suffix = if (okf) OkfConverter.SCRIPT_SUFFIX else ".md"
    val maxFileBytes = if (okf) OkfLimits.MAX_BLOCK_BYTES.toLong() else OkfLimits.MAX_FILE_BYTES

    val planned = ArrayList<PlannedFile>()
    val skipped = ArrayList<Skipped>()
    val failures = ArrayList<ConvertFailure>()
    val inputKeys = HashSet<Any>()
    val inputReals = HashSet<Path>()
    val inputReal: Path
    var totalBytes = 0L

    fun readFailure(
        shown: String,
        r: ConvertFiles.ReadResult.Failed,
    ): ConvertFailure =
        ConvertFailure(
            when (r.kind) {
                ConvertFiles.ReadFailure.MISSING -> ConvertErrorKind.INVALID_PATH
                ConvertFiles.ReadFailure.TOO_LARGE -> ConvertErrorKind.INPUT_TOO_LARGE
                ConvertFiles.ReadFailure.UNREADABLE -> ConvertErrorKind.INPUT_UNREADABLE
            },
            "$shown: ${r.message}",
            shown,
        )

    fun convertOne(
        text: String,
        stem: String,
        shown: String,
        relDir: List<String> = emptyList(),
    ): One {
        if (okf) {
            if (dirMode && text.isBlank()) {
                return One.Ok(emptyList(), listOf(Skipped(shown, SkipReason.EMPTY_SCRIPT)))
            }
            val noteTitle = title ?: OkfConverter.titleFromStem(stem)
            return when (val w = OkfConverter.wrapAsOkf(text, noteTitle, tags)) {
                is OkfConverter.WrapResult.Ok ->
                    One.Ok(
                        listOf(
                            PlannedFile(
                                OkfConverter.sanitizeStem(stem) + ".md",
                                w.note.toByteArray(StandardCharsets.UTF_8),
                                shown,
                                null,
                                relDir,
                            ),
                        ),
                        emptyList(),
                    )
                is OkfConverter.WrapResult.Rejected ->
                    One.Fail(ConvertFailure(ConvertErrorKind.CONVERSION_REJECTED, "$shown: ${w.reason}", shown))
            }
        }
        return when (val x = OkfConverter.extractScripts(text, stem)) {
            is OkfConverter.ExtractResult.Ok -> {
                val single = OkfConverter.sanitizeStem(stem) + OkfConverter.SCRIPT_SUFFIX
                One.Ok(
                    x.scripts.map {
                        PlannedFile(
                            it.fileName,
                            it.source.toByteArray(StandardCharsets.UTF_8),
                            shown,
                            if (it.fileName == single) null else it.blockIndex + 1,
                            relDir,
                            it.blockName,
                        )
                    },
                    x.skippedEmptyLines.map { Skipped(shown, SkipReason.EMPTY_BLOCK, it) },
                    x.scripts.flatMap { blockWarnings(shown, it) },
                )
            }
            is OkfConverter.ExtractResult.Rejected ->
                when {
                    dirMode && x.kind == OkfConverter.RejectKind.NO_BLOCK ->
                        One.Ok(emptyList(), listOf(Skipped(shown, SkipReason.NO_KSTEP_BLOCK)))
                    dirMode && x.kind == OkfConverter.RejectKind.ALL_EMPTY ->
                        One.Ok(emptyList(), listOf(Skipped(shown, SkipReason.EMPTY_BLOCK)))
                    else ->
                        One.Fail(
                            ConvertFailure(
                                when (x.kind) {
                                    OkfConverter.RejectKind.INVALID_NAME -> ConvertErrorKind.INVALID_BLOCK_NAME
                                    OkfConverter.RejectKind.DUPLICATE_NAME -> ConvertErrorKind.DUPLICATE_BLOCK_NAME
                                    else -> ConvertErrorKind.CONVERSION_REJECTED
                                },
                                "$shown: ${x.reason}" + (x.line?.let { " (line $it)" } ?: ""),
                                shown,
                            ),
                        )
                }
        }
    }

    if (!dirMode) {
        val fileName =
            inputPath.fileName?.toString()
                ?: return failed(ConvertErrorKind.INVALID_PATH, "the input path has no file name")
        val shown = DisplayName.escaped(fileName)
        if (!fileName.lowercase(Locale.ROOT).endsWith(suffix)) {
            return failed(
                ConvertErrorKind.WRONG_INPUT_TYPE,
                if (okf) "--to okf expects a *.kstep.kts file" else "--to kts expects a *.md file",
                shown,
            )
        }
        val stem = fileName.substring(0, fileName.length - suffix.length)
        val text =
            when (val r = ConvertFiles.readBounded(inputPath, maxFileBytes)) {
                is ConvertFiles.ReadResult.Ok -> r.text
                is ConvertFiles.ReadResult.Failed ->
                    return ConvertReport.Failed(listOf(readFailure(shown, r)), emptyList(), emptyList())
            }
        inputReal =
            try {
                inputPath.toRealPath()
            } catch (_: IOException) {
                return failed(ConvertErrorKind.INVALID_PATH, "$shown: the input file cannot be resolved", shown)
            }
        inputReals.add(inputReal)
        try {
            Files.readAttributes(inputReal, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        } catch (_: IOException) {
            null
        }?.let { inputKeys += it }
        val outReal =
            when (val o = resolveOut(command, inputReal.parent)) {
                is Resolved.Ok -> o.real
                is Resolved.Bad -> return failed(ConvertErrorKind.OUTPUT_FOLDER, o.message)
            }
        when (val one = convertOne(text, stem, shown)) {
            is One.Ok -> {
                planned += one.files
                skipped += one.skipped
                warnings += one.warnings
            }
            is One.Fail -> return ConvertReport.Failed(listOf(one.failure), emptyList(), emptyList())
        }
        return finish(
            command,
            limits,
            outReal,
            planned,
            skipped,
            inputKeys,
            inputReals,
            beforeMove,
            onWritten,
            onPlanned,
        )
    }

    // Folder mode (ADR-0061): one level, every source read bounded and without following links.
    inputReal =
        try {
            inputPath.toRealPath()
        } catch (_: IOException) {
            return failed(ConvertErrorKind.INVALID_PATH, "the input folder cannot be resolved")
        }
    val outReal =
        when (val o = resolveOut(command, inputReal)) {
            is Resolved.Ok -> o.real
            is Resolved.Bad -> return failed(ConvertErrorKind.OUTPUT_FOLDER, o.message)
        }

    class Item(
        val relDir: List<String>,
        val name: String,
        val path: Path,
        val fileKey: Any?,
    )

    val items = ArrayList<Item>()
    if (command.recursive) {
        // Recursive mode (ADR-0071): own scanner; the flat branch below stays as it was.
        val skipOut = if (outReal != inputReal && outReal.startsWith(inputReal)) outReal else null
        val l =
            ConvertTreeScanner.list(
                inputReal,
                suffix,
                skipOut,
                limits.maxDepth,
                limits.maxEntries,
                limits.maxFiles,
                limits.maxBytes,
                beforeDescend,
            )
        when (l) {
            is ConvertTreeScanner.Outcome.Failed ->
                return failed(
                    when (l.kind) {
                        ConvertTreeScanner.FailKind.LIMIT -> ConvertErrorKind.LIMIT_EXCEEDED
                        ConvertTreeScanner.FailKind.UNREADABLE -> ConvertErrorKind.INPUT_UNREADABLE
                        ConvertTreeScanner.FailKind.DEPTH -> ConvertErrorKind.DEPTH_EXCEEDED
                        ConvertTreeScanner.FailKind.ESCAPE -> ConvertErrorKind.PATH_ESCAPE
                    },
                    l.message,
                )
            is ConvertTreeScanner.Outcome.Ok -> {
                for (ig in l.ignored) {
                    val dirKind =
                        ig.kind == ConvertTreeScanner.SkipKind.HIDDEN_DIR ||
                            ig.kind == ConvertTreeScanner.SkipKind.OUTPUT_DIR
                    skipped +=
                        Skipped(
                            DisplayName.escaped(ig.relPath) + if (dirKind) "/" else "",
                            when (ig.kind) {
                                ConvertTreeScanner.SkipKind.SYMBOLIC_LINK -> SkipReason.SYMBOLIC_LINK
                                ConvertTreeScanner.SkipKind.NOT_REGULAR -> SkipReason.NOT_REGULAR
                                ConvertTreeScanner.SkipKind.HIDDEN -> SkipReason.HIDDEN
                                ConvertTreeScanner.SkipKind.HIDDEN_DIR -> SkipReason.HIDDEN_DIR
                                ConvertTreeScanner.SkipKind.OUTPUT_DIR -> SkipReason.OUTPUT_DIR
                            },
                        )
                }
                for (c in l.candidates) items += Item(c.relDir, c.name, c.path, c.fileKey)
            }
        }
    } else {
        val l =
            ConvertSourceScanner.list(inputReal, suffix, limits.maxEntries, limits.maxFiles, limits.maxBytes)
        when (l) {
            is ConvertSourceScanner.Outcome.Failed ->
                return failed(
                    if (l.limit) ConvertErrorKind.LIMIT_EXCEEDED else ConvertErrorKind.INPUT_UNREADABLE,
                    l.message,
                )
            is ConvertSourceScanner.Outcome.Ok -> {
                for (ig in l.ignored) {
                    skipped +=
                        Skipped(
                            DisplayName.escaped(ig.name),
                            when (ig.reason) {
                                ConvertSourceScanner.IgnoredReason.SYMBOLIC_LINK -> SkipReason.SYMBOLIC_LINK
                                ConvertSourceScanner.IgnoredReason.NOT_REGULAR -> SkipReason.NOT_REGULAR
                                ConvertSourceScanner.IgnoredReason.HIDDEN -> SkipReason.HIDDEN
                            },
                        )
                }
                for (c in l.candidates) items += Item(emptyList(), c.name, c.path, c.fileKey)
            }
        }
    }
    if (items.isEmpty()) {
        onPlanned(skipped)
        val which = if (okf) "*.kstep.kts" else "*.md"
        return failed(ConvertErrorKind.WRONG_INPUT_TYPE, "no $which files in the input folder", skipped = skipped)
    }
    for (c in items) {
        val shown = DisplayName.escaped((c.relDir + c.name).joinToString("/"))
        c.fileKey?.let { inputKeys += it }
        inputReals.add(c.path)
        val text =
            when (val r = ConvertFiles.readBounded(c.path, maxFileBytes)) {
                is ConvertFiles.ReadResult.Ok -> r.text
                is ConvertFiles.ReadResult.Failed -> {
                    failures += readFailure(shown, r)
                    continue
                }
            }
        totalBytes += OkfConverter.utf8Size(text)
        if (totalBytes > limits.maxBytes) {
            onPlanned(skipped)
            return failed(
                ConvertErrorKind.LIMIT_EXCEEDED,
                "the files are larger than ${limits.maxBytes / 1024} KiB together",
                skipped = skipped,
            )
        }
        when (val one = convertOne(text, c.name.substring(0, c.name.length - suffix.length), shown, c.relDir)) {
            is One.Ok -> {
                planned += one.files
                skipped += one.skipped
                warnings += one.warnings
            }
            is One.Fail -> failures += one.failure
        }
    }
    if (failures.isNotEmpty()) {
        onPlanned(skipped)
        return ConvertReport.Failed(failures, emptyList(), skipped)
    }
    if (planned.isEmpty()) {
        onPlanned(skipped)
        return failed(
            ConvertErrorKind.CONVERSION_REJECTED,
            "nothing to convert: every file was skipped",
            skipped = skipped,
        )
    }
    return finish(command, limits, outReal, planned, skipped, inputKeys, inputReals, beforeMove, onWritten, onPlanned)
}

private sealed interface Resolved {
    class Ok(
        val real: Path,
    ) : Resolved

    class Bad(
        val message: String,
    ) : Resolved
}

private fun resolveOut(
    command: CliCommand.Convert,
    default: Path,
): Resolved {
    val dir: Path =
        try {
            command.outDir?.let { Paths.get(it) } ?: default
        } catch (_: InvalidPathException) {
            return Resolved.Bad("the output folder path is not valid")
        }
    return when (val r = ConvertFiles.resolveOutDir(dir)) {
        is ConvertFiles.DirResult.Ok -> Resolved.Ok(r.real)
        is ConvertFiles.DirResult.Failed -> Resolved.Bad(r.message)
    }
}

/** Collision, limit and target checks for all planned files, then the writes (all-or-nothing up to the first write). */
@Suppress("LongParameterList", "LongMethod", "ReturnCount")
private fun finish(
    command: CliCommand.Convert,
    limits: ConvertDirLimits,
    outReal: Path,
    planned: List<PlannedFile>,
    skipped: List<Skipped>,
    inputKeys: Set<Any>,
    inputReals: Set<Path>,
    beforeMove: () -> Unit,
    onWritten: (WrittenFile) -> Unit,
    onPlanned: (List<Skipped>) -> Unit,
): ConvertReport {
    val recursive = command.recursive

    fun failed(
        kind: ConvertErrorKind,
        message: String,
        source: String? = null,
    ) = ConvertReport.Failed(listOf(ConvertFailure(kind, message, source)), emptyList(), skipped)

    fun shownTarget(p: PlannedFile) = DisplayName.escaped(if (recursive) p.relPath else p.name)

    onPlanned(skipped)
    if (planned.size > limits.maxTargets) {
        return failed(ConvertErrorKind.LIMIT_EXCEEDED, "more than ${limits.maxTargets} target files")
    }
    // Case-insensitive: macOS and Windows would silently merge such names.
    val byName = HashMap<String, PlannedFile>()
    for (p in planned) {
        val other = byName.put(p.relPath.lowercase(Locale.ROOT), p)
        if (other != null) {
            val message =
                if (other.source == p.source) {
                    "the converted file names are not unique"
                } else {
                    "${other.source} and ${p.source} would both write ${shownTarget(p)}"
                }
            return failed(ConvertErrorKind.TARGET_INVALID, message, p.source)
        }
    }
    // A planned file must not share its name with a planned folder (any prefix of any relDir), case-insensitively.
    val folderKeys = HashSet<String>()
    for (p in planned) {
        for (i in 1..p.relDir.size) {
            val prefix = p.relDir.subList(0, i)
            folderKeys += prefix.joinToString("/").lowercase(Locale.ROOT)
        }
    }
    for (p in planned) {
        if (p.relPath.lowercase(Locale.ROOT) in folderKeys) {
            return failed(
                ConvertErrorKind.TARGET_INVALID,
                "${shownTarget(p)}: the same name is also needed as a target folder",
                p.source,
            )
        }
    }
    val conflicts = ArrayList<String>()
    for (p in planned) {
        val dirCheck =
            when (val d = ConvertFiles.checkTargetDir(outReal, p.relDir)) {
                is ConvertFiles.DirCheck.Invalid ->
                    return failed(ConvertErrorKind.TARGET_INVALID, "${shownTarget(p)}: ${d.message}", p.source)
                is ConvertFiles.DirCheck.Ready -> d
            }
        if (!dirCheck.exists) continue
        when (val c = ConvertFiles.checkTarget(dirCheck.dir, p.name, inputKeys, inputReals)) {
            is ConvertFiles.TargetCheck.Free -> Unit
            is ConvertFiles.TargetCheck.Exists -> if (!command.force) conflicts += shownTarget(p)
            is ConvertFiles.TargetCheck.Invalid ->
                return failed(ConvertErrorKind.TARGET_INVALID, "${shownTarget(p)}: ${c.message}", p.source)
        }
    }
    if (conflicts.isNotEmpty()) {
        val message =
            if (conflicts.size == 1) {
                "${conflicts.first()} already exists; use --force to replace it. Nothing was written."
            } else {
                "${conflicts.first()} and ${conflicts.size - 1} more already exist; " +
                    "use --force to replace them. Nothing was written."
            }
        return ConvertReport.Failed(
            listOf(ConvertFailure(ConvertErrorKind.TARGET_EXISTS, message, conflicts = conflicts.toList())),
            emptyList(),
            skipped,
        )
    }

    val written = ArrayList<WrittenFile>()
    for (p in planned) {
        val shownName = shownTarget(p)
        val dir =
            when (val d = ConvertFiles.ensureDir(outReal, p.relDir)) {
                is ConvertFiles.EnsureResult.Ok -> d.dir
                is ConvertFiles.EnsureResult.Escaped ->
                    return ConvertReport.Failed(
                        listOf(ConvertFailure(ConvertErrorKind.PATH_ESCAPE, "$shownName: ${d.message}", p.source)),
                        written,
                        skipped,
                    )
                is ConvertFiles.EnsureResult.Failed ->
                    return ConvertReport.Failed(
                        listOf(ConvertFailure(ConvertErrorKind.WRITE_FAILED, "$shownName: ${d.message}", p.source)),
                        written,
                        skipped,
                    )
            }
        when (val r = ConvertFiles.writeAtomic(dir, p.name, p.bytes, command.force, beforeMove = beforeMove)) {
            ConvertFiles.WriteResult.Written -> {
                val w =
                    WrittenFile(
                        DisplayName.escaped(p.name),
                        p.source,
                        p.block,
                        if (recursive) DisplayName.escaped(p.relPath) else null,
                        p.blockName,
                    )
                written += w
                onWritten(w)
            }
            ConvertFiles.WriteResult.AlreadyExists ->
                return ConvertReport.Failed(
                    listOf(
                        ConvertFailure(
                            ConvertErrorKind.TARGET_EXISTS,
                            "$shownName appeared meanwhile; use --force to replace it",
                            p.source,
                        ),
                    ),
                    written,
                    skipped,
                )
            is ConvertFiles.WriteResult.Failed ->
                return ConvertReport.Failed(
                    listOf(ConvertFailure(ConvertErrorKind.WRITE_FAILED, "$shownName: ${r.message}", p.source)),
                    written,
                    skipped,
                )
        }
    }
    return ConvertReport.Success(written, skipped)
}

private fun blockWarnings(
    shown: String,
    script: OkfConverter.ExtractedScript,
): List<ConvertWarning> {
    val result = ArrayList<ConvertWarning>()
    for (key in script.unknownKeys) {
        result +=
            ConvertWarning(
                ConvertWarningKind.UNKNOWN_BLOCK_ATTRIBUTE,
                shown,
                script.openLine,
                "$shown: unknown block attribute \"${DisplayName.escaped(key, 32)}\" ignored (line ${script.openLine})",
            )
    }
    if (script.attrUnterminated) {
        result +=
            ConvertWarning(
                ConvertWarningKind.UNTERMINATED_BLOCK_ATTRIBUTE,
                shown,
                script.openLine,
                "$shown: unterminated block attribute ignored (line ${script.openLine})",
            )
    }
    return result
}
