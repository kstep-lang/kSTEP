package dev.kstep.desktop.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Turns the answer of a call into what the status bar shows (ADR-0099). */
internal object McpCallOutcomes {
    /** `null`: nothing to show, the call is dropped from the tracker (malformed request, nothing answered). */
    fun classify(response: JsonObject?): McpCallOutcome? {
        if (response == null) return null
        val rpcError = JsonRpc.errorCode(response)
        if (rpcError == JsonRpc.INVALID_REQUEST) return null
        if (rpcError != null) return McpCallOutcome.Failed
        val result = response["result"] as? JsonObject ?: return McpCallOutcome.Ok
        val isError = (result["isError"] as? JsonPrimitive)?.content == "true"
        if (!isError) return McpCallOutcome.Ok
        return when (toolCode(result)) {
            McpToolCodes.DENIED, McpToolCodes.CONFIRMATION_TIMEOUT -> McpCallOutcome.Declined
            else -> McpCallOutcome.Failed
        }
    }

    private fun toolCode(result: JsonObject): String? {
        val structured = ((result["structuredContent"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content
        if (structured != null) return structured
        val first = (result["content"] as? JsonArray)?.firstOrNull() as? JsonObject
        val text = (first?.get("text") as? JsonPrimitive)?.content ?: return null
        val colon = text.indexOf(':')
        return if (colon > 0) text.substring(0, colon) else null
    }
}
