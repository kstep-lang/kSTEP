package dev.kstep.desktop.recent

import dev.kstep.desktop.ai.extract.DisplayText

/**
 * The AI prompt history (ADR-0069): the last [MAX_ENTRIES] distinct prompts, newest first. Pure logic. Only prompt text
 * is ever held here, never a key, model, provider, script or answer.
 */
class PromptHistory private constructor(
    val entries: List<String>,
) {
    /** [prompt] first (an equal older entry moves up); an empty or whitespace-only prompt changes nothing. */
    fun record(prompt: String): PromptHistory {
        val clean = sanitize(prompt) ?: return this
        return PromptHistory((listOf(clean) + entries.filter { it != clean }).take(MAX_ENTRIES))
    }

    companion object {
        const val MAX_ENTRIES = 50

        /** Equals `AiLimits().maxPromptChars` (a test keeps them in step). */
        const val MAX_CHARS = 4_000

        fun empty(): PromptHistory = PromptHistory(emptyList())

        /** Sanitizes, drops empty ones, removes duplicates (the first wins) and clamps the count. */
        fun of(raw: List<String>): PromptHistory {
            val seen = LinkedHashSet<String>()
            for (r in raw) {
                val s = sanitize(r) ?: continue
                seen.add(s)
                if (seen.size >= MAX_ENTRIES) break
            }
            return PromptHistory(seen.toList())
        }

        /** Control and bidi characters removed (`\n`, `\t` stay), trimmed, clamped; `null` if nothing is left. */
        fun sanitize(s: String): String? {
            val t = DisplayText.sanitize(s).trim()
            if (t.isEmpty()) return null
            if (t.length <= MAX_CHARS) return t
            var end = MAX_CHARS
            if (Character.isHighSurrogate(t[end - 1])) end--
            return t.substring(0, end).trim().ifEmpty { null }
        }
    }
}
