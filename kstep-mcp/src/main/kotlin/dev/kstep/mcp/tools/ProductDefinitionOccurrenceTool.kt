package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.productDefinitionOccurrence
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildProductDefinitionOccurrenceArgs
import dev.kstep.mcp.findProductDefinitionOrReference
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

private const val TOOL_NAME = "build_product_definition_occurrence"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
                putJsonObject("definition_id") { put("type", "string") }
            },
        required = listOf("id"),
    )

fun registerBuildProductDefinitionOccurrenceTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'product_definition_occurrence' entity — an occurrence of a product " +
                "definition that can stand as the related end of a next_assembly_usage_occurrence, which is " +
                "what gives that usage a product_definition_occurrence_id (and makes its UNIQUE rule UR2 " +
                "apply). Only 'id' is mandatory; 'name' and 'description' are OPTIONAL, and 'definition_id' " +
                "optionally names the product_definition (or product_definition_occurrence) this is an " +
                "occurrence of; an unknown or wrong-type definition_id returns a structured unknown_reference " +
                "error, not a crash. The schema rule WR2 (EXISTS(definition) OR a configuration_design " +
                "usage) depends on the whole model and is checked by validate_model, not here. The real " +
                "entity's quantity attribute is not modelled and is exported as '$'. Stores the result under " +
                "its own id, overwriting any previous entry with the same id.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildProductDefinitionOccurrenceArgs>(
                    request.arguments ?: JsonObject(emptyMap()),
                )
            requireBoundedString("id", args.id)
            args.name?.let { requireBoundedString("name", it) }
            args.description?.let { requireBoundedString("description", it) }
            args.definitionId?.let { requireBoundedString("definition_id", it) }

            val definition = args.definitionId?.let { store.findProductDefinitionOrReference(it) }
            if (args.definitionId != null && definition == null) {
                return@mcpToolCall unknownReferenceError(
                    listOf(
                        UnknownReference(
                            "definition_id",
                            args.definitionId,
                            "product_definition | product_definition_occurrence",
                        ),
                    ),
                )
            }

            when (
                val result =
                    productDefinitionOccurrence(args.id) {
                        name = args.name
                        description = args.description
                        this.definition = definition
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.id, EntityStoreEntry.ProductDefinitionOccurrenceEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(TextContent(text = "Built product_definition_occurrence '${entity.id}'")),
                            structuredContent =
                                buildJsonObject {
                                    put("id", entity.id)
                                    put("name", entity.name)
                                    put("description", entity.description)
                                    put("definition_id", args.definitionId)
                                    put("entityType", "product_definition_occurrence")
                                },
                        )
                    }
                }
            }
        }
    }
}
