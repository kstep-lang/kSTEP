package dev.kstep.desktop.edit

enum class EditKind { TYPING, OTHER, RELOAD }

/**
 * The editor's own undo/redo (ADR-0052): full-text snapshots under a step and a character budget (the oldest steps are
 * dropped). Consecutive typing merges into one step until a pause of more than [pauseMs], a line break or a caret jump.
 * Pure; the clock is injected.
 */
class EditHistory(
    private val maxSteps: Int = 500,
    private val maxChars: Int = 16 * 1024 * 1024,
    private val pauseMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Entry(
        val text: String,
        val caret: Int,
    )

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var chars = 0L
    private var lastKind: EditKind? = null
    private var lastTime = 0L
    private var lastCaretAfter = -1

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records an edit of [textBefore] (caret [caretBefore]); [caretAfter] is where the caret is after the edit. */
    fun record(
        textBefore: String,
        caretBefore: Int,
        kind: EditKind,
        caretAfter: Int,
        insertedNewline: Boolean = false,
    ) {
        val now = clock()
        val coalesce =
            kind == EditKind.TYPING &&
                lastKind == EditKind.TYPING &&
                !insertedNewline &&
                now - lastTime <= pauseMs &&
                caretBefore == lastCaretAfter &&
                undoStack.isNotEmpty()
        clearRedo()
        if (!coalesce) {
            undoStack.addLast(Entry(textBefore, caretBefore))
            chars += textBefore.length
            trim()
        }
        lastKind = if (insertedNewline) EditKind.OTHER else kind
        lastTime = now
        lastCaretAfter = caretAfter
    }

    /** Pops the previous state; [current] goes onto the redo stack. */
    fun undo(
        current: String,
        caret: Int,
    ): Entry? {
        val entry = undoStack.removeLastOrNull() ?: return null
        chars -= entry.text.length
        redoStack.addLast(Entry(current, caret))
        chars += current.length
        lastKind = null
        trim()
        return entry
    }

    fun redo(
        current: String,
        caret: Int,
    ): Entry? {
        val entry = redoStack.removeLastOrNull() ?: return null
        chars -= entry.text.length
        undoStack.addLast(Entry(current, caret))
        chars += current.length
        lastKind = null
        trim()
        return entry
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        chars = 0
        lastKind = null
    }

    private fun clearRedo() {
        for (e in redoStack) chars -= e.text.length
        redoStack.clear()
    }

    private fun trim() {
        while (undoStack.isNotEmpty() && (undoStack.size > maxSteps || chars > maxChars)) {
            chars -= undoStack.removeFirst().text.length
        }
    }
}
