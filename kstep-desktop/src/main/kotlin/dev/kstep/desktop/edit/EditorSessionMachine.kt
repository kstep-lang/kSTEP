package dev.kstep.desktop.edit

import java.nio.file.Path

enum class ConflictKind { CHANGED, DELETED }

/** The disk version that conflicts with the buffer; [disk] is `null` for [ConflictKind.DELETED]. */
class Conflict(
    val kind: ConflictKind,
    val disk: EditorText.Loaded?,
) {
    /** The disk version can be loaded into the editor (Reload is possible). */
    val reloadable: Boolean get() = kind == ConflictKind.CHANGED && disk is EditorText.Loaded.Ok

    /** Why Reload is not possible, or `null`. */
    val reloadBlockedReason: String?
        get() =
            when {
                reloadable -> null
                kind == ConflictKind.DELETED -> "the file no longer exists"
                disk is EditorText.Loaded.Refused -> disk.reason
                else -> "the file cannot be read"
            }
}

class InFlight(
    val text: String,
    val hash: ByteArray,
)

sealed interface EditorState {
    val path: Path?

    data object NoFile : EditorState {
        override val path: Path? = null
    }

    /** The file cannot be edited (too large, not UTF-8 text, missing): the preview still works. */
    data class Unavailable(
        override val path: Path,
        val code: String,
        val reason: String,
    ) : EditorState

    /**
     * [base] is the text that equals the disk version (hash [baseHash], `null` = the file is known to be gone);
     * [text] is the buffer. [forceNext]: the next save overwrites without the hash check (after Keep mine on a
     * disk version whose hash is unknown).
     */
    class Editing(
        override val path: Path,
        val base: String,
        val baseHash: ByteArray?,
        val text: String,
        val lineEnding: LineEnding,
        val bom: Boolean,
        val mixedEndings: Boolean,
        val conflict: Conflict? = null,
        val saving: InFlight? = null,
        val saveError: SaveFailure? = null,
        val deletedOnDisk: Boolean = false,
        val pendingSave: Boolean = false,
        val forceNext: Boolean = false,
    ) : EditorState {
        val dirty: Boolean get() = text != base || deletedOnDisk

        fun copy(
            base: String = this.base,
            baseHash: ByteArray? = this.baseHash,
            text: String = this.text,
            lineEnding: LineEnding = this.lineEnding,
            bom: Boolean = this.bom,
            mixedEndings: Boolean = this.mixedEndings,
            conflict: Conflict? = this.conflict,
            saving: InFlight? = this.saving,
            saveError: SaveFailure? = this.saveError,
            deletedOnDisk: Boolean = this.deletedOnDisk,
            pendingSave: Boolean = this.pendingSave,
            forceNext: Boolean = this.forceNext,
        ): Editing =
            Editing(
                path,
                base,
                baseHash,
                text,
                lineEnding,
                bom,
                mixedEndings,
                conflict,
                saving,
                saveError,
                deletedOnDisk,
                pendingSave,
                forceNext,
            )
    }
}

sealed interface EditorEvent {
    class Opened(
        val path: Path,
        val loaded: EditorText.Loaded,
    ) : EditorEvent

    class TextEdited(
        val text: String,
    ) : EditorEvent

    data object SaveRequested : EditorEvent

    class SaveFinished(
        val result: SaveOutcome,
    ) : EditorEvent

    /** The IO layer has already read, limit-checked and hashed the disk version. */
    class DiskChanged(
        val loaded: EditorText.Loaded,
    ) : EditorEvent

    data object DiskMissing : EditorEvent

    data object ReloadChosen : EditorEvent

    data object KeepMineChosen : EditorEvent

    data object CloseRequested : EditorEvent
}

sealed interface SaveOutcome {
    class Saved(
        val newHash: ByteArray,
        val savedText: String,
    ) : SaveOutcome

    class Failed(
        val reason: SaveFailure,
    ) : SaveOutcome

    /** The saver found that the disk no longer matches; [disk] is `null` when the file is gone. */
    class Conflicted(
        val disk: EditorText.Loaded?,
    ) : SaveOutcome
}

sealed interface EditorEffect {
    class WriteFile(
        val text: String,
        val lineEnding: LineEnding,
        val bom: Boolean,
        val expectedBaseHash: ByteArray?,
        val force: Boolean,
    ) : EditorEffect

