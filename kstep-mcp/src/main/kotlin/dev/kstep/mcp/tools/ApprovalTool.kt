package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.approval
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildApprovalArgs
import dev.kstep.mcp.findApprovalStatus
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.requireBoundedString
import dev.kstep.mcp.storeOrCapacityError
import dev.kstep.mcp.unknownReferenceError
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

private const val TOOL_NAME = "build_approval"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("handle") { put("type", "string") }
                putJsonObject("status_handle") { put("type", "string") }
                putJsonObject("level") { put("type", "string") }
            },
        required = listOf("handle", "status_handle", "level"),
    )

fun registerApprovalTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'approval' entity, referencing an already-built approval_status by " +
                "status_handle (must be a handle previously stored by build_approval_status; an unknown or " +
                "wrong-type handle returns a structured unknown_reference error, not a crash). 'level' is " +
                "mandatory in the real AP242 schema (non-OPTIONAL 'label') and omitting it returns a " +
                "structured validation_failed error (KSTEP-M-002). Stores the result under the " +
                "caller-supplied 'handle' (this entity has no natural id of its own). Re-building with a " +
                "handle already in the store OVERWRITES the previous entry.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args = Json.decodeFromJsonElement<BuildApprovalArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("handle", args.handle)
            requireBoundedString("status_handle", args.statusHandle)
            args.level?.let { requireBoundedString("level", it) }

            val status =
                store.findApprovalStatus(args.statusHandle)
                    ?: return@mcpToolCall unknownReferenceError(
                        listOf(UnknownReference("status_handle", args.statusHandle, "approval_status")),
                    )

            when (
                val result =
                    approval {
                        this.status = status
                        level = args.level
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.handle, EntityStoreEntry.ApprovalEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built approval '${args.handle}': status='${status.name}', " +
                                                "level='${entity.level}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("status_handle", args.statusHandle)
                                    put("level", entity.level)
                                    put("entityType", "approval")
                                },
                        )
                    }
                }
            }
        }
    }
}
