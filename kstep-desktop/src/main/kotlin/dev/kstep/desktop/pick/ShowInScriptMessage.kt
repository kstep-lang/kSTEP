package dev.kstep.desktop.pick

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.edit.SourceLocation

/** A line of text below the "Show in Script" button; [findQuery] set means a Find action is offered (ADR-0068). */
class ShowInScriptMessage(
    val text: String,
    val findQuery: String? = null,
)

object ShowInScriptMessages {
    const val NAME_CLIP = 64
    const val DISABLED_NO_SCRIPT = "No script for this model"
    const val DISABLED_NO_NAME = "Selection has no name"
    const val DISABLED_KNOWLEDGE = "Not available in a knowledge note"

    /** The message for a located name, or null when nothing needs saying (the jump itself is the feedback). */
    fun of(
        location: SourceLocation,
        name: String,
        bufferDiffers: Boolean,
    ): ShowInScriptMessage? {
        val shown = "“" + DisplayName.escaped(name, NAME_CLIP) + "”"
        return when (location) {
            is SourceLocation.Found ->
                if (bufferDiffers) ShowInScriptMessage("Showing $shown (script changed since last preview)") else null
            is SourceLocation.Ambiguous ->
                ShowInScriptMessage("$shown appears more than once in the script", location.query)
            SourceLocation.NotFound -> ShowInScriptMessage("$shown not found as a literal in the script")
            SourceLocation.TooLong -> ShowInScriptMessage("Name too long to search for")
            SourceLocation.NotLiteral -> ShowInScriptMessage("$shown cannot be searched as a plain literal")
            SourceLocation.TimedOut -> ShowInScriptMessage("Search took too long; use Find in the editor")
        }
    }
}
