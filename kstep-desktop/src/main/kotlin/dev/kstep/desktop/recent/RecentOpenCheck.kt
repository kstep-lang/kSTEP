package dev.kstep.desktop.recent

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** The result of looking at a path right before it is opened (ADR-0066). */
sealed interface RecentCheck {
    /** Safe to open; [path] is the real path. */
    data class Ok(
        val path: Path,
    ) : RecentCheck

    data object Missing : RecentCheck

    /** The path, or a folder above it, is a symbolic link: it leads somewhere the user did not pick. */
    data object Link : RecentCheck

    /** A file where a folder was expected or the reverse. */
    data object WrongType : RecentCheck
}

/** Checks a Recent entry or a dropped path at click time, never when a menu opens. Does file I/O: call off the UI thread. */
object RecentOpenCheck {
    fun check(entry: RecentEntry): RecentCheck = checkPath(entry.path, entry.kind == RecentKind.FOLDER)

    fun checkPath(
        path: Path,
        wantDirectory: Boolean,
    ): RecentCheck =
        try {
            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            when {
                attrs.isSymbolicLink -> RecentCheck.Link
                wantDirectory && !attrs.isDirectory -> RecentCheck.WrongType
                !wantDirectory && !attrs.isRegularFile -> RecentCheck.WrongType
                else -> {
                    val real = path.toRealPath()
                    if (real == path.toAbsolutePath().normalize()) RecentCheck.Ok(real) else RecentCheck.Link
                }
            }
        } catch (_: NoSuchFileException) {
            RecentCheck.Missing
        } catch (_: IOException) {
            RecentCheck.Missing
        } catch (_: SecurityException) {
            RecentCheck.Missing
        }
}
