package dev.kstep.desktop.ai.extract

import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.repair.AiLimits

/** One compiler diagnostic as handed to the AI loop. */
data class Diagnostic(
    val code: String,
    val line: Int?,
    val column: Int?,
    val message: String,
)

/** Builds the short, scrubbed error text a repair request carries (ADR-0056): no paths, no key-like strings. */
object ErrorSummary {
    private const val MAX_ENTRIES = 20
    private val patterns =
        listOf(
            Regex("sk-ant-[A-Za-z0-9_-]{10,}"),
            Regex("sk-[A-Za-z0-9]{20,}"),
            Regex("(?i)(api[_-]?key|token|secret)\\s*[:=]\\s*\\S+"),
            Regex("[A-Za-z0-9+/=_-]{32,}"),
        )
    private val paths =
        listOf(
            Regex("file:\\S+"),
            Regex("[A-Za-z]:\\\\[^\\s:\"']*"),
            Regex("~/[^\\s:\"']*"),
            Regex("(?<![\\w.])/[^\\s:\"']+"),
        )
    private val candidateName = Regex("candidate-\\d+\\.kstep\\.kts")

    fun scrub(
        text: String,
        knownSecrets: List<Secret>,
    ): String {
        var s = DisplayText.sanitize(text)
        for (k in knownSecrets) s = k.redact(s)
        s = candidateName.replace(s, "script.kstep.kts")
        for (p in paths) s = p.replace(s, "<path>")
        for (p in patterns) s = p.replace(s, "<redacted>")
        return s
    }

    fun forModel(
        diags: List<Diagnostic>,
        limits: AiLimits,
        knownSecrets: List<Secret> = emptyList(),
    ): String {
        val body =
            diags.take(MAX_ENTRIES).joinToString("\n") { d ->
                val pos = if (d.line != null) " ${d.line}:${d.column ?: 0}" else ""
                scrub("${d.code}$pos ${d.message}".replace('\n', ' '), knownSecrets)
            }
        return truncateUtf8(body, limits.maxErrorSummaryBytes)
    }

    internal fun truncateUtf8(
        s: String,
        maxBytes: Int,
    ): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        var end = s.length
        while (end > 0 && s.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end)
    }
}
