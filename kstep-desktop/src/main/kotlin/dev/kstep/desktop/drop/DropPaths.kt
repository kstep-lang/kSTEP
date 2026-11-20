package dev.kstep.desktop.drop

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.net.URI
import java.nio.file.Path

/** Reads the file paths out of an AWT drop (ADR-0066). Text and URLs that are not `file:` yield nothing. */
object DropPaths {
    private const val MAX_URI_LINES = 100_000
    private val uriListFlavor = DataFlavor("text/uri-list;class=java.lang.String")

    /** Never throws: a platform that hides the data during the hover gives an empty list. */
    fun fromTransferable(transferable: Transferable): List<Path> =
        try {
            when {
                transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                    val files =
                        transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: emptyList<Any>()
                    files.mapNotNull { (it as? java.io.File)?.toPath() }
                }
                transferable.isDataFlavorSupported(uriListFlavor) ->
                    fromUriList(transferable.getTransferData(uriListFlavor) as? String ?: "")
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

    /** The `file:` URIs of a `text/uri-list` text (one per line, `#` lines are comments). */
    fun fromUriList(text: String): List<Path> =
        text
            .lineSequence()
            .take(MAX_URI_LINES)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull {
                try {
                    val uri = URI(it)
                    if (uri.scheme.equals("file", ignoreCase = true)) Path.of(uri) else null
                } catch (_: Exception) {
                    null
                }
            }.toList()
}
