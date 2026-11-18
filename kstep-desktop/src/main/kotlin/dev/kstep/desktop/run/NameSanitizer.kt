package dev.kstep.desktop.run

import dev.kstep.cli.DesktopPartLimits
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Cleans names from the render worker's JSON before they reach the UI (shared by the part list and the tree). */
internal object NameSanitizer {
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /**
     * ANSI escapes are removed; other control characters and bidirectional overrides/isolates become U+FFFD (they
     * could reorder or hide text in the inspection panel). Rejects empty names and names with no visible character.
     */
    fun cleanName(e: JsonElement?): String? {
        val p = e as? JsonPrimitive ?: return null
        if (!p.isString) return null
        val raw = p.content
        if (raw.isEmpty() || raw.length > DesktopPartLimits.MAX_NAME_CHARS) return null
        val sb = StringBuilder(raw.length)
        for (ch in ansi.replace(raw, "")) sb.append(if (isUnsafe(ch)) '\uFFFD' else ch)
        val cleaned = sb.toString()
        if (cleaned.all { it == '\uFFFD' || it.isWhitespace() }) return null
        return cleaned
    }

    private fun isUnsafe(c: Char): Boolean =
        c.code < 0x20 ||
            c.code == 0x7f ||
            c.code in 0x80..0x9f ||
            c.code in 0x202A..0x202E ||
            c.code in 0x2066..0x2069 ||
            c.code == 0x200E ||
            c.code == 0x200F ||
            c.code == 0x061C ||
            c.code == 0x2028 ||
            c.code == 0x2029
}
