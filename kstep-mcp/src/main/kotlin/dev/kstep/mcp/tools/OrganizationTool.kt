package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.organization
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.dto.BuildOrganizationArgs
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

private const val TOOL_NAME = "build_organization"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("handle") { put("type", "string") }
                putJsonObject("id") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
            },
        required = listOf("handle", "name"),
    )

fun registerBuildOrganizationTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'organization' entity and stores it under the caller-supplied 'handle' " +
                "(NOT its own 'id' — unlike product/product_definition/etc, the real AP242 organization.id " +
                "is itself genuinely OPTIONAL, so it cannot reliably serve as a store key). 'name' is " +
                "mandatory (non-OPTIONAL 'label') and omitting it returns a structured validation_failed " +
                "error (KSTEP-M-002); 'id' and 'description' are genuinely OPTIONAL and may be omitted. " +
                "Referenced later by build_person_and_organization's the_organization_handle. Re-building " +
                "with a handle already in the store OVERWRITES the previous entry.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildOrganizationArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("handle", args.handle)
            args.id?.let { requireBoundedString("id", it) }
            args.name?.let { requireBoundedString("name", it) }
            args.description?.let { requireBoundedString("description", it) }

            when (
                val result =
                    organization {
                        id = args.id
                        name = args.name
                        description = args.description
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.handle, EntityStoreEntry.OrganizationEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text = "Built organization '${args.handle}': name='${entity.name}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("id", entity.id)
                                    put("name", entity.name)
                                    put("description", entity.description)
                                    put("entityType", "organization")
                                },
                        )
                    }
                }
            }
        }
    }
}
