package dev.kstep.desktop.edit

import dev.kstep.desktop.watch.WatchSignal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
) {
    val text: String get() = (state as? EditorState.Editing)?.text ?: ""
    val dirty: Boolean get() = (state as? EditorState.Editing)?.dirty == true
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
    clock: () -> Long = System::currentTimeMillis,
    private val io: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "kstep-desktop-editor").apply {
                isDaemon =
                    true
            }
        },
) : AutoCloseable {
    private val machine = EditorSessionMachine()
    private val history = EditHistory(clock = clock)
    private val lock = Any()
    private var state: EditorState = EditorState.NoFile
    private var revision = 0
    private var caret = 0
    private var pulse = 0
    private val mutableView = MutableStateFlow(EditorView(state, 0, 0, 0))

    val view: StateFlow<EditorView> = mutableView.asStateFlow()

    private fun publish() {
        mutableView.value = EditorView(state, revision, caret, pulse)
    }

    /** Loads [path] into the editor (replaces any current buffer; the caller has already handled unsaved changes). */
    fun open(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        submit {
            val loaded = loader(normalized)
            synchronized(lock) {
                history.clear()
                state = machine.reduce(state, EditorEvent.Opened(normalized, loaded)).state
                revision++
                caret = 0
                publish()
            }
        }
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

    /** Moves the caret (an error-bar click) without changing the text. */
    fun requestCaret(offset: Int) {
        synchronized(lock) {
            caret = offset
            revision++
            publish()
        }
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

    fun save() = synchronized(lock) { apply(EditorEvent.SaveRequested) }

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
        publish()
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
        io.shutdownNow()
    }
}
