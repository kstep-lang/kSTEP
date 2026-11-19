package dev.kstep.cli.okf

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/**
 * Lists the source files of a `kstep convert` folder run (ADR-0061): one level only (targets are written flat and the
 * output folder is never created), entries are never followed when they are links, and every bound is checked before
 * anything is read. No partial result: when a limit is hit the whole listing fails.
 */
internal object ConvertSourceScanner {
    enum class IgnoredReason { SYMBOLIC_LINK, NOT_REGULAR, HIDDEN }

    data class Candidate(
        val name: String,
        val path: Path,
        val size: Long,
        val fileKey: Any?,
    )

    data class Ignored(
        val name: String,
        val reason: IgnoredReason,
    )

    sealed interface Outcome {
        data class Ok(
            val candidates: List<Candidate>,
            val ignored: List<Ignored>,
        ) : Outcome

        /** [limit] is true when a bound was exceeded, false for an unreadable folder. */
        data class Failed(
            val limit: Boolean,
            val message: String,
        ) : Outcome
    }

    fun list(
        dirReal: Path,
        suffix: String,
        maxEntries: Int,
        maxFiles: Int,
        maxBytes: Long,
    ): Outcome {
        val candidates = ArrayList<Candidate>()
        val ignored = ArrayList<Ignored>()
        var entries = 0
        var bytes = 0L
        try {
            Files.newDirectoryStream(dirReal).use { stream ->
                for (entry in stream) {
                    if (++entries > maxEntries) {
                        return Outcome.Failed(true, "the folder has more than $maxEntries entries")
                    }
                    val name = entry.fileName.toString()
                    if (!name.lowercase(Locale.ROOT).endsWith(suffix)) continue
                    val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    when {
                        attrs.isSymbolicLink -> ignored += Ignored(name, IgnoredReason.SYMBOLIC_LINK)
                        !attrs.isRegularFile -> ignored += Ignored(name, IgnoredReason.NOT_REGULAR)
                        name.startsWith(".") -> ignored += Ignored(name, IgnoredReason.HIDDEN)
                        else -> {
                            if (candidates.size >= maxFiles) {
                                return Outcome.Failed(true, "the folder has more than $maxFiles matching files")
                            }
                            bytes += attrs.size()
                            if (bytes > maxBytes) {
                                return Outcome.Failed(
                                    true,
                                    "the matching files are larger than ${maxBytes / 1024} KiB together",
                                )
                            }
                            candidates += Candidate(name, entry, attrs.size(), attrs.fileKey())
                        }
                    }
                }
            }
        } catch (_: DirectoryIteratorException) {
            return Outcome.Failed(false, "the input folder cannot be read")
        } catch (_: IOException) {
            return Outcome.Failed(false, "the input folder cannot be read")
        }
        candidates.sortWith { a, b -> a.name.compareTo(b.name) }
        ignored.sortWith { a, b -> a.name.compareTo(b.name) }
        return Outcome.Ok(candidates, ignored)
    }
}
