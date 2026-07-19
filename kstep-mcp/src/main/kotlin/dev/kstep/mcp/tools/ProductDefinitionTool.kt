package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.productDefinition
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildProductDefinitionArgs
import dev.kstep.mcp.findProductDefinitionFormation
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

private const val TOOL_NAME = "build_product_definition"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
                putJsonObject("formation_id") { put("type", "string") }
            },
        required = listOf("id", "formation_id"),
    )

fun registerBuildProductDefinitionTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'product_definition' entity, referencing an already-built " +
                "product_definition_formation by formation_id (must be an id previously stored by " +
                "build_product_definition_formation; an unknown or wrong-type id — e.g. a product id used " +
                "here by mistake — returns a structured unknown_reference error, not a crash). Stores the " +
                "result under its own id, overwriting any previous entry with the same id.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildProductDefinitionArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("id", args.id)
            args.description?.let { requireBoundedString("description", it) }
            requireBoundedString("formation_id", args.formationId)

            val formation =
                store.findProductDefinitionFormation(args.formationId)
                    ?: return@mcpToolCall unknownReferenceError(
                        listOf(UnknownReference("formation_id", args.formationId, "product_definition_formation")),
                    )

            when (
                val result =
                    productDefinition(args.id) {
                        description = args.description ?: ""
                        this.formation = formation
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.id, EntityStoreEntry.ProductDefinitionEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built product_definition '${entity.id}' " +
                                                "with formation '${args.formationId}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("id", entity.id)
                                    put("description", entity.description)
                                    put("formation_id", args.formationId)
                                    put("entityType", "product_definition")
                                },
                        )
                    }
                }
            }
        }
    }
}
