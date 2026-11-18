package dev.kstep.desktop.okf

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

enum class PartialReason { FILES, ENTRIES, BYTES, TIME, DEPTH }

data class WorkspaceEntry(
    /** Path below the workspace root, always with `/` separators. */
    val relative: String,
    val path: Path,
    val size: Long,
    val title: String?,
    val blockCount: Int,
    val tooLarge: Boolean,
    val frontmatterIgnored: Boolean,
)

sealed interface ScanResult {
    data class Ok(
        val root: Path,
        val entries: List<WorkspaceEntry>,
        val partial: Set<PartialReason>,
        val linksSkipped: Int,
    ) : ScanResult

    data class Failed(
        val error: DesktopError,
    ) : ScanResult
}

data class ScanLimits(
    val maxFiles: Int = OkfLimits.MAX_MD_FILES,
    val maxDepth: Int = OkfLimits.MAX_DEPTH,
    val maxEntries: Int = OkfLimits.MAX_DIR_ENTRIES,
    val maxBytes: Long = OkfLimits.MAX_SCAN_BYTES,
    val maxMillis: Long = OkfLimits.MAX_SCAN_MILLIS,
    val maxFileBytes: Long = OkfLimits.MAX_FILE_BYTES,
)

/**
 * Lists the Markdown notes of a folder (ADR-0058, D2/D13). Symbolic links are never followed and never listed (they are
 * only counted), hidden folders and `node_modules` are skipped, and the walk is bounded in depth, entries, files, bytes
 * read and time; a bound ends the walk and is reported as a partial result. The first look at every note reads at most
 * [ScanLimits.maxFileBytes] bytes (title, number of blocks); a note above that is listed but not read.
 */
class WorkspaceScanner(
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MILLI },
    private val limits: ScanLimits = ScanLimits(),
) {
    fun scan(
        dir: Path,
        cancelled: () -> Boolean,
    ): ScanResult {
        val root =
            try {
                dir.toRealPath()
            } catch (_: IOException) {
                return failed("The folder cannot be opened.")
            } catch (_: SecurityException) {
                return failed("The folder cannot be opened.")
            }
        if (!Files.isDirectory(root) || !Files.isReadable(root)) return failed("The folder cannot be opened.")
        val started = clock()
        val entries = ArrayList<WorkspaceEntry>()
        val partial = LinkedHashSet<PartialReason>()
        var seen = 0
        var bytes = 0L
        var links = 0
        var userCancelled = false

        fun stopReason(): PartialReason? =
            when {
                seen > limits.maxEntries -> PartialReason.ENTRIES
                clock() - started > limits.maxMillis -> PartialReason.TIME
                else -> null
            }

        val visitor =
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    d: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (cancelled()) {
                        userCancelled = true
                        return FileVisitResult.TERMINATE
                    }
                    seen++
                    stopReason()?.let {
                        partial += it
                        return FileVisitResult.TERMINATE
                    }
                    if (d != root) {
                        val name = d.fileName?.toString().orEmpty()
                        if (name.startsWith(".") || name == "node_modules") return FileVisitResult.SKIP_SUBTREE
                        // Windows directory junctions are not symbolic links to the JDK: compare the real path
                        val escapes =
                            attrs.isOther ||
                                try {
                                    d.toRealPath() != d
                                } catch (_: IOException) {
                                    true
                                }
                        if (escapes) {
                            links++
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    f: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (cancelled()) {
                        userCancelled = true
                        return FileVisitResult.TERMINATE
                    }
                    seen++
                    stopReason()?.let {
                        partial += it
                        return FileVisitResult.TERMINATE
                    }
                    if (attrs.isSymbolicLink) {
                        links++
                        return FileVisitResult.CONTINUE
                    }
                    if (attrs.isDirectory) {
                        // the walk is not allowed to go deeper
                        partial += PartialReason.DEPTH
                        return FileVisitResult.CONTINUE
                    }
                    if (!attrs.isRegularFile || !isMarkdown(f.fileName.toString())) return FileVisitResult.CONTINUE
                    if (entries.size >= limits.maxFiles) {
                        partial += PartialReason.FILES
                        return FileVisitResult.TERMINATE
                    }
                    val size = attrs.size()
                    val relative = root.relativize(f).joinToString("/") { it.toString() }
                    if (size > limits.maxFileBytes) {
                        entries +=
                            WorkspaceEntry(relative, f, size, null, 0, tooLarge = true, frontmatterIgnored = false)
                        return FileVisitResult.CONTINUE
                    }
                    if (bytes + size > limits.maxBytes) {
                        partial += PartialReason.BYTES
                        return FileVisitResult.TERMINATE
                    }
                    bytes += size
                    entries += inspect(f, relative, size)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    f: Path,
                    exc: IOException,
                ): FileVisitResult {
                    seen++
                    return FileVisitResult.CONTINUE
                }
            }
        try {
            Files.walkFileTree(root, emptySet(), limits.maxDepth, visitor)
        } catch (_: IOException) {
            return failed("The folder cannot be read.")
        } catch (_: SecurityException) {
            return failed("The folder cannot be read.")
        }
        if (userCancelled) return failed("The scan was cancelled.")
        if (entries.isEmpty()) {
            return failed(
                "No Markdown files in ${DisplayName.escaped(
                    root.fileName?.toString() ?: root.toString(),
                )}. To edit a script, use Open…",
            )
        }
        entries.sortWith { a, b -> compareNames(a.relative, b.relative) }
        return ScanResult.Ok(root, entries, partial, links)
    }

    private fun inspect(
        file: Path,
        relative: String,
        size: Long,
    ): WorkspaceEntry {
        val text =
            try {
                Files.newInputStream(file).use { String(it.readNBytes(size.toInt().coerceAtLeast(0)), Charsets.UTF_8) }
            } catch (_: IOException) {
                return WorkspaceEntry(relative, file, size, null, 0, tooLarge = false, frontmatterIgnored = false)
            }
        val fm = FrontmatterParser.parse(text)
        val ex = MarkdownBlockExtractor.extract(text, fm.bodyStartLine)
        return WorkspaceEntry(
            relative,
            file,
            size,
            fm.title,
            ex.blocks.size,
            tooLarge = false,
            frontmatterIgnored = fm.ignored,
        )
    }

    private fun failed(message: String) =
        ScanResult.Failed(DesktopError(DesktopErrorCodes.WORKSPACE_UNAVAILABLE, null, null, message))

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        fun isMarkdown(name: String): Boolean =
            name.length > 3 && name.regionMatches(name.length - 3, ".md", 0, 3, ignoreCase = true)

        /** Lower-cased first, code point by code point; the original spelling breaks ties. */
        fun compareNames(
            a: String,
            b: String,
        ): Int {
            val c = compareCodePoints(a.lowercase(Locale.ROOT), b.lowercase(Locale.ROOT))
            return if (c != 0) c else compareCodePoints(a, b)
        }

        private fun compareCodePoints(
            a: String,
            b: String,
        ): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val x = a.codePointAt(i)
                val y = b.codePointAt(j)
                if (x != y) return x.compareTo(y)
                i += Character.charCount(x)
                j += Character.charCount(y)
            }
            return (a.length - i).compareTo(b.length - j)
        }
    }
}
