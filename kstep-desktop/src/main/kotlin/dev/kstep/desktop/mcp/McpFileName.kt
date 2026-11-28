package dev.kstep.desktop.mcp

import java.text.Normalizer
import java.util.Locale

/** The export formats an agent may ask for (ADR-0098); the window side maps them to its own format type. */
enum class McpExportFormat(
    val id: String,
    val extension: String,
) {
    STEP("step", "step"),
    GLB("glb", "glb"),
    STL("stl", "stl"),
    THREE_MF("3mf", "3mf"),
    ;

    companion object {
        fun of(id: String): McpExportFormat? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A file name an agent proposed for an export, checked (ADR-0098 D6). It is only a name, never a path: 1 to 100 code
 * points of letters, digits, space, `-`, `_` and `.`; no leading or trailing dot or space; no control, format or
 * separator characters; no Windows device names. A known export extension is removed. Only [parse] creates one.
 */
class McpSafeFileName private constructor(
    val value: String,
) {
    // A plain class, not a value class: a value class would show up as a bare String in the port signature (ADR-0098).
    override fun toString(): String = "McpSafeFileName"

    companion object {
        private const val MAX_POINTS = 100
        private val RESERVED =
            setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }
        private val EXTENSIONS = McpExportFormat.entries.map { "." + it.extension } + listOf(".stp")

        fun parse(raw: String): McpSafeFileName? {
            var name = Normalizer.normalize(raw, Normalizer.Form.NFC)
            val lower = name.lowercase(Locale.ROOT)
            EXTENSIONS.firstOrNull { lower.endsWith(it) && name.length > it.length }?.let {
                name = name.substring(0, name.length - it.length)
            }
            if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_POINTS) return null
            if (name == "." || name == "..") return null
            if (name.first() == '.' || name.first() == ' ' || name.last() == '.' || name.last() == ' ') return null
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                val ok =
                    Character.isLetterOrDigit(cp) ||
                        cp == ' '.code ||
                        cp == '-'.code ||
                        cp == '_'.code ||
                        cp == '.'.code
                if (!ok) return null
                val type = Character.getType(cp)
                if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) return null
                i += Character.charCount(cp)
            }
            val stem = name.substringBefore('.').trimEnd().uppercase(Locale.ROOT)
            if (stem in RESERVED) return null
            return McpSafeFileName(name)
        }
    }
}
