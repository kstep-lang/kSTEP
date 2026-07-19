package dev.kstep.mcp.tools

import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.describeEntry
import dev.kstep.mcp.dto.GetEntityArgs
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.requireBoundedString
import dev.kstep.mcp.unknownReferenceError
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TOOL_NAME = "get_entity"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("id") { put("type", "string") }
            },
        required = listOf("id"),
    )

fun registerGetEntityTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Returns a full field dump of one entity previously built in this session, by its store id " +
                "(the id/handle a build_* tool call stored it under — see list_entities). Entity-typed " +
                "reference fields are rendered as the referenced entity's own store id, not a nested " +
                "object. An unknown id returns a structured unknown_reference error.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args = Json.decodeFromJsonElement<GetEntityArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("id", args.id)

            val entry =
                store.get(args.id)
                    ?: return@mcpToolCall unknownReferenceError(listOf(UnknownReference("id", args.id, null)))

            val description = describeEntry(store, entry)
            CallToolResult(
                content = listOf(TextContent(text = "Entity '${args.id}': ${entry.entityType}")),
                structuredContent =
                    buildJsonObject {
                        put("id", args.id)
                        for ((key, value) in description) {
                            put(key, value)
                        }
                    },
            )
        }
    }
}
