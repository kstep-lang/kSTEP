package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.dto.BuildPersonAndOrganizationArgs
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

private const val TOOL_NAME = "build_person_and_organization"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("handle") { put("type", "string") }
                putJsonObject("the_person") { put("type", "string") }
                putJsonObject("the_organization") { put("type", "string") }
            },
        required = listOf("handle"),
    )

fun registerBuildPersonAndOrganizationTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'person_and_organization' entity (the_person, the_organization — at " +
                "least one must be non-empty) and stores it under the caller-supplied 'handle' (this entity " +
                "has no natural id of its own, unlike product/product_definition/etc). Referenced later by " +
                "build_approval's authorized_by_handle. Re-building with a handle already in the store " +
                "OVERWRITES the previous entry.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildPersonAndOrganizationArgs>(
                    request.arguments ?: JsonObject(emptyMap()),
                )
            requireBoundedString("handle", args.handle)
            args.thePerson?.let { requireBoundedString("the_person", it) }
            args.theOrganization?.let { requireBoundedString("the_organization", it) }

            when (
                val result =
                    personAndOrganization {
                        thePerson = args.thePerson ?: ""
                        theOrganization = args.theOrganization ?: ""
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.handle, EntityStoreEntry.PersonAndOrganizationEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built person_and_organization '${args.handle}': " +
                                                "the_person='${entity.thePerson}', " +
                                                "the_organization='${entity.theOrganization}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("the_person", entity.thePerson)
                                    put("the_organization", entity.theOrganization)
                                    put("entityType", "person_and_organization")
                                },
                        )
                    }
                }
            }
        }
    }
}
