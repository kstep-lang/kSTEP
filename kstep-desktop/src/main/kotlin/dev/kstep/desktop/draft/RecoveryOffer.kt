package dev.kstep.desktop.draft

import java.nio.file.Path
import java.security.MessageDigest

/** What the draft is compared with: the state of the file on disk when it is opened. */
sealed interface FileState {
    /** [text] has LF line endings and no BOM, like the editor buffer. */
    class Editable(
        val sha256: ByteArray,
        val text: String,
    ) : FileState

    data object Missing : FileState

    class NotEditable(
        val sha256: ByteArray?,
        val reason: String,
    ) : FileState
}

enum class OfferKind { UNCHANGED, CHANGED, DELETED, NOT_EDITABLE }

class RecoveryOffer(
    val path: Path,
    val kind: OfferKind,
    val written: Long,
    val body: String,
    val notEditableReason: String? = null,
)

sealed interface OfferDecision {
    data object None : OfferDecision

    /** The draft equals the file: nothing to recover, the draft is deleted without a question. */
    data object DeleteSilently : OfferDecision

    class Offer(
        val offer: RecoveryOffer,
    ) : OfferDecision
}

/** The pure decision after opening a file that has a draft (ADR-0084). */
fun decideOffer(
    draft: Draft?,
    file: FileState,
): OfferDecision {
    if (draft == null) return OfferDecision.None

    fun offer(
        kind: OfferKind,
        reason: String? = null,
    ) = OfferDecision.Offer(RecoveryOffer(draft.path, kind, draft.written, draft.body, reason))
    return when (file) {
        is FileState.Editable ->
            when {
                draft.body == file.text -> OfferDecision.DeleteSilently
                draft.fileSha256 != null && MessageDigest.isEqual(draft.fileSha256, file.sha256) ->
                    offer(OfferKind.UNCHANGED)
                else -> offer(OfferKind.CHANGED)
            }
        FileState.Missing -> offer(OfferKind.DELETED)
        is FileState.NotEditable -> offer(OfferKind.NOT_EDITABLE, file.reason)
    }
}
