package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.approval
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildApprovalArgs
import dev.kstep.mcp.findPersonAndOrganization
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
                putJsonObject("status") { put("type", "string") }
                putJsonObject("level") { put("type", "string") }
                putJsonObject("authorized_by_handle") { put("type", "string") }
            },
        required = listOf("handle", "status", "authorized_by_handle"),
    )

fun registerApprovalTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'approval' entity, referencing an already-built person_and_organization " +
                "by authorized_by_handle (must be a handle previously stored by " +
                "build_person_and_organization; an unknown or wrong-type handle returns a structured " +
                "unknown_reference error, not a crash). Stores the result under the caller-supplied 'handle' " +
                "(this entity has no natural id of its own). Re-building with a handle already in the store " +
                "OVERWRITES the previous entry.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args = Json.decodeFromJsonElement<BuildApprovalArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("handle", args.handle)
            requireBoundedString("status", args.status)
            args.level?.let { requireBoundedString("level", it) }
            requireBoundedString("authorized_by_handle", args.authorizedByHandle)

            val authorizedBy =
                store.findPersonAndOrganization(args.authorizedByHandle)
                    ?: return@mcpToolCall unknownReferenceError(
                        listOf(
                            UnknownReference(
                                "authorized_by_handle",
                                args.authorizedByHandle,
                                "person_and_organization",
                            ),
                        ),
                    )

            when (
                val result =
                    approval(args.status) {
                        level = args.level ?: ""
                        this.authorizedBy = authorizedBy
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
                                            "Built approval '${args.handle}': status='${entity.status}', " +
                                                "level='${entity.level}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("status", entity.status)
                                    put("level", entity.level)
                                    put("authorized_by_handle", args.authorizedByHandle)
                                    put("entityType", "approval")
                                },
                        )
                    }
                }
            }
        }
    }
}
