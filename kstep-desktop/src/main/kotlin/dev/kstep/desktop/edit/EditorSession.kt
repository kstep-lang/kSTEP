package dev.kstep.desktop.edit

import dev.kstep.desktop.draft.Draft
import dev.kstep.desktop.draft.DraftDebounce
import dev.kstep.desktop.draft.DraftKey
import dev.kstep.desktop.draft.DraftPolicy
import dev.kstep.desktop.draft.DraftStore
import dev.kstep.desktop.draft.FileState
import dev.kstep.desktop.draft.OfferDecision
import dev.kstep.desktop.draft.OfferKind
import dev.kstep.desktop.draft.RecoveryOffer
import dev.kstep.desktop.draft.decideOffer
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.watch.WatchSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * What the editor UI shows. [revision] changes when the text is replaced from outside the text field (open, undo, redo,
 * reload); then the field takes [text] and [caret] over. [pulse] counts "Save pressed during a conflict".
 */
class EditorView(
    val state: EditorState,
    val revision: Int,
    val caret: Int,
    val pulse: Int,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** A crash draft of the open file waits for Restore or Discard (ADR-0084); Save is blocked meanwhile. */
    val draftOffer: RecoveryOffer? = null,
    /** The header of a draft of a file that is not open (shown only while no file is open). */
    val draftElsewhere: Draft? = null,
    /** The code of the once-per-session notice that drafts are not written. */
    val draftNotice: String? = null,
    /** Start of the selection that ends at [caret] (ADR-0091); equal to [caret] when nothing is selected. */
    val selectionStart: Int = caret,
) {
    val text: String get() = (state as? EditorState.Editing)?.text ?: ""
    val dirty: Boolean get() = (state as? EditorState.Editing)?.dirty == true
}

/** Runs [task] after [delayMs]; closing the result cancels it. Injectable so the tests need no real time. */
fun interface DraftTimer {
    fun schedule(
        delayMs: Long,
        task: Runnable,
    ): AutoCloseable
}

/**
 * The coordinator around the pure [EditorSessionMachine] (ADR-0052): it owns the undo history and runs the file work
 * on one IO thread, never on the UI thread and never under the run controller's lock. Loader, saver and clock are
 * injected so the tests run with fakes. Every state change goes through one lock.
 */
