package dev.kstep.desktop.mcp

import kotlinx.serialization.json.JsonObject

/**
 * Keeps the answer of a tool below a byte limit (ADR-0096 D5). The size is measured on the finished `tools/call`
 * result, not on the body: with protocol 2025-06-18 the body is in the answer twice, and the text copy is an escaped
 * JSON string, so it is larger than the body.
 */
internal object McpResponseBudget {
    const val MAX_RESPONSE_BYTES = 1 shl 20

    /** What the JSON-RPC envelope around the result may add. */
    private const val ENVELOPE_MARGIN = 1024

    fun encodedSize(
        body: JsonObject,
        structured: Boolean,
    ): Int = mcpToolResult(body, structured).toString().toByteArray(Charsets.UTF_8).size

    /**
     * The body with the largest number of entries (0..[total]) that fits [max]. [build] gets the entry count and whether
     * the list was cut. Whole list fitting means one build. Not even zero entries fitting is [McpToolCodes.TOO_LARGE].
     */
    fun fit(
        total: Int,
        structured: Boolean,
        max: Int = MAX_RESPONSE_BYTES,
        build: (n: Int, truncated: Boolean) -> JsonObject,
    ): JsonObject {
        val limit = max - ENVELOPE_MARGIN
        val whole = build(total, false)
        if (encodedSize(whole, structured) <= limit) return whole
        var lo = 0
        var hi = total - 1
        var best: JsonObject? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val candidate = build(mid, true)
            if (encodedSize(candidate, structured) <= limit) {
                best = candidate
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best ?: throw McpToolException(McpToolCodes.TOO_LARGE)
    }
}
