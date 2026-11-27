package dev.kstep.desktop.run

import dev.kstep.desktop.edit.EditorText
import java.nio.file.Path

/**
 * Reads a script as the editor shows it (ADR-0091): same size limit, same line-end normalization (`\n`), no BOM. This is
 * the text the part source lines of a run refer to. `null` when the file cannot be loaded; never throws, never logs.
 */
internal fun readScriptSourceText(path: Path): String? =
    try {
        (EditorText.load(path) as? EditorText.Loaded.Ok)?.text
    } catch (_: RuntimeException) {
        null
    }
