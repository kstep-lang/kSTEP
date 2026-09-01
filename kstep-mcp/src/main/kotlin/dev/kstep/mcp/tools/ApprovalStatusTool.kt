package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.approvalStatus
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.dto.BuildApprovalStatusArgs
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.requireBoundedString
import dev.kstep.mcp.storeOrCapacityError
import dev.kstep.mcp.validationFailedError
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

private const val TOOL_NAME = "build_approval_status"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("handle") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
            },
        required = listOf("handle", "name"),
    )

fun registerBuildApprovalStatusTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'approval_status' entity (one mandatory 'name' label) and stores it under " +
                "the caller-supplied 'handle' (this entity has no natural id of its own). Referenced later by " +
                "build_approval's status_handle. Re-building with a handle already in the store OVERWRITES " +
                "the previous entry.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildApprovalStatusArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("handle", args.handle)
            args.name?.let { requireBoundedString("name", it) }

            when (val result = approvalStatus { name = args.name }) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.handle, EntityStoreEntry.ApprovalStatusEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(text = "Built approval_status '${args.handle}': name='${entity.name}'"),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("name", entity.name)
                                    put("entityType", "approval_status")
                                },
                        )
                    }
                }
            }
        }
    }
}
