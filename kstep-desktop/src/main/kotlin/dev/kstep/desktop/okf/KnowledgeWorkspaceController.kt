package dev.kstep.desktop.okf

import dev.kstep.cli.WorkerTempDir
import dev.kstep.desktop.edit.EditorText
import dev.kstep.desktop.edit.SaveFailure
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.watch.WatchSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

sealed interface WorkspaceState {
    data object None : WorkspaceState

    data class Scanning(
        val dir: Path,
    ) : WorkspaceState

    data class Open(
        val scan: ScanResult.Ok,
    ) : WorkspaceState
}

/** What the preview header says when there is no block to show (ADR-0058, D5). */
sealed interface BlockHint {
    data object NoBlocks : BlockHint

    data object SaveToPreview : BlockHint

    data object Empty : BlockHint

    data class Unclosed(
        val line: Int,
    ) : BlockHint

    data object TooLarge : BlockHint

    data object TooMany : BlockHint

    data object Untrusted : BlockHint

    fun text(): String =
        when (this) {
            NoBlocks -> "No ```kstep blocks in this note."
            SaveToPreview -> "Save to preview this block"
            Empty -> "Empty block"
            is Unclosed -> "Block not closed (line $line)"
            TooLarge -> "Block too large to preview"
            TooMany -> "Too many blocks (only the first ${OkfLimits.MAX_BLOCKS} are available)"
            Untrusted -> "Blocks in this folder are not run (not trusted)"
        }
}

/** `Block 2 of 3 - lines 14-38`; [cursorOutside] is set when the caret is not inside any block (the shown block stays). */
data class BlockHeader(
    val index: Int,
    val count: Int,
    val firstLine: Int,
    val lastLine: Int,
    val cursorOutside: Boolean,
    val hint: BlockHint?,
)

/**
 * The knowledge workspace (ADR-0058): a folder of Markdown notes whose fenced `kstep` blocks are previewed one at a time.
 * It owns no watcher and no run path of its own: the opened note goes through the ordinary [dev.kstep.desktop.run.LiveRunController]
 * (one watcher, one state), which asks [runBlock] for the run and [runFilter] whether a change of the note matters.
 *
 * Threads: [openFolder] scans on its own executor; [onBuffer] runs on the UI thread and only schedules; [runBlock] runs on
 * the run thread; [runFilter] on the watcher thread. The shared fields are guarded by [lock] or are volatile.
 */
