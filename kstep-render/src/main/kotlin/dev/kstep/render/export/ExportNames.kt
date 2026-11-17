package dev.kstep.render.export

import dev.kstep.render.RenderLimits

/** Name hygiene for mesh-export part names (ADR-0049 D6). */
object ExportNames {
    /**
     * Keeps only `[A-Za-z0-9 _.-]`, caps at [RenderLimits.EXPORT_MAX_NAME_CHARS] and trims; returns
     * [fallback] when nothing remains. Control characters, markup and path separators never survive.
     */
    fun sanitize(
        raw: String,
        fallback: String,
    ): String {
        val out = StringBuilder()
        for (ch in raw) {
            if (out.length >= RenderLimits.EXPORT_MAX_NAME_CHARS) break
            val ok =
                (ch in 'a'..'z') ||
                    (ch in 'A'..'Z') ||
                    (ch in '0'..'9') ||
                    ch == ' ' ||
                    ch == '_' ||
                    ch == '.' ||
                    ch == '-'
            if (ok) out.append(ch)
        }
        val cleaned = out.toString().trim()
        return cleaned.ifEmpty { fallback }
    }
}
