package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildProductDefinitionFormationArgs
import dev.kstep.mcp.findProduct
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

private const val TOOL_NAME = "build_product_definition_formation"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
                putJsonObject("of_product_id") { put("type", "string") }
            },
        required = listOf("id", "of_product_id"),
    )

fun registerBuildProductDefinitionFormationTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'product_definition_formation' entity, referencing an already-built " +
                "product by of_product_id (must be an id previously stored by build_product; an unknown or " +
                "wrong-type id returns a structured unknown_reference error, not a crash). This entity has " +
                "no WHERE rule, so it can never return a validation_failed error — only unknown_reference or " +
                "malformed_input. Stores the result under its own id, overwriting any previous entry with " +
                "the same id.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildProductDefinitionFormationArgs>(
                    request.arguments ?: JsonObject(emptyMap()),
                )
            requireBoundedString("id", args.id)
            args.description?.let { requireBoundedString("description", it) }
            requireBoundedString("of_product_id", args.ofProductId)

            val ofProduct =
                store.findProduct(args.ofProductId)
                    ?: return@mcpToolCall unknownReferenceError(
                        listOf(UnknownReference("of_product_id", args.ofProductId, "product")),
                    )

            when (
                val result =
                    productDefinitionFormation(args.id) {
                        description = args.description ?: ""
                        this.ofProduct = ofProduct
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.id, EntityStoreEntry.ProductDefinitionFormationEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built product_definition_formation '${entity.id}' " +
                                                "of product '${args.ofProductId}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("id", entity.id)
                                    put("description", entity.description)
                                    put("of_product_id", args.ofProductId)
                                    put("entityType", "product_definition_formation")
                                },
                        )
                    }
                }
            }
        }
    }
}