class KnowledgeWorkspaceController(
    private val scanner: WorkspaceScanner = WorkspaceScanner(),
    private val workerRun: (Path, Long, (Process) -> Unit) -> RunResult,
    private val sessionDir: Path,
    private val scheduler: (Long, () -> Unit) -> AutoCloseable,
    private val loader: (Path) -> EditorText.Loaded = EditorText::load,
    private val scanExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "kstep-desktop-scan").apply { isDaemon = true } },
) : AutoCloseable {
    private class ScanToken {
        @Volatile var cancelled = false
    }

    private class Sent(
        val index: Int,
        val hash: ByteArray,
    )

    private val lock = Any()
    private val mutableWorkspace = MutableStateFlow<WorkspaceState>(WorkspaceState.None)
    private val mutableHeader = MutableStateFlow<BlockHeader?>(null)
    private val mutableError = MutableStateFlow<DesktopError?>(null)

    val workspace: StateFlow<WorkspaceState> = mutableWorkspace.asStateFlow()
    val header: StateFlow<BlockHeader?> = mutableHeader.asStateFlow()
    val error: StateFlow<DesktopError?> = mutableError.asStateFlow()

    /** Set by the UI to `controller::forceRun`. */
    @Volatile
    var onRunRequested: (() -> Unit)? = null

    @Volatile private var rootReal: Path? = null

    /**
     * Whether `kstep` blocks of the open folder may run (ADR-0058): a note is untrusted text, and a block is arbitrary Kotlin.
     * Per session and per folder only: reset by every [openFolder] and [closeWorkspace], never persisted.
     */
    @Volatile
    var trusted: Boolean = false
        private set

    /** The user allowed running the blocks of the open folder; a shown note runs now. */
    fun trustFolder() {
        if (rootReal == null) return
        trusted = true
        onRunRequested?.invoke()
    }

    @Volatile private var currentMd: Path? = null

    @Volatile private var activeIndex = 0

    @Volatile private var sent: Sent? = null

    /** Per block of the note on disk: the hash of its content (`null` if the block is not runnable). */
    @Volatile private var diskHashes: List<ByteArray?>? = null

    private var token: ScanToken? = null
    private var scanFuture: Future<*>? = null
    private var debounce: AutoCloseable? = null
    private var lastText: String? = null
    private var lastCaret = 0
    private var closed = false

    // ---- workspace ----

    fun openFolder(dir: Path) {
        val previous: WorkspaceState
        val mine = ScanToken()
        synchronized(lock) {
            if (closed) return
            token?.cancelled = true
            scanFuture?.cancel(true)
            trusted = false
            previous = mutableWorkspace.value.let { if (it is WorkspaceState.Scanning) WorkspaceState.None else it }
            token = mine
            mutableWorkspace.value = WorkspaceState.Scanning(dir)
            mutableError.value = null
        }
        val future =
            try {
                scanExecutor.submit {
                    val result =
                        try {
                            scanner.scan(dir) { mine.cancelled }
                        } catch (e: Exception) {
                            ScanResult.Failed(
                                DesktopError(
                                    DesktopErrorCodes.WORKSPACE_UNAVAILABLE,
                                    null,
                                    null,
                                    "The folder cannot be scanned (${e::class.simpleName}).",
                                ),
                            )
                        }
                    synchronized(lock) {
                        if (mine.cancelled || closed) return@submit
                        when (result) {
                            is ScanResult.Ok -> {
                                rootReal = result.root
                                mutableWorkspace.value = WorkspaceState.Open(result)
                                logger.info {
                                    "workspace scanned files=${result.entries.size} partial=${result.partial.size} " +
                                        "links=${result.linksSkipped}"
                                }
                            }
                            is ScanResult.Failed -> {
                                mutableWorkspace.value = previous
                                mutableError.value = result.error
                            }
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                return
            }
        synchronized(lock) { scanFuture = future }
    }

    fun cancelScan() {
        synchronized(lock) {
            token?.cancelled = true
            scanFuture?.cancel(true)
            if (mutableWorkspace.value is WorkspaceState.Scanning) mutableWorkspace.value = WorkspaceState.None
        }
    }

    fun closeWorkspace() {
        synchronized(lock) {
            token?.cancelled = true
            scanFuture?.cancel(true)
            debounce?.close()
            debounce = null
            rootReal = null
            trusted = false
            currentMd = null
            activeIndex = 0
            sent = null
            diskHashes = null
            lastText = null
            mutableWorkspace.value = WorkspaceState.None
            mutableHeader.value = null
            mutableError.value = null
        }
    }

    fun clearError() {
        mutableError.value = null
    }

    fun resolveForOpen(path: Path): PathCheck {
        val root = rootReal ?: return PathCheck.Rejected("no workspace is open")
        val check = WorkspacePaths.resolve(root, path)
        if (check is PathCheck.Rejected) {
            mutableError.value =
                DesktopError(DesktopErrorCodes.WORKSPACE_PATH_REJECTED, null, null, "Cannot open: ${check.reason}.")
        }
        return check
    }

    fun isWorkspaceFile(path: Path): Boolean {
        val root = rootReal ?: return false
        return WorkspaceScanner.isMarkdown(path.fileName?.toString().orEmpty()) &&
            path.toAbsolutePath().normalize().startsWith(root)
    }

    /** A note was opened in the editor: the first block is shown until the caret says otherwise. */
    fun onFileOpened(md: Path) {
        synchronized(lock) {
            debounce?.close()
            debounce = null
            currentMd = md.toAbsolutePath().normalize()
            activeIndex = 0
            sent = null
            diskHashes = null
            lastText = null
            mutableHeader.value = null
        }
    }

    // ---- caret and blocks ----

    /** Called on every change of buffer or caret. Cheap: the analysis is cached by text, the run is debounced. */
    fun onBuffer(
        text: String,
        caret: Int,
    ) {
        val analysis = NoteAnalyzer.analyze(text)
        synchronized(lock) {
            if (closed || currentMd == null) return
            lastText = text
            lastCaret = caret
            val line = analysis.lines.lineOf(caret)
            val at = MarkdownBlockExtractor.blockAtLine(analysis.extraction, line)
            if (at != null && at != activeIndex) {
                activeIndex = at
                debounce?.close()
                debounce = if (!trusted) null else scheduler(OkfLimits.DEBOUNCE_MS) { fire() }
            }
            publishHeader(analysis, outside = at == null)
        }
    }

    private fun publishHeader(
        analysis: NoteAnalysis,
        outside: Boolean,
    ) {
        val ex = analysis.extraction
        if (ex.blocks.isEmpty()) {
            mutableHeader.value = BlockHeader(0, 0, 0, 0, outside, BlockHint.NoBlocks)
            return
        }
        val index = activeIndex.coerceIn(0, ex.blocks.size - 1)
        val block = ex.blocks[index]
        val disk = diskHashes
        val hint =
            when {
                block.state == BlockState.UNCLOSED -> BlockHint.Unclosed(block.openLine)
                block.state == BlockState.TOO_LARGE -> BlockHint.TooLarge
                block.state == BlockState.EMPTY -> BlockHint.Empty
                disk != null && !matchesDisk(analysis.text, block, disk.getOrNull(index)) -> BlockHint.SaveToPreview
                ex.tooManyBlocks && index == ex.blocks.size - 1 -> BlockHint.TooMany
                else -> null
            }
        mutableHeader.value =
            BlockHeader(
                index,
                ex.blocks.size,
                block.contentStartLine,
                maxOf(block.contentStartLine, block.contentEndLine),
                outside,
                hint,
            )
    }

    /** The buffer's block is what the preview of the saved note shows (the index is the buffer's, the disk may differ). */
    private fun matchesDisk(
        text: String,
        block: FenceBlock,
        diskHash: ByteArray?,
    ): Boolean = diskHash != null && sha256(MarkdownBlockExtractor.content(text, block)).contentEquals(diskHash)

    private fun fire() {
        val md = currentMd ?: return
        if (!trusted) return
        if (diskDiffers(md)) onRunRequested?.invoke()
    }

    /** The caret offset for "next/previous block", or `null` at the ends. */
    fun neighbourCaret(
        text: String,
        caret: Int,
        forward: Boolean,
    ): Int? {
        val analysis = NoteAnalyzer.analyze(text)
        val blocks = analysis.extraction.blocks
        if (blocks.isEmpty()) return null
        val line = analysis.lines.lineOf(caret)
        val current = MarkdownBlockExtractor.blockAtLine(analysis.extraction, line)
        val target =
            if (current != null) {
                blocks.getOrNull(if (forward) current + 1 else current - 1)
            } else if (forward) {
                blocks.firstOrNull { it.openLine > line }
            } else {
                blocks.lastOrNull { it.openLine < line }
            }
        return target?.contentStart
    }

    // ---- run ----

    /** Whether a change of the note on disk starts a run: only if the shown block itself changed. */
    fun runFilter(signal: WatchSignal): Boolean {
        if (signal != WatchSignal.CHANGED) return true
        val md = currentMd ?: return true
        return diskDiffers(md)
    }

    private fun diskDiffers(md: Path): Boolean {
        val last = sent ?: return true
        val index = activeIndex
        if (last.index != index) return true
        val root = rootReal ?: return true
        val checked =
            when (val c = WorkspacePaths.resolve(root, md)) {
                is PathCheck.Ok -> c.path
                is PathCheck.Rejected -> return true // runBlock decides and reports
            }
        val loaded = loader(checked)
        if (loaded !is EditorText.Loaded.Ok) return true
        val fm = FrontmatterParser.parse(loaded.text)
        val ex = MarkdownBlockExtractor.extract(loaded.text, fm.bodyStartLine)
        val block = ex.blocks.getOrNull(index) ?: return true
        if (block.state != BlockState.OK) return true
        return !sha256(MarkdownBlockExtractor.content(loaded.text, block)).contentEquals(last.hash)
    }

    /** The run of the note [md]: its active block, from the saved file. Runs on the run thread. */
    fun runBlock(
        md: Path,
        generation: Long,
        started: (Process) -> Unit,
    ): RunResult {
        val root = rootReal ?: return failure(DesktopErrorCodes.WORKSPACE_UNAVAILABLE, null, "No workspace is open.")
        if (!trusted) return RunResult.Skipped(BlockHint.Untrusted.text())
        val checked =
            when (val c = WorkspacePaths.resolve(root, md)) {
                is PathCheck.Ok -> c.path
                is PathCheck.Rejected ->
                    return failure(DesktopErrorCodes.WORKSPACE_PATH_REJECTED, null, "Cannot run: ${c.reason}.")
            }
        val loaded = loader(checked)
        if (loaded !is EditorText.Loaded.Ok) {
            loaded as EditorText.Loaded.Refused
            sent = null
            return failure(loaded.code, null, "Cannot read the note: ${loaded.reason}.")
        }
        val text = loaded.text
        val fm = FrontmatterParser.parse(text)
        val ex = MarkdownBlockExtractor.extract(text, fm.bodyStartLine)
        val index = activeIndex
        synchronized(lock) {
            diskHashes =
                ex.blocks.map { b ->
                    if (b.state == BlockState.OK) sha256(MarkdownBlockExtractor.content(text, b)) else null
                }
            lastText?.let { t ->
                val a = NoteAnalyzer.analyze(t)
                publishHeader(
                    a,
                    outside =
                        MarkdownBlockExtractor.blockAtLine(a.extraction, a.lines.lineOf(lastCaret)) == null,
                )
            }
        }
        sent = null
        if (ex.blocks.isEmpty()) {
            return if (ex.tooManyBlocks) {
                failure(
                    DesktopErrorCodes.WORKSPACE_LIMIT,
                    null,
                    BlockHint.TooMany.text(),
                )
            } else {
                RunResult.Skipped(BlockHint.NoBlocks.text())
            }
        }
        val block = ex.blocks.getOrNull(index) ?: return RunResult.Skipped(BlockHint.SaveToPreview.text())
        when (block.state) {
            BlockState.EMPTY -> return RunResult.Skipped(BlockHint.Empty.text())
            BlockState.UNCLOSED ->
                return failure(
                    DesktopErrorCodes.BLOCK_NOT_CLOSED,
                    block.openLine,
                    "Block not closed (line ${block.openLine})",
                )
            BlockState.TOO_LARGE ->
                return failure(
                    DesktopErrorCodes.WORKSPACE_LIMIT,
                    block.openLine,
                    "Block too large to preview (limit ${OkfLimits.MAX_BLOCK_BYTES / 1024} KiB)",
                )
            BlockState.OK -> {}
        }
        val content = MarkdownBlockExtractor.content(text, block)
        val map = BlockLineMap(block.contentStartLine, block.contentEndLine - block.contentStartLine + 1)
        val hash = sha256(content)
        val label = "block ${index + 1}"
        val result =
            try {
                BlockSessionFile.withBlockFile(sessionDir, generation, content) { file ->
                    sent = Sent(index, hash)
                    relabel(workerRun(file, generation, started), file, map, label)
                }
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                sent = null
                failure(DesktopErrorCodes.RESULT_UNREADABLE, null, "Cannot prepare the block for the preview.")
            } catch (_: java.io.IOException) {
                sent = null
                failure(DesktopErrorCodes.RESULT_UNREADABLE, null, "Cannot prepare the block for the preview.")
            }
        logger.info { "block run gen=$generation index=$index outcome=${result::class.simpleName}" }
        return result
    }

    /** Lines to the note, the session file's path and name to "block K" (no temp path reaches the screen). */
    private fun relabel(
        result: RunResult,
        file: Path,
        map: BlockLineMap,
        label: String,
    ): RunResult {
        fun clean(m: String) = m.replace(file.toString(), label).replace(file.fileName.toString(), label)
        return when (result) {
            is RunResult.Failure ->
                RunResult.Failure(
                    result.errors.map { e -> e.copy(line = map.toMarkdown(e.line), message = clean(e.message)) },
                )
            is RunResult.Success -> RunResult.Success(cleanModel(result.model, ::clean))
            is RunResult.NativeUnavailable -> RunResult.NativeUnavailable(clean(result.reason))
            is RunResult.Skipped -> result
        }
    }

    private fun cleanModel(
        model: DesktopModel,
        clean: (String) -> String,
    ): DesktopModel =
        model.copy(
            notes = model.notes.map(clean),
            warnings = model.warnings.map { it.copy(message = clean(it.message)) },
        )

    /** `guardSave` of the editor session: a workspace note is only written if it passes the path rule (D13). */
    fun guardSave(path: Path): SaveFailure? {
        val root = rootReal ?: return null
        if (!isWorkspaceFile(path)) return null
        return when (WorkspacePaths.resolve(root, path)) {
            is PathCheck.Ok -> null
            is PathCheck.Rejected -> {
                // a note deleted meanwhile is still saved (the editor's conflict flow handles it) if its folder is inside
                val absolute = path.toAbsolutePath().normalize()
                val gone = !Files.exists(absolute, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                val parentOk =
                    try {
                        absolute.parent?.toRealPath()?.startsWith(root) == true
                    } catch (_: Exception) {
                        false
                    }
                if (gone && parentOk) null else SaveFailure.PATH_REJECTED
            }
        }
    }

    private fun failure(
        code: String,
        line: Int?,
        message: String,
    ): RunResult.Failure = RunResult.Failure(listOf(DesktopError(code, line, null, message)))

    private fun sha256(content: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            token?.cancelled = true
            scanFuture?.cancel(true)
            debounce?.close()
            debounce = null
        }
        scanExecutor.shutdownNow()
        WorkerTempDir.deleteRecursively(sessionDir)
    }

    companion object {
        /** The production wiring: an own session directory and worker, a daemon debounce scheduler. */
        fun production(): KnowledgeWorkspaceController {
            val dir = BlockSessionFile.createSessionDir()
            Runtime.getRuntime().addShutdownHook(
                Thread({ WorkerTempDir.deleteRecursively(dir) }, "kstep-knowledge-cleanup"),
            )
            val runner = WorkerRunner(dir)
            val timer =
                ScheduledThreadPoolExecutor(1) { r -> Thread(r, "kstep-desktop-debounce").apply { isDaemon = true } }
                    .apply { removeOnCancelPolicy = true }
            return KnowledgeWorkspaceController(
                workerRun = { file, gen, started -> runner.run(file, gen, started) },
                sessionDir = dir,
                scheduler = { delayMs, task ->
                    val future = timer.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                    AutoCloseable { future.cancel(false) }
                },
            )
        }
    }
}
