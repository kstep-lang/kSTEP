package dev.kstep.mcp.tools

import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.mcpToolCall
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TOOL_NAME = "list_entities"

fun registerListEntitiesTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Lists every entity currently held in this session's in-memory store, as (id, entityType) " +
                "pairs — use this to see what's already been built without re-deriving it from earlier " +
                "conversation turns. Takes no parameters. An empty store returns count=0, not an error.",
        inputSchema = ToolSchema(required = emptyList()),
    ) { _ ->
        mcpToolCall(TOOL_NAME) {
            val entities = store.snapshot().entries.sortedBy { it.key }
            CallToolResult(
                content =
                    listOf(
                        TextContent(
                            text =
                                if (entities.isEmpty()) {
                                    "No entities built yet."
                                } else {
                                    entities.joinToString("\n") { (id, entry) -> "- $id (${entry.entityType})" }
                                },
                        ),
                    ),
                structuredContent =
                    buildJsonObject {
                        put("count", entities.size)
                        put(
                            "entities",
                            buildJsonArray {
                                for ((id, entry) in entities) {
                                    add(
                                        buildJsonObject {
                                            put("id", id)
                                            put("entityType", entry.entityType)
                                        },
                                    )
                                }
                            },
                        )
                    },
            )
        }
    }
}
