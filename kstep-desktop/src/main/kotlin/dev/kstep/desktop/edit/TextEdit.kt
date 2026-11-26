package dev.kstep.desktop.edit

/**
 * One replacement that turns an old text into a new one: the old range `[start, oldEnd)` became `[start, newEnd)`
 * (ADR-0085). Found by comparing the two texts, so every edit path of the editor (typing, paste, replace, undo, redo)
 * is described the same way without being hooked one by one.
 */
data class TextEdit(
    val start: Int,
    val oldEnd: Int,
    val newEnd: Int,
) {
    val delta: Int get() = newEnd - oldEnd

    companion object {
        /** The smallest single replacement from [old] to [new] in O(n); `null` when the texts are equal. */
        fun between(
            old: CharSequence,
            new: CharSequence,
        ): TextEdit? {
            val min = minOf(old.length, new.length)
            var prefix = 0
            while (prefix < min && old[prefix] == new[prefix]) prefix++
            if (prefix == old.length && prefix == new.length) return null
            // never split a surrogate pair: a common prefix that ends after a high surrogate backs off one unit
            if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix--
            var suffix = 0
            val maxSuffix = min - prefix
            while (suffix < maxSuffix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            // likewise a common suffix must not start with a low surrogate
            if (suffix > 0 && old[old.length - suffix].isLowSurrogate()) suffix--
            return TextEdit(prefix, old.length - suffix, new.length - suffix)
        }
    }
}
