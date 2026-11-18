package dev.kstep.desktop.edit

/** Start offsets of the lines of a text with `\n` line endings. Built in O(n); lookups are O(log n). */
class LineIndex(
    text: CharSequence,
) {
    private val starts: IntArray
    val textLength: Int = text.length

    init {
        var count = 1
        for (i in 0 until text.length) if (text[i] == '\n') count++
        starts = IntArray(count)
        var k = 1
        for (i in 0 until text.length) if (text[i] == '\n') starts[k++] = i + 1
    }

    val lineCount: Int get() = starts.size

    /** Offset of the first character of [line] (1-based, clamped). */
    fun lineStart(line: Int): Int = starts[line.coerceIn(1, lineCount) - 1]

    /** Offset just before the line break of [line] (or the text end). */
    fun lineEnd(line: Int): Int {
        val l = line.coerceIn(1, lineCount)
        return if (l == lineCount) textLength else starts[l] - 1
    }

    /** The 1-based line that contains [offset]. */
    fun lineOf(offset: Int): Int {
        val o = offset.coerceIn(0, textLength)
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= o) lo = mid else hi = mid - 1
        }
        return lo + 1
    }
}
