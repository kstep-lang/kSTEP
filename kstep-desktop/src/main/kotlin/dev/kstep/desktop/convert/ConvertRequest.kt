package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertTarget
import dev.kstep.cli.DesktopConvertRequest
import java.nio.file.Path

/**
 * What the convert dialog asks for (ADR-0077). There is deliberately no `force` here: replacing is decided only by the
 * controller, and only after the user confirmed a conflict list.
 */
data class ConvertRequest(
    val source: Path?,
    val target: ConvertTarget,
    /** Null means the CLI default: next to the source file, or into the source folder. */
    val outDir: Path?,
    val recursive: Boolean,
    val title: String? = null,
    val tags: List<String> = emptyList(),
) {
    /** Null while no source is chosen. */
    fun toFacade(force: Boolean): DesktopConvertRequest? =
        source?.let { DesktopConvertRequest(it, target, outDir, recursive, force, title, tags) }
}