class EditorSession(
    private val loader: (Path) -> EditorText.Loaded = EditorText::load,
    private val saver: (Path, ByteArray, ByteArray?, Boolean) -> AtomicSaver.Result =
        AtomicSaver().let { s -> { p, b, h, f -> s.save(p, b, h, f) } },
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "kstep-desktop-editor").apply {
                isDaemon =
                    true
            }
        },
    /** The crash drafts (ADR-0084); `null` switches them off. */
    private val drafts: DraftStore? = null,
    private val draftTimer: DraftTimer? = null,
) : AutoCloseable {
    private val machine = EditorSessionMachine()
    private val history = EditHistory(clock = clock)
    private val lock = Any()
    private var state: EditorState = EditorState.NoFile
    private var revision = 0
    private var caret = 0
    private var selectionStart = 0 // valid only while selectionRevision == revision (ADR-0091)
    private var selectionRevision = -1
    private var pulse = 0
    private var offer: RecoveryOffer? = null
    private var elsewhere: Draft? = null
    private var notice: String? = null
    private var noticeShown = false
    private var draftCreated: Long? = null
    private var finalized = false
    private val debounce = DraftDebounce()
    private var draftTask: AutoCloseable? = null
    private var ownTimer: ScheduledThreadPoolExecutor? = null
    private val timer: DraftTimer =
        draftTimer
            ?: DraftTimer { delayMs, task ->
                val executor =
                    synchronized(this) {
                        ownTimer
                            ?: ScheduledThreadPoolExecutor(1) { r ->
                                Thread(r, "kstep-desktop-draft-timer").apply { isDaemon = true }
                            }.apply { removeOnCancelPolicy = true }.also { ownTimer = it }
                    }
                val future: ScheduledFuture<*> = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                AutoCloseable { future.cancel(false) }
            }
    private val mutableView = MutableStateFlow(EditorView(state, 0, 0, 0))

    val view: StateFlow<EditorView> = mutableView.asStateFlow()

    private fun publish() {
        mutableView.value =
            EditorView(
                state,
                revision,
                caret,
                pulse,
                history.canUndo,
                history.canRedo,
                offer,
                elsewhere,
                notice,
                if (selectionRevision == revision) selectionStart else caret,
            )
    }

    /** Loads [path] into the editor (replaces any current buffer; the caller has already handled unsaved changes). */
    fun open(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        submit {
            leaving()
            val loaded = loader(normalized)
            val decision = drafts?.let { decideFor(normalized, loaded) } ?: OfferDecision.None
            synchronized(lock) {
                history.clear()
                state = machine.reduce(state, EditorEvent.Opened(normalized, loaded)).state
                offer = (decision as? OfferDecision.Offer)?.offer
                elsewhere = null
                revision++
                caret = 0
                publish()
            }
        }
    }

    /** Reads the draft of [path] and compares it with the file that was just loaded (on the IO thread). */
    private fun decideFor(
        path: Path,
        loaded: EditorText.Loaded,
    ): OfferDecision {
        val store = drafts ?: return OfferDecision.None
        val key = DraftKey.normalize(path)
        val read = store.read(key)
        if (read is DraftStore.Read.Unavailable) {
            showNotice(DesktopErrorCodes.DRAFT_STORE_UNAVAILABLE)
            return OfferDecision.None
        }
        val draft = (read as? DraftStore.Read.Found)?.draft
        val file =
            when (loaded) {
                is EditorText.Loaded.Ok -> FileState.Editable(loaded.sha256, loaded.text)
                is EditorText.Loaded.Refused ->
                    if (loaded.code == DesktopErrorCodes.FILE_MISSING) {
                        FileState.Missing
                    } else {
                        FileState.NotEditable(loaded.sha256, loaded.reason)
                    }
            }
        val decision = decideOffer(draft, file)
        if (decision is OfferDecision.DeleteSilently) store.delete(key)
        return decision
    }

    /**
     * The open file is being left (another file, close, quit): its draft is deleted, unless an unanswered offer holds
     * it, which stays byte for byte (ADR-0084). Runs the file work on the calling thread: call it on the IO thread.
     */
    private fun leaving() {
        val store = drafts ?: return
        val path =
            synchronized(lock) {
                draftTask?.close()
                draftTask = null
                debounce.reset()
                draftCreated = null
                if (offer != null) null else state.path
            }
        if (path != null) store.delete(DraftKey.normalize(path))
    }

    /** Closes the file (the caller has already handled unsaved changes): the editor shows "No script open". */
    fun clear() {
        val path: Path?
        synchronized(lock) {
            path = if (drafts != null && offer == null) state.path else null
            draftTask?.close()
            draftTask = null
            debounce.reset()
            draftCreated = null
            history.clear()
            state = EditorState.NoFile
            offer = null
            revision++
            caret = 0
            publish()
        }
        // on the IO thread: after a draft write that was already running
        if (path != null) submit { drafts?.delete(DraftKey.normalize(path)) }
    }

    /** The text field changed the text (typing, paste, cut); [caretAfter] is its caret afterwards. */
    fun onTextChanged(
        newText: String,
        caretAfter: Int,
    ) {
        synchronized(lock) {
            val editing = state as? EditorState.Editing ?: return
            if (editing.text == newText) {
                caret = caretAfter
                return
            }
            val newline =
                newText.length > editing.text.length &&
                    caretAfter in 1..newText.length &&
                    newText[caretAfter - 1] == '\n'
            history.record(editing.text, caret, EditKind.TYPING, caretAfter, newline)
            caret = caretAfter
            apply(EditorEvent.TextEdited(newText))
        }
    }

    fun onCaretMoved(caretNow: Int) {
        synchronized(lock) { caret = caretNow }
    }

    /** A programmatic edit (Enter indentation, Tab): the text field shows the result. */
    fun replaceText(
        newText: String,
        newCaret: Int,
    ) {
        synchronized(lock) {
            val editing = state as? EditorState.Editing ?: return
            if (editing.text == newText) return
            history.record(editing.text, caret, EditKind.OTHER, newCaret)
            caret = newCaret
            revision++
            apply(EditorEvent.TextEdited(newText))
        }
    }

    /**
     * Applies an accepted AI proposal (ADR-0056): one undo step ([replaceText]), the buffer becomes dirty, nothing is
     * written to disk (saving stays an explicit act). The caret is kept where it fits.
     */
    fun applyProposal(text: String) {
        synchronized(lock) { replaceText(text, minOf(caret, text.length)) }
    }

    /**
     * Applies [text] only if the editor text still equals [expected], atomically under the lock (ADR-0069): a subset
     * of an AI proposal was checked against [expected], so it must not land on a text that changed meanwhile. One undo
     * step. Returns false and changes nothing otherwise.
     */
    fun applyProposalIf(
        expected: String,
        text: String,
    ): Boolean {
        synchronized(lock) {
            val editing = state as? EditorState.Editing ?: return false
            if (editing.text != expected) return false
            replaceText(text, minOf(caret, text.length))
            return true
        }
    }

    /** Moves the caret (an error-bar click) without changing the text. */
    fun requestCaret(offset: Int) {
        synchronized(lock) {
            caret = offset
            revision++
            publish()
        }
    }

    /**
     * Selects `[start, end)` and puts the caret at [end] (ADR-0091). Ignored (`false`) unless the buffer is still at
     * [expectedRevision] and `0 <= start <= end <= text.length`. No history entry; any later revision drops the selection.
     */
    fun requestSelection(
        start: Int,
        end: Int,
        expectedRevision: Int,
    ): Boolean =
        synchronized(lock) {
            val editing = state as? EditorState.Editing ?: return false
            if (revision != expectedRevision || start < 0 || start > end || end > editing.text.length) return false
            caret = end
            revision++
            selectionStart = start
            selectionRevision = revision
            publish()
            true
        }

    fun undo() = stepHistory(redo = false)

    fun redo() = stepHistory(redo = true)

    private fun stepHistory(redo: Boolean) {
        synchronized(lock) {
            val editing = state as? EditorState.Editing ?: return
            val entry = (if (redo) history.redo(editing.text, caret) else history.undo(editing.text, caret)) ?: return
            caret = entry.caret.coerceIn(0, entry.text.length)
            revision++
            apply(EditorEvent.TextEdited(entry.text))
        }
    }

    fun save() =
        synchronized(lock) {
            if (offer != null) {
                // the buffer is not the user's yet: Save would overwrite the file under an unanswered question
                pulse++
                publish()
            } else {
                apply(EditorEvent.SaveRequested)
            }
        }

    fun reload() = synchronized(lock) { apply(EditorEvent.ReloadChosen) }

    fun keepMine() = synchronized(lock) { apply(EditorEvent.KeepMineChosen) }

    /** From the run controller's watcher (called on the watcher thread, outside the controller's lock). */
    fun onDiskSignal(signal: WatchSignal) {
        if (signal == WatchSignal.MISSING) {
            synchronized(lock) { apply(EditorEvent.DiskMissing) }
            return
        }
        val path = synchronized(lock) { state.path } ?: return
        submit {
            val loaded = loader(path)
            synchronized(lock) {
                if (state.path == path) apply(EditorEvent.DiskChanged(loaded))
            }
        }
    }

    /**
     * Saves and waits (at most [timeoutMs]); `true` if nothing unsaved remains. Must not be called on the IO thread.
     * Used when closing the window or opening another file.
     */
    fun saveAndWait(timeoutMs: Long = 15_000): Boolean {
        if (synchronized(lock) { offer != null }) {
            save()
            return false
        }
        save()
        val done = CompletableFuture<Boolean>()
        try {
            io.submit {
                val ok =
                    synchronized(lock) {
                        (state as? EditorState.Editing)?.let { !it.dirty && it.saveError == null }
                            ?: true
                    }
                done.complete(ok)
            }
        } catch (_: RejectedExecutionException) {
            return false
        }
        return try {
            done.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            false
        }
    }

    /** What closing or opening another file must ask: `true` if there are unsaved changes. */
    fun hasUnsavedChanges(): Boolean = synchronized(lock) { (state as? EditorState.Editing)?.dirty == true }

    private fun apply(event: EditorEvent) {
        val previous = state
        val transition = machine.reduce(state, event)
        state = transition.state
        // The field must take over the text when the buffer appears or disappears (e.g. preview-only to editable).
        if ((previous is EditorState.Editing) != (state is EditorState.Editing)) revision++
        for (effect in transition.effects) {
            when (effect) {
                is EditorEffect.WriteFile -> submitWrite(effect)
                is EditorEffect.ReloadApplied -> {
                    val editing = state as EditorState.Editing
                    history.record(effect.previousText, caret, EditKind.RELOAD, 0)
                    val (line, column) = CaretMapper.lineColumn(effect.previousText, caret)
                    caret = CaretMapper.toOffset(editing.text, line, column) ?: 0
                    revision++
                }
                EditorEffect.PulseBanner -> pulse++
                EditorEffect.ContinueClose, EditorEffect.AskCloseDecision -> Unit
            }
        }
        if (event is EditorEvent.TextEdited || event is EditorEvent.DraftRestoredMissing) scheduleDraft()
        if (event is EditorEvent.SaveFinished && event.result is SaveOutcome.Saved) afterSaved()
        if (event is EditorEvent.DiskChanged || event === EditorEvent.DiskMissing) refreshOffer()
        publish()
    }

    /**
     * The file changed under an unanswered offer: the kind decided at open() may no longer be true, so it is derived
     * again from the buffer's state. This keeps the banner's warning ("replaces the file") and the Restore button honest.
     */
    private fun refreshOffer() {
        val o = offer ?: return
        val current = state
        val (kind, reason) =
            when {
                current is EditorState.Unavailable && current.code == DesktopErrorCodes.FILE_MISSING ->
                    OfferKind.DELETED to null
                current is EditorState.Unavailable -> OfferKind.NOT_EDITABLE to current.reason
                current is EditorState.Editing -> {
                    val gone = current.deletedOnDisk || current.conflict?.kind == ConflictKind.DELETED
                    (if (gone) OfferKind.DELETED else OfferKind.CHANGED) to null
                }
                else -> return
            }
        if (kind == o.kind && reason == o.notEditableReason) return
        offer = RecoveryOffer(o.path, kind, o.written, o.body, reason)
    }

    /** A dirty buffer needs a draft soon; each change moves the write 2 s on, but never later than 20 s after the first. */
    private fun scheduleDraft() {
        if (drafts == null || finalized || offer != null) return
        val editing = state as? EditorState.Editing ?: return
        // undone back to the saved text: the draft that exists stays (until close or save), no new one is needed
        if (!editing.dirty) return
        val now = clock()
        debounce.onChange(now)
        val delay = ((debounce.dueAt() ?: now) - now).coerceAtLeast(0)
        draftTask?.close()
        draftTask = timer.schedule(delay) { submit { writeDraftTask() } }
    }

    /** The file is saved: the draft is obsolete. Deleted on the IO thread, so it cannot race a draft write. */
    private fun afterSaved() {
        val store = drafts ?: return
        val path = state.path ?: return
        draftTask?.close()
        draftTask = null
        debounce.reset()
        draftCreated = null
        submit { store.delete(DraftKey.normalize(path)) }
        // typed while the save was in flight: the buffer is dirty again and needs a new draft (delete -> write order)
        scheduleDraft()
    }

    private class DraftSnapshot(
        val path: Path,
        val text: String,
        val baseHash: ByteArray?,
        val created: Long?,
    )

    private fun snapshotLocked(): DraftSnapshot? {
        if (drafts == null || finalized || offer != null) return null
        val editing = state as? EditorState.Editing ?: return null
        if (!editing.dirty) return null
        return DraftSnapshot(editing.path, editing.text, editing.baseHash, draftCreated)
    }

    private fun writeDraftTask() {
        val snapshot = synchronized(lock) { snapshotLocked() } ?: return
        writeDraft(snapshot)
    }

    private fun writeDraft(snapshot: DraftSnapshot) {
        val store = drafts ?: return
        val now = clock()
        val created = snapshot.created ?: now
        val key = DraftKey.normalize(snapshot.path)
        val stat =
            try {
                Files.readAttributes(snapshot.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) {
                null
            }
        val draft =
            Draft(
                key,
                stat?.lastModifiedTime()?.toMillis() ?: -1,
                stat?.size() ?: -1,
                snapshot.baseHash,
                created,
                maxOf(now, created),
                snapshot.text,
            )
        when (store.write(draft)) {
            DraftStore.Write.Written ->
                synchronized(lock) {
                    if (state.path == snapshot.path && draftCreated == null) draftCreated = created
                    debounce.onWritten()
                }
            // a failed attempt restarts the debounce window: otherwise every keystroke would retry at once (delay 0)
            DraftStore.Write.TooLarge -> {
                synchronized(lock) { debounce.onWritten() }
                showNotice(DesktopErrorCodes.DRAFT_TOO_LARGE)
            }
            DraftStore.Write.Failed -> {
                synchronized(lock) { debounce.onWritten() }
                showNotice(DesktopErrorCodes.DRAFT_WRITE_FAILED)
            }
            DraftStore.Write.Unavailable -> {
                synchronized(lock) { debounce.onWritten() }
                showNotice(DesktopErrorCodes.DRAFT_STORE_UNAVAILABLE)
            }
        }
    }

    /** The one-time notice that drafts are not written ("Unsaved changes are not protected"); later ones are only logged. */
    private fun showNotice(code: String) {
        synchronized(lock) {
            if (noticeShown) return
            noticeShown = true
            notice = code
            publish()
        }
    }

    fun dismissNotice() =
        synchronized(lock) {
            notice = null
            publish()
        }

    /** Restore: the draft becomes the buffer, as one undo step. */
    fun restoreDraft() {
        synchronized(lock) {
            val o = offer ?: return
            if (o.kind == OfferKind.NOT_EDITABLE) return
            val current = state
            if (current is EditorState.Unavailable &&
                current.code == DesktopErrorCodes.FILE_MISSING &&
                o.kind == OfferKind.DELETED
            ) {
                offer = null
                draftCreated = o.written
                history.record("", 0, EditKind.OTHER, 0)
                caret = 0
                revision++
                apply(EditorEvent.DraftRestoredMissing(o.body))
                return
            }
            val editing = current as? EditorState.Editing ?: return
            offer = null
            draftCreated = o.written
            if (editing.text == o.body) {
                val path = editing.path
                submit { drafts?.delete(DraftKey.normalize(path)) }
                publish()
            } else {
                replaceText(o.body, minOf(caret, o.body.length))
            }
        }
    }

    /** Discard: the draft is deleted at once; the buffer stays what it is. */
    fun discardDraft() {
        val path: Path?
        synchronized(lock) {
            if (offer == null) return
            path = offer?.path
            offer = null
            scheduleDraft()
            publish()
        }
        if (path != null) submit { drafts?.delete(DraftKey.normalize(path)) }
    }

    /** At start without a file: looks for a draft of a file that is not open (the newest script draft). */
    fun lookForDraftsElsewhere() {
        val store = drafts ?: return
        submit {
            store.housekeeping()
            if (!store.available) {
                showNotice(DesktopErrorCodes.DRAFT_STORE_UNAVAILABLE)
                return@submit
            }
            val found =
                store.newestHeader {
                    it.fileName
                        ?.toString()
                        ?.lowercase()
                        ?.endsWith(".kstep.kts") == true
                }
            synchronized(lock) {
                if (state is EditorState.NoFile && found != null) {
                    elsewhere = found
                    publish()
                }
            }
        }
    }

    /**
     * The window is about to close after the user's answer (Save, or Don't Save): the draft of the open file is
     * deleted - unless an unanswered offer holds it - and nothing is written afterwards. Waits for the IO thread.
     */
    fun finishForQuit() {
        if (drafts == null) return
        synchronized(lock) {
            finalized = true
            draftTask?.close()
            draftTask = null
            debounce.reset()
        }
        val done = CompletableFuture<Unit>()
        try {
            io.submit {
                try {
                    leaving()
                } finally {
                    done.complete(Unit)
                }
            }
            done.get(DraftPolicy.SHUTDOWN_BUDGET_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // the executor is closed or slow: the shutdown hook must not write either (finalized)
            logger.debug { "the draft could not be cleaned up on quit" }
        }
    }

    /**
     * The JVM shuts down (SIGTERM, log-out): one last draft write of an unsaved buffer on an own thread, so that a
     * closed or blocked IO thread cannot hold the shutdown longer than [budgetMs].
     */
    fun flushForShutdown(budgetMs: Long = DraftPolicy.SHUTDOWN_BUDGET_MS) {
        if (drafts == null) return
        val snapshot = synchronized(lock) { snapshotLocked() } ?: return
        val thread = Thread({ writeDraft(snapshot) }, "kstep-draft-flush").apply { isDaemon = true }
        thread.start()
        thread.join(budgetMs)
    }

    /** Test hook: writes the draft of the current buffer now, on the IO thread, and waits for it. */
    internal fun writeDraftNow() {
        val done = CompletableFuture<Unit>()
        io.submit {
            try {
                writeDraftTask()
            } finally {
                done.complete(Unit)
            }
        }
        done.get(30, TimeUnit.SECONDS)
    }

    /** Test hook: waits until everything queued on the IO thread so far has run. */
    internal fun awaitIdle() {
        val done = CompletableFuture<Unit>()
        io.submit { done.complete(Unit) }
        done.get(30, TimeUnit.SECONDS)
    }

    private fun submitWrite(effect: EditorEffect.WriteFile) {
        val path = state.path ?: return
        submit {
            val outcome: SaveOutcome =
                try {
                    val bytes = EditorText.encodeForSave(effect.text, effect.lineEnding, effect.bom)
                    when (val result = saver(path, bytes, effect.expectedBaseHash, effect.force)) {
                        is AtomicSaver.Result.Saved -> SaveOutcome.Saved(result.newHash, effect.text)
                        is AtomicSaver.Result.Failed -> SaveOutcome.Failed(result.reason)
                        is AtomicSaver.Result.Conflict ->
                            SaveOutcome.Conflicted(
                                if (result.diskHash == null &&
                                    !pathExists(path)
                                ) {
                                    null
                                } else {
                                    loader(path)
                                },
                            )
                    }
                } catch (e: Exception) {
                    logger.warn { "save failed (${e::class.simpleName})" }
                    SaveOutcome.Failed(SaveFailure.IO)
                }
            synchronized(lock) { if (state.path == path) apply(EditorEvent.SaveFinished(outcome)) }
        }
    }

    private fun pathExists(path: Path): Boolean =
        java.nio.file.Files
            .exists(path)

    private fun submit(task: () -> Unit) {
        try {
            io.submit {
                try {
                    task()
                } catch (e: Exception) {
                    logger.warn { "editor task failed (${e::class.simpleName})" }
                }
            }
        } catch (_: RejectedExecutionException) {
            // closed
        }
    }

    override fun close() {
        synchronized(lock) {
            draftTask?.close()
            draftTask = null
        }
        io.shutdownNow()
        synchronized(this) { ownTimer }?.shutdownNow()
    }
}
