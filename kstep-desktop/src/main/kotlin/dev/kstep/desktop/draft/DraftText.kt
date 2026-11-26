package dev.kstep.desktop.draft

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.run.DesktopErrorCodes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words of the crash-draft banner (ADR-0084); untrusted names are made safe before they are shown. */
object DraftText {
    const val CHOOSE_FIRST = "Choose Restore or Discard first. "

    fun stamp(
        epochMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        DateTimeFormatter
            .ofPattern("d MMM, HH:mm", Locale.ENGLISH)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun offerMessage(
        offer: RecoveryOffer,
        emphasised: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val at = stamp(offer.written, zone)
        val text =
            when (offer.kind) {
                OfferKind.UNCHANGED -> "Unsaved changes from $at were found. Restore them?"
                OfferKind.CHANGED ->
                    "Unsaved changes from $at were found, but the file changed on disk since. " +
                        "Saving restored changes replaces the file."
                OfferKind.DELETED ->
                    "Unsaved changes from $at were found, but the file was deleted. Saving restored changes recreates it."
                OfferKind.NOT_EDITABLE ->
                    "Unsaved changes from $at were found, but the file can no longer be edited: " +
                        "${DisplayName.escaped(offer.notEditableReason.orEmpty(), 80)}."
            }
        return (if (emphasised) CHOOSE_FIRST else "") + text
    }

    fun elsewhereMessage(fileName: String): String =
        "Unsaved changes to ${DisplayName.escaped(fileName, 80)} were kept."

    fun noticeMessage(code: String): String =
        when (code) {
            DesktopErrorCodes.DRAFT_WRITE_FAILED ->
                "The crash draft could not be written ($code). Unsaved changes are not protected."
            DesktopErrorCodes.DRAFT_STORE_UNAVAILABLE ->
                "The crash draft folder is not usable ($code). Unsaved changes are not protected."
            DesktopErrorCodes.DRAFT_TOO_LARGE ->
                "The text is too large for a crash draft ($code). Unsaved changes are not protected."
            else -> "Unsaved changes are not protected ($code)."
        }
}
