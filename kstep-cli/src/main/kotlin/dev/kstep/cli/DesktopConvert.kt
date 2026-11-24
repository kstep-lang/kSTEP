package dev.kstep.cli

import java.nio.file.Path

/**
 * What the desktop asks of `kstep convert` (ADR-0077). [outDir] null means the CLI default: next to the source file, or
 * into the source folder.
 */
public data class DesktopConvertRequest(
    val input: Path,
    val target: ConvertTarget,
    val outDir: Path?,
    val recursive: Boolean,
    val force: Boolean,
    val title: String? = null,
    val tags: List<String> = emptyList(),
)

/** One written file; every text is already shown safely (escaped), no absolute path. */
public data class ConvertedFile(
    val name: String,
    val source: String,
    val block: Int?,
    val target: String?,
    val blockName: String?,
)

/** Why a source was skipped; [jsonKind] is the stable id of `--output json`. */
public enum class ConvertSkipReason(
    public val jsonKind: String,
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

public data class SkippedSource(
    val source: String,
    val reason: ConvertSkipReason,
    val line: Int?,
)

/** An error or a warning: [code] is the stable `KSTEP-C-0xx`, [kind] its JSON id, [message] already escaped. */
public data class ConvertIssue(
    val code: String,
    val kind: String,
    val message: String,
    val source: String?,
    val line: Int? = null,
)

public sealed interface ConvertOutcome {
    public val written: List<ConvertedFile>
    public val skipped: List<SkippedSource>
    public val warnings: List<ConvertIssue>

    public data class Success(
        override val written: List<ConvertedFile>,
        override val skipped: List<SkippedSource>,
        override val warnings: List<ConvertIssue>,
    ) : ConvertOutcome

    public data class Failure(
        val errors: List<ConvertIssue>,
        /** Escaped names of every existing target, only set by the pre-write check. */
        val conflicts: List<String>,
        override val written: List<ConvertedFile>,
        override val skipped: List<SkippedSource>,
        override val warnings: List<ConvertIssue>,
    ) : ConvertOutcome {
        /** True only for the pre-write check: nothing was written and the conflicts are listed. */
        public val isTargetConflict: Boolean
            get() =
                written.isEmpty() &&
                    conflicts.isNotEmpty() &&
                    errors.singleOrNull()?.code == DesktopConvert.TARGET_EXISTS_CODE
    }
}

/**
 * The desktop's entry to `kstep convert`: the same code path as the command (`computeConvert`), without any printing or
 * JSON. Returns everything (no caps; the desktop caps what it shows). Pure text conversion, never evaluates a script.
 * Exceptions are not caught here.
 */
public object DesktopConvert {
    public const val TARGET_EXISTS_CODE: String = "KSTEP-C-008"

    public fun run(request: DesktopConvertRequest): ConvertOutcome = run(request, ConvertDirLimits())

    internal fun run(
        request: DesktopConvertRequest,
        limits: ConvertDirLimits,
        beforeMove: () -> Unit = {},
        beforeDescend: (Path) -> Unit = {},
    ): ConvertOutcome {
        // The raw path string decides file or folder mode (as on the command line): never normalize it here.
        val command =
            CliCommand.Convert(
                inputPath = request.input.toString(),
                target = request.target,
                outDir = request.outDir?.toString(),
                force = request.force,
                jsonOutput = false,
                title = request.title,
                tags = request.tags,
                recursive = request.recursive,
            )
        val report = computeConvert(command, limits, beforeMove, beforeDescend).report
        val warnings = report.warnings.map { issue(it) }
        return when (report) {
            is ConvertReport.Success ->
                ConvertOutcome.Success(report.written.map { file(it) }, report.skipped.map { skip(it) }, warnings)
            is ConvertReport.Failed ->
                ConvertOutcome.Failure(
                    errors = report.failures.map { issue(it) },
                    conflicts = report.failures.flatMap { it.conflicts },
                    written = report.written.map { file(it) },
                    skipped = report.skipped.map { skip(it) },
                    warnings = warnings,
                )
        }
    }

    private fun file(w: WrittenFile) = ConvertedFile(w.name, w.source, w.block, w.target, w.blockName)

    private fun skip(s: Skipped) = SkippedSource(s.source, reason(s.reason), s.line)

    private fun reason(r: SkipReason): ConvertSkipReason =
        when (r) {
            SkipReason.EMPTY_BLOCK -> ConvertSkipReason.EMPTY_BLOCK
            SkipReason.NO_KSTEP_BLOCK -> ConvertSkipReason.NO_KSTEP_BLOCK
            SkipReason.EMPTY_SCRIPT -> ConvertSkipReason.EMPTY_SCRIPT
            SkipReason.SYMBOLIC_LINK -> ConvertSkipReason.SYMBOLIC_LINK
            SkipReason.NOT_REGULAR -> ConvertSkipReason.NOT_REGULAR
            SkipReason.HIDDEN -> ConvertSkipReason.HIDDEN
            SkipReason.HIDDEN_DIR -> ConvertSkipReason.HIDDEN_DIR
            SkipReason.OUTPUT_DIR -> ConvertSkipReason.OUTPUT_DIR
        }

    private fun issue(f: ConvertFailure) = ConvertIssue(errorCode(f.kind), errorJsonKind(f.kind), f.message, f.source)

    private fun issue(w: ConvertWarning) =
        ConvertIssue(warningCode(w.kind), warningJsonKind(w.kind), w.message, w.source, w.line)

    // Exhaustive on purpose (no else): a new internal value must break the build, not vanish silently.
    private fun errorCode(k: ConvertErrorKind): String =
        when (k) {
            ConvertErrorKind.INVALID_PATH,
            ConvertErrorKind.WRONG_INPUT_TYPE,
            ConvertErrorKind.INPUT_UNREADABLE,
            ConvertErrorKind.INPUT_TOO_LARGE,
            ConvertErrorKind.CONVERSION_REJECTED,
            ConvertErrorKind.INVALID_METADATA,
            ConvertErrorKind.OUTPUT_FOLDER,
            ConvertErrorKind.TARGET_EXISTS,
            ConvertErrorKind.TARGET_INVALID,
            ConvertErrorKind.WRITE_FAILED,
            ConvertErrorKind.LIMIT_EXCEEDED,
            ConvertErrorKind.DEPTH_EXCEEDED,
            ConvertErrorKind.PATH_ESCAPE,
            ConvertErrorKind.INVALID_BLOCK_NAME,
            ConvertErrorKind.DUPLICATE_BLOCK_NAME,
            -> k.code
        }

    private fun errorJsonKind(k: ConvertErrorKind): String =
        when (k) {
            ConvertErrorKind.INVALID_PATH,
            ConvertErrorKind.WRONG_INPUT_TYPE,
            ConvertErrorKind.INPUT_UNREADABLE,
            ConvertErrorKind.INPUT_TOO_LARGE,
            ConvertErrorKind.CONVERSION_REJECTED,
            ConvertErrorKind.INVALID_METADATA,
            ConvertErrorKind.OUTPUT_FOLDER,
            ConvertErrorKind.TARGET_EXISTS,
            ConvertErrorKind.TARGET_INVALID,
            ConvertErrorKind.WRITE_FAILED,
            ConvertErrorKind.LIMIT_EXCEEDED,
            ConvertErrorKind.DEPTH_EXCEEDED,
            ConvertErrorKind.PATH_ESCAPE,
            ConvertErrorKind.INVALID_BLOCK_NAME,
            ConvertErrorKind.DUPLICATE_BLOCK_NAME,
            -> k.jsonKind
        }

    private fun warningCode(k: ConvertWarningKind): String =
        when (k) {
            ConvertWarningKind.UNKNOWN_BLOCK_ATTRIBUTE, ConvertWarningKind.UNTERMINATED_BLOCK_ATTRIBUTE -> k.code
        }

    private fun warningJsonKind(k: ConvertWarningKind): String =
        when (k) {
            ConvertWarningKind.UNKNOWN_BLOCK_ATTRIBUTE, ConvertWarningKind.UNTERMINATED_BLOCK_ATTRIBUTE -> k.jsonKind
        }
}