    /** The buffer was replaced by the disk version: the session records the old text as an undoable step. */
    class ReloadApplied(
        val previousText: String,
    ) : EditorEffect

    /** Save was pressed while a conflict is open: the banner pulses and a hint is shown. */
    data object PulseBanner : EditorEffect

    /** Nothing unsaved: the window may close. */
    data object ContinueClose : EditorEffect

    /** Unsaved changes: ask Save / Don't Save / Cancel. */
    data object AskCloseDecision : EditorEffect
}

class EditorTransition(
    val state: EditorState,
    val effects: List<EditorEffect> = emptyList(),
)

/** The pure rules of the editor session (ADR-0052). All I/O happens outside; the reducer only decides. */
class EditorSessionMachine {
    fun reduce(
        state: EditorState,
        event: EditorEvent,
    ): EditorTransition =
        when (event) {
            is EditorEvent.Opened -> EditorTransition(fromLoaded(event.path, event.loaded))
            is EditorEvent.TextEdited ->
                if (state is EditorState.Editing) {
                    EditorTransition(
                        state.copy(text = event.text),
                    )
                } else {
                    EditorTransition(state)
                }
            EditorEvent.SaveRequested -> save(state)
            is EditorEvent.SaveFinished -> saveFinished(state, event.result)
            is EditorEvent.DiskChanged -> diskChanged(state, event.loaded)
            EditorEvent.DiskMissing -> diskMissing(state)
            EditorEvent.ReloadChosen -> reload(state)
            EditorEvent.KeepMineChosen -> keepMine(state)
            EditorEvent.CloseRequested ->
                EditorTransition(
                    state,
                    listOf(
                        if (state is EditorState.Editing &&
                            state.dirty
                        ) {
                            EditorEffect.AskCloseDecision
                        } else {
                            EditorEffect.ContinueClose
                        },
                    ),
                )
        }

    private fun fromLoaded(
        path: Path,
        loaded: EditorText.Loaded,
    ): EditorState =
        when (loaded) {
            is EditorText.Loaded.Ok ->
                EditorState.Editing(
                    path,
                    loaded.text,
                    loaded.sha256,
                    loaded.text,
                    loaded.lineEnding,
                    loaded.hadBom,
                    loaded.mixedLineEndings,
                )
            is EditorText.Loaded.Refused -> EditorState.Unavailable(path, loaded.code, loaded.reason)
        }

    private fun save(state: EditorState): EditorTransition {
        if (state !is EditorState.Editing) return EditorTransition(state)
        if (state.conflict != null) return EditorTransition(state, listOf(EditorEffect.PulseBanner))
        if (state.saving != null) return EditorTransition(state.copy(pendingSave = true))
        if (!state.dirty) return EditorTransition(state)
        return write(state)
    }

    private fun write(state: EditorState.Editing): EditorTransition {
        val hash = EditorText.sha256(EditorText.encodeForSave(state.text, state.lineEnding, state.bom))
        return EditorTransition(
            state.copy(saving = InFlight(state.text, hash), pendingSave = false),
            listOf(EditorEffect.WriteFile(state.text, state.lineEnding, state.bom, state.baseHash, state.forceNext)),
        )
    }

    private fun saveFinished(
        state: EditorState,
        result: SaveOutcome,
    ): EditorTransition {
        if (state !is EditorState.Editing) return EditorTransition(state)
        return when (result) {
            is SaveOutcome.Saved -> {
                val next =
                    state.copy(
                        base = result.savedText,
                        baseHash = result.newHash,
                        saving = null,
                        saveError = null,
                        deletedOnDisk = false,
                        conflict = null,
                        forceNext = false,
                        mixedEndings = false,
                    )
                if (state.pendingSave &&
                    next.text != next.base
                ) {
                    write(next.copy(pendingSave = false))
                } else {
                    EditorTransition(next.copy(pendingSave = false))
                }
            }
            is SaveOutcome.Failed ->
                EditorTransition(state.copy(saving = null, saveError = result.reason, pendingSave = false))
            is SaveOutcome.Conflicted ->
                EditorTransition(
                    state.copy(
                        saving = null,
                        pendingSave = false,
                        conflict =
                            if (result.disk ==
                                null
                            ) {
                                Conflict(ConflictKind.DELETED, null)
                            } else {
                                Conflict(ConflictKind.CHANGED, result.disk)
                            },
                    ),
                )
        }
    }

