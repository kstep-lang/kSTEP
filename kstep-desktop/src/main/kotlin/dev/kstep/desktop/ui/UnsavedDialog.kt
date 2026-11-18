package dev.kstep.desktop.ui

import dev.kstep.desktop.edit.CloseDecision
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.mayProceed
import java.awt.Component
import javax.swing.JOptionPane

/**
 * The native three-button question of the platform (AWT has no such dialog, so a Swing option dialog): Save / Don't
 * Save / Cancel. Closing the dialog counts as Cancel. Must run on the UI thread.
 */
internal fun askUnsaved(parent: Component?): CloseDecision {
    val options = arrayOf("Save", "Don't Save", "Cancel")
    val answer =
        JOptionPane.showOptionDialog(
            parent,
            "Do you want to save the changes to the script?",
            "kSTEP Desktop",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            options,
            options[0],
        )
    return when (answer) {
        0 -> CloseDecision.SAVE
        1 -> CloseDecision.DONT_SAVE
        else -> CloseDecision.CANCEL
    }
}

/** `true` if the caller may go on (nothing unsaved, discarded, or saved successfully); a failed save keeps everything. */
internal fun confirmUnsaved(
    parent: Component?,
    session: EditorSession,
): Boolean = !session.hasUnsavedChanges() || mayProceed(askUnsaved(parent)) { session.saveAndWait() }
