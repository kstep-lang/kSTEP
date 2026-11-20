package dev.kstep.desktop.drop

import dev.kstep.cli.okf.DisplayName
import dev.kstep.desktop.okf.WorkspaceEntry
import dev.kstep.desktop.okf.WorkspaceState
import dev.kstep.desktop.recent.RecentCheck
import dev.kstep.desktop.recent.RecentOpenCheck
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import java.nio.file.Path
import java.util.Locale

/** What a drop asks for (ADR-0066). */
sealed interface DropAction {
    data class OpenScript(
        val path: Path,
    ) : DropAction

    data class OpenNote(
        val path: Path,
    ) : DropAction

    data class OpenFolder(
        val path: Path,
    ) : DropAction

    data class Rejected(
        val error: DesktopError,
    ) : DropAction
}

/** The [action] for the first usable dropped path; [ignoredCount] counts all the other paths. */
data class DropDecision(
    val action: DropAction,
    val ignoredCount: Int,
)

/** What the hover overlay may say while the pointer carries items (names only, no file access). */
enum class DropHint { ACCEPT, UNSUPPORTED }

object DropEvaluator {
    const val MAX_PATHS = 1000
    const val ONLY_SUPPORTED = "Only .kstep.kts scripts, .md notes or folders can be opened."
    private const val SCRIPT_SUFFIX = ".kstep.kts"
    private const val NOTE_SUFFIX = ".md"

    private enum class Kind { SCRIPT, NOTE, FOLDER, OTHER }

    /** Judges by name alone. A name without a dot extension might be a folder, so it is accepted here. */
    fun hint(names: List<String>): DropHint =
        if (names.isEmpty() ||
            names.take(MAX_PATHS).any { byName(it) != Kind.OTHER }
        ) {
            DropHint.ACCEPT
        } else {
            DropHint.UNSUPPORTED
        }

    private fun byName(name: String): Kind {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(SCRIPT_SUFFIX) -> Kind.SCRIPT
            lower.endsWith(NOTE_SUFFIX) -> Kind.NOTE
            !lower.substringAfterLast('/').substringAfterLast('\\').contains('.') -> Kind.FOLDER
            else -> Kind.OTHER
        }
    }

    /**
     * Does file I/O (type and link checks): call off the UI thread. The first path that is a script, a note or a
     * folder wins; everything else counts as ignored. Links are rejected, never followed.
     */
    fun evaluate(paths: List<Path>): DropDecision {
        val candidates = paths.take(MAX_PATHS)
        var firstLink: Path? = null
        for (path in candidates) {
            val name = path.fileName?.toString().orEmpty()
            val lower = name.lowercase(Locale.ROOT)
            val (kind, wantDirectory) =
                when {
                    lower.endsWith(SCRIPT_SUFFIX) -> Kind.SCRIPT to false
                    lower.endsWith(NOTE_SUFFIX) -> Kind.NOTE to false
                    else -> Kind.FOLDER to true
                }
            when (val check = RecentOpenCheck.checkPath(path, wantDirectory)) {
                is RecentCheck.Ok -> {
                    val action =
                        when (kind) {
                            Kind.SCRIPT -> DropAction.OpenScript(check.path)
                            Kind.NOTE -> DropAction.OpenNote(check.path)
                            else -> DropAction.OpenFolder(check.path)
                        }
                    return DropDecision(action, paths.size - 1)
                }
                RecentCheck.Link -> if (firstLink == null) firstLink = path
                else -> Unit
            }
        }
        val rejected =
            firstLink?.let { link(it) }
                ?: reject(ONLY_SUPPORTED)
        return DropDecision(DropAction.Rejected(rejected), paths.size)
    }

    fun link(path: Path): DesktopError =
        reject("${DisplayName.escaped(path.fileName?.toString() ?: path.toString(), 80)} is a link and was not opened.")

    fun reject(text: String): DesktopError = DesktopError(DesktopErrorCodes.DROP_REJECTED, null, null, text)

    fun openedNotice(
        name: String,
        ignored: Int,
    ): String? = if (ignored > 0) "Opened ${DisplayName.escaped(name, 80)}; $ignored other item(s) ignored." else null
}

/** What to do with a note that was dropped from outside the open workspace (ADR-0066). */
sealed interface PendingResolution {
    data class Open(
        val entry: WorkspaceEntry,
    ) : PendingResolution

    /** Forget the note: it is not in the scanned folder, or the scan failed. */
    data object Drop : PendingResolution

    data object Wait : PendingResolution
}

object PendingNote {
    /**
     * [pendingReal] is the real path of the dropped note and [expectedRoot] the folder that was asked to open for it;
     * [scanError] is set when the scan failed, [sawScanning] once the scan of that folder was seen running.
     */
    fun resolve(
        pendingReal: Path?,
        expectedRoot: Path?,
        workspace: WorkspaceState,
        scanError: Boolean,
        sawScanning: Boolean,
    ): PendingResolution =
        when {
            pendingReal == null -> PendingResolution.Wait
            scanError -> PendingResolution.Drop
            // an old workspace that is about to be replaced is not the answer
            workspace is WorkspaceState.Open && workspace.scan.root != expectedRoot -> PendingResolution.Wait
            workspace is WorkspaceState.Open -> {
                val entry = workspace.scan.entries.firstOrNull { sameFile(it.path, pendingReal) }
                if (entry != null) PendingResolution.Open(entry) else PendingResolution.Drop
            }
            workspace is WorkspaceState.Scanning -> PendingResolution.Wait
            // None: right after openFolder the state may not have changed yet; once it was seen scanning, None means failure
            sawScanning -> PendingResolution.Drop
            else -> PendingResolution.Wait
        }

    private fun sameFile(
        entryPath: Path,
        real: Path,
    ): Boolean =
        entryPath.toAbsolutePath().normalize() == real ||
            try {
                entryPath.toRealPath() == real
            } catch (_: Exception) {
                false
            }
}
