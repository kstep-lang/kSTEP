package dev.kstep.desktop.recent

import java.nio.file.Path
import java.util.Locale

/**
 * What a Recent entry opens: a script in the editor, a folder as a knowledge workspace (ADR-0066), or a single note
 * that was opened on its own, together with its workspace folder (ADR-0083).
 */
enum class RecentKind { SCRIPT, FOLDER, NOTE }

/** [root] is only set for a [RecentKind.NOTE]: the workspace folder the note was opened under. */
data class RecentEntry(
    val path: Path,
    val kind: RecentKind,
    val root: Path? = null,
)

/**
 * The most recently opened scripts and folders, newest first, at most [MAX_ENTRIES]. Immutable; every change returns a
 * new list. Two entries are the same when their normalized absolute paths are equal (compared case-insensitively on
 * macOS and Windows, where the file systems are), whatever their kind.
 */
class RecentList private constructor(
    val entries: List<RecentEntry>,
    private val caseInsensitive: Boolean,
) {
    fun touch(entry: RecentEntry): RecentList {
        val key = keyOf(entry.path, caseInsensitive)
        return RecentList(
            (
                listOf(entry) +
                    entries.filter {
                        keyOf(it.path, caseInsensitive) != key
                    }
            ).take(MAX_ENTRIES),
            caseInsensitive,
        )
    }

    fun remove(path: Path): RecentList {
        val key = keyOf(path, caseInsensitive)
        return RecentList(entries.filter { keyOf(it.path, caseInsensitive) != key }, caseInsensitive)
    }

    fun clear(): RecentList = RecentList(emptyList(), caseInsensitive)

    override fun equals(other: Any?): Boolean = other is RecentList && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    companion object {
        const val MAX_ENTRIES = 10

        fun empty(caseInsensitive: Boolean = osCaseInsensitive()): RecentList = RecentList(emptyList(), caseInsensitive)

        /** Builds a list from [list] (newest first): duplicates dropped (first wins), cut at [MAX_ENTRIES]. */
        fun of(
            list: List<RecentEntry>,
            caseInsensitive: Boolean = osCaseInsensitive(),
        ): RecentList {
            val seen = HashSet<String>()
            val kept = list.filter { seen.add(keyOf(it.path, caseInsensitive)) }.take(MAX_ENTRIES)
            return RecentList(kept, caseInsensitive)
        }

        fun osCaseInsensitive(): Boolean {
            val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            return os.contains("mac") || os.contains("win")
        }

        private fun keyOf(
            path: Path,
            caseInsensitive: Boolean,
        ): String {
            val text = path.toAbsolutePath().normalize().toString()
            return if (caseInsensitive) text.lowercase(Locale.ROOT) else text
        }
    }
}
