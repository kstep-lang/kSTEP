package dev.kstep.desktop.okf

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

sealed interface PathCheck {
    data class Ok(
        val path: Path,
    ) : PathCheck

    data class Rejected(
        val reason: String,
    ) : PathCheck
}

/**
 * The path rule of the workspace (ADR-0058, D13), applied on every open and every save: the file must be a regular file,
 * not a symbolic link, and its real path must lie under the (real) workspace root. The file key is deliberately NOT
 * compared: the atomic save replaces the file, so its inode changes with every save of our own.
 */
object WorkspacePaths {
    fun resolve(
        rootReal: Path,
        candidate: Path,
    ): PathCheck {
        if (!candidate.isAbsolute && candidate.any { it.toString() == ".." }) {
            return PathCheck.Rejected("a parent reference (..) is not allowed")
        }
        val absolute =
            (
                if (candidate.isAbsolute) {
                    candidate
                } else {
                    rootReal.resolve(
                        candidate,
                    )
                }
            ).toAbsolutePath().normalize()
        if (!absolute.startsWith(rootReal)) return PathCheck.Rejected("the file is outside the workspace")
        try {
            val attrs =
                try {
                    Files.readAttributes(
                        absolute,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: java.nio.file.NoSuchFileException) {
                    return PathCheck.Rejected("the file no longer exists")
                }
            if (attrs.isSymbolicLink) return PathCheck.Rejected("the file is a symbolic link")
            if (!attrs.isRegularFile) return PathCheck.Rejected("the entry is not a regular file")
            val real = absolute.toRealPath()
            if (!real.startsWith(rootReal)) return PathCheck.Rejected("the file resolves outside the workspace")
            return PathCheck.Ok(real)
        } catch (_: IOException) {
            return PathCheck.Rejected("the file cannot be checked")
        } catch (_: SecurityException) {
            return PathCheck.Rejected("the file cannot be checked")
        }
    }
}
