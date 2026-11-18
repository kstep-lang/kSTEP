package dev.kstep.desktop.ai.extract

import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.repair.AiLimits

sealed interface Extraction {
    data class Script(
        val text: String,
    ) : Extraction

    /** [reason] is always a fixed text, never model content. */
    data class Rejected(
        val reason: String,
    ) : Extraction
}

/** Takes the one script out of a model answer and refuses anything suspicious (ADR-0056). */
object ResponseExtractor {
    private val fence = Regex("```([^\\n`]*)\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
    private val kotlinTags = setOf("kotlin", "kts", "kotlin script", "kotlin-script")

    fun extract(
        raw: String,
        limits: AiLimits,
        knownSecrets: List<Secret> = emptyList(),
    ): Extraction {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
        val blocks = fence.findAll(text).toList()
        val chosen =
            blocks.firstOrNull { it.groupValues[1].trim().lowercase() in kotlinTags }
                ?: blocks.singleOrNull { it.groupValues[1].isBlank() }
                ?: return Extraction.Rejected("the answer contains no kotlin code block")
        val code = chosen.groupValues[2].removePrefix("\uFEFF")
        if (code.isBlank()) return Extraction.Rejected("the code block is empty")
        if (code.toByteArray(Charsets.UTF_8).size > limits.maxScriptBytes) {
            return Extraction.Rejected("the script is larger than the editor limit")
        }
        if (DisplayText.hasForbidden(code)) {
            return Extraction.Rejected("the script contains control or bidirectional characters")
        }
        if (knownSecrets.any { it.occursIn(code) }) {
            return Extraction.Rejected("the script contains a configured API key")
        }
        return Extraction.Script(code)
    }
}
