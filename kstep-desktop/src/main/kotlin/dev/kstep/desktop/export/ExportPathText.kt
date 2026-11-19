package dev.kstep.desktop.export

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.status.StatusLineText
import java.nio.file.Path

/** The export target of the dialog as two readable lines (ADR-0064): the file name whole, the folder shortened. Pure. */
object ExportPathText {
    /** [fileName] and [folder] are escaped and shortened for display; [full] is the escaped full path for a tooltip. */
    data class TargetLabel(
        val fileName: String,
        val folder: String,
        val full: String,
    )

    private const val DEFAULT_NAME_MAX = 64
    private const val DEFAULT_FOLDER_MAX = 48
    private const val KEPT_LEADING_SEGMENTS = 1
    private const val KEPT_TRAILING_SEGMENTS = 2
    private const val END_SHARE = 0.7
    private const val SEGMENT_MAX = 255

    fun label(
        target: Path,
        nameMax: Int = DEFAULT_NAME_MAX,
        folderMax: Int = DEFAULT_FOLDER_MAX,
    ): TargetLabel {
        val absolute = target.toAbsolutePath().normalize()
        val name = DisplayName.escaped(absolute.fileName?.toString().orEmpty(), SEGMENT_MAX)
        return TargetLabel(
            fileName = StatusLineText.shorten(name, nameMax),
            folder = folderLabel(absolute, folderMax),
            full = DisplayName.escaped(absolute.toString(), Int.MAX_VALUE),
        )
    }

    private fun folderLabel(
        absolute: Path,
        folderMax: Int,
    ): String {
        val separator = absolute.fileSystem.separator
        val root = absolute.root?.toString() ?: ""
        val parent = absolute.parent ?: return root.ifEmpty { separator }
        val segments = parent.map { DisplayName.escaped(it.toString(), SEGMENT_MAX) }
        val full = root + segments.joinToString(separator)
        if (full.length <= folderMax) return full
        val limit = KEPT_LEADING_SEGMENTS + KEPT_TRAILING_SEGMENTS
        val shortened =
            if (segments.size > limit) {
                root + segments.take(KEPT_LEADING_SEGMENTS).joinToString(separator) + separator + "…" + separator +
                    segments.takeLast(KEPT_TRAILING_SEGMENTS).joinToString(separator)
            } else {
                full
            }
        return StatusLineText.middle(shortened, folderMax, END_SHARE)
    }
}
