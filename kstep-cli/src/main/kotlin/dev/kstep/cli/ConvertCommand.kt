package dev.kstep.cli

import dev.kstep.cli.okf.ConvertFiles
import dev.kstep.cli.okf.ConvertSourceScanner
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
}

internal data class ConvertFailure(
    val kind: ConvertErrorKind,
    val message: String,
    val source: String? = null,
)

/** [name] and [source] are already shown safely (`DisplayName.escaped`). */
internal data class WrittenFile(
    val name: String,
    val source: String,
    val block: Int?,
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
}

internal data class Skipped(
    val source: String,
    val reason: SkipReason,
    val line: Int? = null,
)

internal sealed interface ConvertReport {
    data class Success(
        val written: List<WrittenFile>,
        val skipped: List<Skipped>,
    ) : ConvertReport

    data class Failed(
        val failures: List<ConvertFailure>,
        val written: List<WrittenFile>,
        val skipped: List<Skipped>,
    ) : ConvertReport
}

/** Injectable so that tests can run with tiny bounds instead of thousands of files. */
internal data class ConvertDirLimits(
    val maxEntries: Int = OkfLimits.MAX_DIR_ENTRIES,
    val maxFiles: Int = OkfLimits.MAX_MD_FILES,
    val maxBytes: Long = OkfLimits.MAX_SCAN_BYTES,
    val maxTargets: Int = OkfLimits.MAX_CONVERT_TARGETS,
    val maxReportedErrors: Int = 50,
)

/** What `kstep convert` writes; see ADR-0060 and ADR-0061. */
private class PlannedFile(
    val name: String,
    val bytes: ByteArray,
    val source: String,
    val block: Int?,
)

private sealed interface One {
    class Ok(
        val files: List<PlannedFile>,
        val skipped: List<Skipped>,
    ) : One

    class Fail(
        val failure: ConvertFailure,
    ) : One
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
): Int {
    val json = command.jsonOutput
    val dirMode = isFolderInput(command.inputPath)
    val report =
        executeConvert(
            command,
            dirMode,
            limits,
            beforeMove,
            onWritten = { w -> if (!json) out.println("Wrote ${w.name}") },
            onPlanned = { skipped -> if (!json) printSkipHints(err, skipped, dirMode) },
        )
    if (json) {
        out.println(convertReportJson(command.target, dirMode, report, limits.maxReportedErrors).toString())
    } else if (report is ConvertReport.Failed) {
        val max = limits.maxReportedErrors
        for (f in report.failures.take(max)) err.println("kstep convert: ${f.message}")
        if (report.failures.size > max) err.println("kstep convert: ... and ${report.failures.size - max} more errors")
        if (report.written.isNotEmpty()) {
            err.println("kstep convert: already written: ${report.written.joinToString(", ") { it.name }}")
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
): JsonObject =
    buildJsonObject {
        fun writtenJson(w: WrittenFile) =
            buildJsonObject {
                put("name", w.name)
                put("source", w.source)
                if (w.block != null) put("block", w.block)
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
                put("written", buildJsonArray { report.written.forEach { add(writtenJson(it)) } })
                put("skipped", buildJsonArray { report.skipped.forEach { add(skippedJson(it)) } })
            }
            is ConvertReport.Failed -> {
                val first = report.failures.first()
                put("status", "error")
                put("command", "convert")
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
                        )
                    },
                    x.skippedEmptyLines.map { Skipped(shown, SkipReason.EMPTY_BLOCK, it) },
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
                                ConvertErrorKind.CONVERSION_REJECTED,
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
    val listing =
        when (
            val l =
                ConvertSourceScanner.list(inputReal, suffix, limits.maxEntries, limits.maxFiles, limits.maxBytes)
        ) {
            is ConvertSourceScanner.Outcome.Ok -> l
            is ConvertSourceScanner.Outcome.Failed ->
                return failed(
                    if (l.limit) ConvertErrorKind.LIMIT_EXCEEDED else ConvertErrorKind.INPUT_UNREADABLE,
                    l.message,
                )
        }
    for (ig in listing.ignored) {
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
    if (listing.candidates.isEmpty()) {
        onPlanned(skipped)
        val which = if (okf) "*.kstep.kts" else "*.md"
        return failed(ConvertErrorKind.WRONG_INPUT_TYPE, "no $which files in the input folder", skipped = skipped)
    }
    for (c in listing.candidates) {
        val shown = DisplayName.escaped(c.name)
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
        when (val one = convertOne(text, c.name.substring(0, c.name.length - suffix.length), shown)) {
            is One.Ok -> {
                planned += one.files
                skipped += one.skipped
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
    fun failed(
        kind: ConvertErrorKind,
        message: String,
        source: String? = null,
    ) = ConvertReport.Failed(listOf(ConvertFailure(kind, message, source)), emptyList(), skipped)

    onPlanned(skipped)
    if (planned.size > limits.maxTargets) {
        return failed(ConvertErrorKind.LIMIT_EXCEEDED, "more than ${limits.maxTargets} target files")
    }
    // Case-insensitive: macOS and Windows would silently merge such names.
    val byName = HashMap<String, PlannedFile>()
    for (p in planned) {
        val other = byName.put(p.name.lowercase(Locale.ROOT), p)
        if (other != null) {
            val message =
                if (other.source == p.source) {
                    "the converted file names are not unique"
                } else {
                    "${other.source} and ${p.source} would both write ${DisplayName.escaped(p.name)}"
                }
            return failed(ConvertErrorKind.TARGET_INVALID, message, p.source)
        }
    }
    val conflicts = ArrayList<String>()
    for (p in planned) {
        when (val c = ConvertFiles.checkTarget(outReal, p.name, inputKeys, inputReals)) {
            is ConvertFiles.TargetCheck.Free -> Unit
            is ConvertFiles.TargetCheck.Exists -> if (!command.force) conflicts += DisplayName.escaped(p.name)
            is ConvertFiles.TargetCheck.Invalid ->
                return failed(ConvertErrorKind.TARGET_INVALID, "${DisplayName.escaped(p.name)}: ${c.message}", p.source)
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
        return failed(ConvertErrorKind.TARGET_EXISTS, message)
    }

    val written = ArrayList<WrittenFile>()
    for (p in planned) {
        val shownName = DisplayName.escaped(p.name)
        when (val r = ConvertFiles.writeAtomic(outReal, p.name, p.bytes, command.force, beforeMove)) {
            ConvertFiles.WriteResult.Written -> {
                val w = WrittenFile(shownName, p.source, p.block)
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