    private fun sameHash(
        a: ByteArray?,
        b: ByteArray?,
    ): Boolean = a != null && b != null && a.contentEquals(b)

    private fun diskChanged(
        state: EditorState,
        loaded: EditorText.Loaded,
    ): EditorTransition {
        if (state is EditorState.NoFile) return EditorTransition(state)
        if (state is EditorState.Unavailable) {
            return EditorTransition(fromLoaded(state.path, loaded))
        }
        state as EditorState.Editing
        val diskHash =
            when (loaded) {
                is EditorText.Loaded.Ok -> loaded.sha256
                is EditorText.Loaded.Refused -> loaded.sha256
            }
        // Our own save (or a touch): nothing changed from the editor's point of view. The in-flight hash is recorded
        // before the move, so a signal that arrives between the move and the completion is recognised as well.
        if (sameHash(diskHash, state.baseHash) || sameHash(diskHash, state.saving?.hash)) {
            // The disk is back at the version the buffer is based on: an open "changed" conflict is gone.
            val resolved = state.conflict?.kind == ConflictKind.CHANGED && sameHash(diskHash, state.baseHash)
            return EditorTransition(if (resolved) state.copy(conflict = null) else state)
        }
        if (!state.dirty && state.saving == null) {
            return when (loaded) {
                is EditorText.Loaded.Ok ->
                    EditorTransition(
                        state.copy(
                            base = loaded.text,
                            baseHash = loaded.sha256,
                            text = loaded.text,
                            lineEnding = loaded.lineEnding,
                            bom = loaded.hadBom,
                            mixedEndings = loaded.mixedLineEndings,
                            conflict = null,
                            saveError = null,
                        ),
                        listOf(EditorEffect.ReloadApplied(state.text)),
                    )
                is EditorText.Loaded.Refused ->
                    EditorTransition(
                        EditorState.Unavailable(state.path, loaded.code, loaded.reason),
                    )
            }
        }
        return EditorTransition(state.copy(conflict = Conflict(ConflictKind.CHANGED, loaded)))
    }

    private fun diskMissing(state: EditorState): EditorTransition {
        if (state !is EditorState.Editing) return EditorTransition(state)
        return if (state.dirty) {
            EditorTransition(state.copy(conflict = Conflict(ConflictKind.DELETED, null)))
        } else {
            EditorTransition(state.copy(deletedOnDisk = true, baseHash = null))
        }
    }

    private fun reload(state: EditorState): EditorTransition {
        if (state !is EditorState.Editing) return EditorTransition(state)
        val conflict = state.conflict ?: return EditorTransition(state)
        val disk = conflict.disk as? EditorText.Loaded.Ok
        if (conflict.kind != ConflictKind.CHANGED || disk == null) return EditorTransition(state)
        return EditorTransition(
            state.copy(
                base = disk.text,
                baseHash = disk.sha256,
                text = disk.text,
                lineEnding = disk.lineEnding,
                bom = disk.hadBom,
                mixedEndings = disk.mixedLineEndings,
                conflict = null,
                deletedOnDisk = false,
                saveError = null,
                forceNext = false,
            ),
            listOf(EditorEffect.ReloadApplied(state.text)),
        )
    }

    private fun keepMine(state: EditorState): EditorTransition {
        if (state !is EditorState.Editing) return EditorTransition(state)
        val conflict = state.conflict ?: return EditorTransition(state)
        val next =
            when (val disk = conflict.disk) {
                null -> state.copy(conflict = null, baseHash = null, deletedOnDisk = true)
                is EditorText.Loaded.Ok ->
                    state.copy(
                        conflict = null,
                        base = disk.text,
                        baseHash = disk.sha256,
                        deletedOnDisk = false,
                    )
                is EditorText.Loaded.Refused ->
                    // The disk version cannot be a base: keep the old base text (so the buffer stays dirty) and
                    // overwrite on the next save.
                    state.copy(
                        conflict = null,
                        baseHash = disk.sha256,
                        forceNext = disk.sha256 == null,
                        deletedOnDisk = false,
                    )
            }
        return EditorTransition(next)
    }
}
