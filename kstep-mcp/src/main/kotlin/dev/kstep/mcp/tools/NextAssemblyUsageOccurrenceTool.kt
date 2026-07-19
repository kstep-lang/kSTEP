package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildNextAssemblyUsageOccurrenceArgs
import dev.kstep.mcp.findProductDefinition
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

private const val TOOL_NAME = "build_next_assembly_usage_occurrence"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
                putJsonObject("relating_product_definition_id") { put("type", "string") }
                putJsonObject("related_product_definition_id") { put("type", "string") }
                putJsonObject("reference_designator") { put("type", "string") }
            },
        required = listOf("id", "relating_product_definition_id", "related_product_definition_id"),
    )

fun registerBuildNextAssemblyUsageOccurrenceTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'next_assembly_usage_occurrence' entity — an assembly relationship " +
                "between two already-built product_definitions. Both relating_product_definition_id and " +
                "related_product_definition_id are resolved before the entity is built; if either (or both) " +
                "is unknown or wrong-type, a structured unknown_reference error listing every bad reference " +
                "is returned WITHOUT attempting the build, so a WHERE-rule violation (e.g. an empty " +
                "reference_designator) on the same call is not also reported in that response — fix the " +
                "reference(s) first, then call again to see any remaining validation_failed violations. " +
                "Stores the result under its own id, overwriting any previous entry with the same id.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildNextAssemblyUsageOccurrenceArgs>(
                    request.arguments ?: JsonObject(emptyMap()),
                )
            requireBoundedString("id", args.id)
            args.name?.let { requireBoundedString("name", it) }
            requireBoundedString("relating_product_definition_id", args.relatingProductDefinitionId)
            requireBoundedString("related_product_definition_id", args.relatedProductDefinitionId)
            args.referenceDesignator?.let { requireBoundedString("reference_designator", it) }

            val relating = store.findProductDefinition(args.relatingProductDefinitionId)
            val related = store.findProductDefinition(args.relatedProductDefinitionId)
            val unknownRefs =
                buildList {
                    if (relating == null) {
                        add(
                            UnknownReference(
                                "relating_product_definition_id",
                                args.relatingProductDefinitionId,
                                "product_definition",
                            ),
                        )
                    }
                    if (related == null) {
                        add(
                            UnknownReference(
                                "related_product_definition_id",
                                args.relatedProductDefinitionId,
                                "product_definition",
                            ),
                        )
                    }
                }
            if (unknownRefs.isNotEmpty()) return@mcpToolCall unknownReferenceError(unknownRefs)

            when (
                val result =
                    nextAssemblyUsageOccurrence(args.id) {
                        name = args.name ?: ""
                        relatingProductDefinition = relating
                        relatedProductDefinition = related
                        referenceDesignator = args.referenceDesignator ?: ""
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(
                        store,
                        args.id,
                        EntityStoreEntry.NextAssemblyUsageOccurrenceEntry(entity),
                    ) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built next_assembly_usage_occurrence '${entity.id}': " +
                                                "'${args.relatingProductDefinitionId}' -> " +
                                                "'${args.relatedProductDefinitionId}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("id", entity.id)
                                    put("name", entity.name)
                                    put("relating_product_definition_id", args.relatingProductDefinitionId)
                                    put("related_product_definition_id", args.relatedProductDefinitionId)
                                    put("reference_designator", entity.referenceDesignator)
                                    put("entityType", "next_assembly_usage_occurrence")
                                },
                        )
                    }
                }
            }
        }
    }
}
