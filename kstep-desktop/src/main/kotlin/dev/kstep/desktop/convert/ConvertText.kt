package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertSkipReason
import dev.kstep.cli.ConvertTarget
import java.nio.file.Path
import java.util.Locale

/**
 * The texts and the small pure decisions of the convert dialog (ADR-0078). No Compose, no AWT, no file access: the
 * dialog only draws what this object says. Names that come from the controller are already escaped; paths are never
 * handled here.
 */
object ConvertText {
    const val SOURCE_PLACEHOLDER = "No source chosen"
    const val CHOOSE_FILE = "Choose File…"
    const val CHOOSE_FOLDER = "Choose Folder…"
    const val OUT_SAME = "Same folder as the source"
    const val OUT_OTHER = "Other folder"
    const val CHOOSE_OUT = "Choose…"
    const val INCLUDE_SUBFOLDERS = "Include sub-folders"
    const val TITLE_LABEL = "Title"
    const val TAGS_LABEL = "Tags (comma-separated)"

    const val CANCEL = "Cancel"
    const val CONVERT = "Convert"
    const val CONVERTING = "Converting…"
    const val BACK = "Back"
    const val CLOSE = "Close"

    const val SECTION_WRITTEN = "Written"
    const val SECTION_SKIPPED = "Skipped"
    const val SECTION_WARNINGS = "Warnings"
    const val SECTION_ERRORS = "Errors"
    const val SECTION_ALREADY_WRITTEN = "These files were already written"

    private const val SCRIPT_SUFFIX = ".kstep.kts"
    private const val NOTE_SUFFIX = ".md"

    fun title(state: ConvertState): String =
        when (state) {
            is ConvertState.Idle, is ConvertState.Editing, is ConvertState.Running -> "Convert"
            is ConvertState.Conflict ->
                if (state.allConflicts == 1) "1 file already exists" else "${state.allConflicts} files already exist"
            is ConvertState.Done ->
                when (state.written.size) {
                    0 -> "Nothing was converted"
                    1 -> "Converted 1 file"
                    else -> "Converted ${state.written.size} files"
                }
            is ConvertState.Failed -> "Conversion failed"
        }

    fun directionLabel(target: ConvertTarget): String =
        when (target) {
            ConvertTarget.KTS -> "Note → scripts"
            ConvertTarget.OKF -> "Script → note"
        }

    fun directionSubtitle(target: ConvertTarget): String =
        when (target) {
            ConvertTarget.KTS -> ".md to .kstep.kts"
            ConvertTarget.OKF -> ".kstep.kts to .md"
        }

    fun replaceLabel(count: Int): String = if (count == 1) "Replace 1 file" else "Replace $count files"

    /** "and N more", or null when there is nothing more. */
    fun more(count: Int): String? = if (count <= 0) null else "and $count more"

    fun skipReason(
        reason: ConvertSkipReason,
        line: Int?,
    ): String {
        val text =
            when (reason) {
                ConvertSkipReason.EMPTY_BLOCK -> "The kstep block is empty"
                ConvertSkipReason.NO_KSTEP_BLOCK -> "No kstep block in this note"
                ConvertSkipReason.EMPTY_SCRIPT -> "The script is empty"
                ConvertSkipReason.SYMBOLIC_LINK -> "Symbolic links are not followed"
                ConvertSkipReason.NOT_REGULAR -> "Not a regular file"
                ConvertSkipReason.HIDDEN -> "Hidden file"
                ConvertSkipReason.HIDDEN_DIR -> "Inside a hidden folder"
                ConvertSkipReason.OUTPUT_DIR -> "This is the output folder"
            }
        return if (line != null) "$text (line $line)" else text
    }

    /** The file chooser's name filter: a note or a kSTEP script. A name check only; the facade validates for real. */
    fun isConvertSource(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(NOTE_SUFFIX) || lower.endsWith(SCRIPT_SUFFIX)
    }

    /** The direction a freshly chosen source implies: a script becomes a note, everything else a script. */
    fun deriveTarget(
        name: String,
        isDirectory: Boolean,
    ): ConvertTarget =
        if (!isDirectory &&
            name.lowercase(Locale.ROOT).endsWith(SCRIPT_SUFFIX)
        ) {
            ConvertTarget.OKF
        } else {
            ConvertTarget.KTS
        }

    /** Splits a comma-separated tag text; blanks and empty entries are dropped. */
    fun parseTags(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Title and tags exist only for a single script that becomes a note. When their fields are not shown, nothing is
     * sent, even if text was typed earlier.
     */
    fun effectiveRequest(
        base: ConvertRequest,
        source: Path?,
        sourceIsDir: Boolean,
        titleText: String,
        tagsText: String,
    ): ConvertRequest {
        val applies = base.target == ConvertTarget.OKF && !sourceIsDir
        return base.copy(
            source = source,
            recursive = recursiveFor(sourceIsDir, base.recursive),
            title = if (applies && titleText.isNotBlank()) titleText.trim() else null,
            tags = if (applies) parseTags(tagsText) else emptyList(),
        )
    }

    /** A folder is converted with or without its sub-folders as the user says; a single file never recurses. */
    fun recursiveFor(
        sourceIsDir: Boolean,
        wanted: Boolean,
    ): Boolean = sourceIsDir && wanted
}
