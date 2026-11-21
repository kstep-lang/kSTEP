package dev.kstep.cli.okf

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.Locale

/**
 * Lists the source files of a recursive `kstep convert` run (ADR-0071): breadth first with an explicit queue (no
 * recursion, no `Files.walk`), links are never followed, hidden folders and the output folder are not entered, every
 * folder must stay below the root after resolving, and every bound counts over the whole tree. No partial result: when
 * a bound is hit the whole listing fails. [ConvertSourceScanner] (one level) is deliberately left untouched.
 */
internal object ConvertTreeScanner {
    enum class SkipKind { SYMBOLIC_LINK, NOT_REGULAR, HIDDEN, HIDDEN_DIR, OUTPUT_DIR }

    data class Candidate(
        val relDir: List<String>,
        val name: String,
        val path: Path,
        val size: Long,
        val fileKey: Any?,
    ) {
        val relPath: String get() = (relDir + name).joinToString("/")
    }

    data class Ignored(
        val relPath: String,
        val kind: SkipKind,
    )

    enum class FailKind { LIMIT, UNREADABLE, DEPTH, ESCAPE }

    sealed interface Outcome {
        data class Ok(
            val candidates: List<Candidate>,
            val ignored: List<Ignored>,
        ) : Outcome

        data class Failed(
            val kind: FailKind,
            val message: String,
        ) : Outcome
    }

    private class Dir(
        val real: Path,
        val relDir: List<String>,
    )

    /** True when [real] equals or lies below [rootReal]; `Path.startsWith` on real paths, never a string prefix. */
    fun isContained(
        rootReal: Path,
        real: Path,
    ): Boolean = real.startsWith(rootReal)

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount", "LongParameterList")
    fun list(
        rootReal: Path,
        suffix: String,
        outReal: Path?,
        maxDepth: Int,
        maxEntries: Int,
        maxFiles: Int,
        maxBytes: Long,
        beforeDescend: (Path) -> Unit = {},
    ): Outcome {
        val candidates = ArrayList<Candidate>()
        val ignored = ArrayList<Ignored>()
        val queue = ArrayDeque<Dir>()
        queue.add(Dir(rootReal, emptyList()))
        var entries = 0
        var bytes = 0L
        try {
            while (queue.isNotEmpty()) {
                val dir = queue.poll()
                Files.newDirectoryStream(dir.real).use { stream ->
                    // Re-check after opening: the folder must still be the one that was checked (TOCTOU, ADR-0071).
                    if (dir.real.toRealPath() != dir.real) {
                        return Outcome.Failed(FailKind.ESCAPE, "a folder changed while it was read")
                    }
                    for (entry in stream) {
                        if (++entries > maxEntries) {
                            return Outcome.Failed(FailKind.LIMIT, "the tree has more than $maxEntries entries")
                        }
                        val name = entry.fileName.toString()
                        val rel = (dir.relDir + name).joinToString("/")
                        val attrs =
                            Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        when {
                            attrs.isSymbolicLink -> ignored += Ignored(rel, SkipKind.SYMBOLIC_LINK)
                            attrs.isDirectory -> {
                                when {
                                    name.startsWith(".") -> ignored += Ignored(rel, SkipKind.HIDDEN_DIR)
                                    outReal != null && outReal != rootReal && entry == outReal ->
                                        ignored += Ignored(rel, SkipKind.OUTPUT_DIR)
                                    dir.relDir.size + 1 > maxDepth ->
                                        return Outcome.Failed(
                                            FailKind.DEPTH,
                                            "the folder ${DisplayName.escaped(rel)} is deeper than $maxDepth levels",
                                        )
                                    else -> {
                                        beforeDescend(entry)
                                        val real = entry.toRealPath()
                                        if (!isContained(rootReal, real)) {
                                            return Outcome.Failed(
                                                FailKind.ESCAPE,
                                                "the folder ${DisplayName.escaped(rel)} leads outside the input folder",
                                            )
                                        }
                                        queue.add(Dir(real, dir.relDir + name))
                                    }
                                }
                            }
                            !name.lowercase(Locale.ROOT).endsWith(suffix) -> Unit
                            !attrs.isRegularFile -> ignored += Ignored(rel, SkipKind.NOT_REGULAR)
                            name.startsWith(".") -> ignored += Ignored(rel, SkipKind.HIDDEN)
                            else -> {
                                if (candidates.size >= maxFiles) {
                                    return Outcome.Failed(
                                        FailKind.LIMIT,
                                        "the tree has more than $maxFiles matching files",
                                    )
                                }
                                bytes += attrs.size()
                                if (bytes > maxBytes) {
                                    return Outcome.Failed(
                                        FailKind.LIMIT,
                                        "the matching files are larger than ${maxBytes / 1024} KiB together",
                                    )
                                }
                                if (!isContained(rootReal, entry.toRealPath())) {
                                    return Outcome.Failed(
                                        FailKind.ESCAPE,
                                        "the file ${DisplayName.escaped(rel)} leads outside the input folder",
                                    )
                                }
                                candidates += Candidate(dir.relDir, name, entry, attrs.size(), attrs.fileKey())
                            }
                        }
                    }
                }
            }
        } catch (_: DirectoryIteratorException) {
            return Outcome.Failed(FailKind.UNREADABLE, "the input folder cannot be read")
        } catch (_: IOException) {
            return Outcome.Failed(FailKind.UNREADABLE, "the input folder cannot be read")
        }
        candidates.sortWith { a, b -> a.relPath.compareTo(b.relPath) }
        ignored.sortWith { a, b -> a.relPath.compareTo(b.relPath) }
        return Outcome.Ok(candidates, ignored)
    }
}
