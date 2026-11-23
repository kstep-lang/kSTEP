package dev.kstep.desktop.ui

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

/** The result of reading the clipboard text (ADR-0075); never carries the text of an error. */
internal sealed interface ClipboardRead {
    data class Text(
        val text: String,
    ) : ClipboardRead

    /** The clipboard holds no text. */
    data object Empty : ClipboardRead

    /** The clipboard could not be read. */
    data object Failed : ClipboardRead
}

/** Reading and writing plain text through the Compose clipboard. Errors are returned, never thrown or logged. */
@Suppress("OPT_IN_USAGE", "OPT_IN_USAGE_ERROR")
internal object ClipboardText {
    private fun transferable(entry: ClipEntry?): Transferable? = entry?.nativeClipEntry as? Transferable

    /** Whether the clipboard offers text; the content is not read. */
    suspend fun hasText(clipboard: Clipboard): Boolean =
        runCatching { transferable(clipboard.getClipEntry())?.isDataFlavorSupported(DataFlavor.stringFlavor) == true }
            .getOrDefault(false)

    suspend fun read(clipboard: Clipboard): ClipboardRead =
        runCatching {
            val t = transferable(clipboard.getClipEntry())
            if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                ClipboardRead.Empty
            } else {
                val text = t.getTransferData(DataFlavor.stringFlavor) as? String
                if (text.isNullOrEmpty()) ClipboardRead.Empty else ClipboardRead.Text(text)
            }
        }.getOrDefault(ClipboardRead.Failed)

    /** True when the text was written. */
    suspend fun write(
        clipboard: Clipboard,
        text: String,
    ): Boolean = runCatching { clipboard.setClipEntry(ClipEntry(StringSelection(text))) }.isSuccess
}

/** The notices of the clipboard commands (ADR-0075). */
internal object ClipboardNotices {
    const val TOO_LARGE = "Clipboard text is too large to paste (over 4 MB)"
    const val UNREADABLE = "The clipboard could not be read"
    const val NOT_COPIED = "The text could not be copied to the clipboard"
}
