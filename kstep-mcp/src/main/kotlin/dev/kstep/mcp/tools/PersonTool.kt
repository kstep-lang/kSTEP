package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.person
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.dto.BuildPersonArgs
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.requireBoundedList
import dev.kstep.mcp.requireBoundedString
import dev.kstep.mcp.storeOrCapacityError
import dev.kstep.mcp.validationFailedError
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TOOL_NAME = "build_person"

private fun JsonObjectBuilder.stringArraySchema(key: String) =
    putJsonObject(key) {
        put("type", "array")
        putJsonObject("items") { put("type", "string") }
    }

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("last_name") { put("type", "string") }
                putJsonObject("first_name") { put("type", "string") }
                stringArraySchema("middle_names")
                stringArraySchema("prefix_titles")
                stringArraySchema("suffix_titles")
            },
        required = listOf("id"),
    )

fun registerBuildPersonTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'person' entity and stores it in this session under its own id, for " +
                "later reference by build_person_and_organization's the_person_id. Every attribute besides " +
                "'id' is genuinely OPTIONAL in the real AP242 schema — EXCEPT the real WR1 WHERE rule, which " +
                "requires at least one of last_name/first_name to actually be present: omit both (or pass " +
                "them as null) and the call returns a structured validation_failed error (KSTEP-W-001); " +
                "passing an explicit empty string for either one still counts as 'present' and satisfies the " +
                "rule. middle_names/prefix_titles/suffix_titles are each capped at 64 items; each is " +
                "genuinely OPTIONAL (omitting one is fine), but if you DO pass one, it must be non-empty — " +
                "an explicit [] for any of them returns a structured validation_failed error (KSTEP-A-001), " +
                "distinct from omitting the field entirely. Re-building with an id already in the store " +
                "OVERWRITES the previous entry.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args = Json.decodeFromJsonElement<BuildPersonArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("id", args.id)
            args.lastName?.let { requireBoundedString("last_name", it) }
            args.firstName?.let { requireBoundedString("first_name", it) }
            args.middleNames?.let { list ->
                requireBoundedList("middle_names", list)
                list.forEach { requireBoundedString("middle_names", it) }
            }
            args.prefixTitles?.let { list ->
                requireBoundedList("prefix_titles", list)
                list.forEach { requireBoundedString("prefix_titles", it) }
            }
            args.suffixTitles?.let { list ->
                requireBoundedList("suffix_titles", list)
                list.forEach { requireBoundedString("suffix_titles", it) }
            }

            when (
                val result =
                    person(args.id) {
                        lastName = args.lastName
                        firstName = args.firstName
                        middleNames = args.middleNames
                        prefixTitles = args.prefixTitles
                        suffixTitles = args.suffixTitles
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.id, EntityStoreEntry.PersonEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built person '${entity.id}': last_name='${entity.lastName}', " +
                                                "first_name='${entity.firstName}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("id", entity.id)
                                    put("last_name", entity.lastName)
                                    put("first_name", entity.firstName)
                                    put("entityType", "person")
                                },
                        )
                    }
                }
            }
        }
    }
}
