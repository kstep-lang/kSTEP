package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildPersonAndOrganizationArgs
import dev.kstep.mcp.findOrganization
import dev.kstep.mcp.findPerson
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

private const val TOOL_NAME = "build_person_and_organization"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("handle") { put("type", "string") }
                putJsonObject("the_person_id") { put("type", "string") }
                putJsonObject("the_organization_handle") { put("type", "string") }
            },
        required = listOf("handle", "the_person_id", "the_organization_handle"),
    )

fun registerBuildPersonAndOrganizationTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'person_and_organization' entity, referencing an already-built person " +
                "by the_person_id (an id previously stored by build_person) and an already-built " +
                "organization by the_organization_handle (a handle previously stored by build_organization) " +
                "— both mandatory in the real AP242 schema. An unknown or wrong-type reference for either " +
                "returns a structured unknown_reference error listing every bad reference, not a crash, and " +
                "no build is attempted while any reference is unresolved. Stores the result under the " +
                "caller-supplied 'handle' (this entity has no natural id of its own). Re-building with a " +
                "handle already in the store OVERWRITES the previous entry.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildPersonAndOrganizationArgs>(
                    request.arguments ?: JsonObject(emptyMap()),
                )
            requireBoundedString("handle", args.handle)
            requireBoundedString("the_person_id", args.thePersonId)
            requireBoundedString("the_organization_handle", args.theOrganizationHandle)

            val thePerson = store.findPerson(args.thePersonId)
            val theOrganization = store.findOrganization(args.theOrganizationHandle)
            val unknownRefs =
                buildList {
                    if (thePerson == null) add(UnknownReference("the_person_id", args.thePersonId, "person"))
                    if (theOrganization == null) {
                        add(UnknownReference("the_organization_handle", args.theOrganizationHandle, "organization"))
                    }
                }
            if (unknownRefs.isNotEmpty()) return@mcpToolCall unknownReferenceError(unknownRefs)

            when (
                val result =
                    personAndOrganization {
                        this.thePerson = thePerson
                        this.theOrganization = theOrganization
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    storeOrCapacityError(
                        store,
                        args.handle,
                        EntityStoreEntry.PersonAndOrganizationEntry(result.value),
                    ) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built person_and_organization '${args.handle}': " +
                                                "the_person_id='${args.thePersonId}', " +
                                                "the_organization_handle='${args.theOrganizationHandle}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("the_person_id", args.thePersonId)
                                    put("the_organization_handle", args.theOrganizationHandle)
                                    put("entityType", "person_and_organization")
                                },
                        )
                    }
                }
            }
        }
    }
}
