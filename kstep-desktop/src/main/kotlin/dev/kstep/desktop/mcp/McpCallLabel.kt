package dev.kstep.desktop.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The label of a call for the status bar (ADR-0099). Only names this server knows are ever shown; an unknown name is
 * never copied, so no text an agent chose can reach the screen. Arguments are never read.
 */
internal object McpCallLabel {
    const val UNKNOWN_TOOL = "tools/call (unknown tool)"
    const val UNKNOWN_METHOD = "(unknown method)"

    fun of(
        request: JsonObject,
        knownTools: Set<String>,
        knownMethods: Set<String>,
    ): String {
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val label =
            when {
                method == null || method !in knownMethods -> UNKNOWN_METHOD
                method == "tools/call" -> {
                    val name =
                        ((request["params"] as? JsonObject)?.get("name") as? JsonPrimitive)
                            ?.takeIf { it.isString }
                            ?.content
                    if (name != null && name in knownTools) name else UNKNOWN_TOOL
                }
                else -> method
            }
        return McpActivityTracker.sanitize(label)
    }
}
