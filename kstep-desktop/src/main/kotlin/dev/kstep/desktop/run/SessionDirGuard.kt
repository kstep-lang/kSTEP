package dev.kstep.desktop.run

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Guards the unsandboxed desktop process against a sandboxed candidate that swaps a folder it may write to for a link
 * (ADR-0059, confused deputy). The sandbox may write below the session folder, so a candidate can delete `ai/` and
 * put a link to any user-writable folder in its place (or, on macOS, replace the session folder itself). Before the
 * desktop creates or writes anything there, it checks that the folders are still the real directories it saw first.
 */
internal class SessionDirGuard(
    private val sessionDir: Path,
) {
    @Volatile
    private var expected: Path? = null

    /** True when [sessionDir] (and [child], if given) is still the original real directory and no link. */
    @Synchronized
    fun intact(child: Path? = null): Boolean =
        try {
            if (isLinkOrNotDir(sessionDir)) {
                false
            } else {
                val real = sessionDir.toRealPath()
                val first = expected ?: real.also { expected = it }
                real == first &&
                    (child == null || !Files.exists(child, LinkOption.NOFOLLOW_LINKS) || !isLinkOrNotDir(child))
            }
        } catch (_: Exception) {
            false
        }

    private fun isLinkOrNotDir(p: Path): Boolean =
        !Files
            .readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .isDirectory

    companion object {
        const val MESSAGE: String = "the session folder was changed while the script ran; the check is stopped"
    }
}
