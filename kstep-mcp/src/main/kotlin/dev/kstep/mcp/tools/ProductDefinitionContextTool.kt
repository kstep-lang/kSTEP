package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildProductDefinitionContextArgs
import dev.kstep.mcp.findApplicationContext
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

private const val TOOL_NAME = "build_product_definition_context"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("handle") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
                putJsonObject("frame_of_reference_handle") { put("type", "string") }
                putJsonObject("life_cycle_stage") { put("type", "string") }
            },
        required = listOf("handle", "name", "frame_of_reference_handle", "life_cycle_stage"),
    )

fun registerBuildProductDefinitionContextTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'product_definition_context' entity, referencing an already-built " +
                "application_context by frame_of_reference_handle (must be a handle previously stored by " +
                "build_application_context; an unknown or wrong-type handle returns a structured " +
                "unknown_reference error, not a crash). Stores the result under the caller-supplied 'handle' " +
                "(this entity has no natural id of its own). Referenced later by " +
                "build_product_definition's frame_of_reference_handle. Re-building with a handle already in " +
                "the store OVERWRITES the previous entry.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildProductDefinitionContextArgs>(
                    request.arguments ?: JsonObject(emptyMap()),
                )
            requireBoundedString("handle", args.handle)
            args.name?.let { requireBoundedString("name", it) }
            requireBoundedString("frame_of_reference_handle", args.frameOfReferenceHandle)
            args.lifeCycleStage?.let { requireBoundedString("life_cycle_stage", it) }

            val frameOfReference =
                store.findApplicationContext(args.frameOfReferenceHandle)
                    ?: return@mcpToolCall unknownReferenceError(
                        listOf(
                            UnknownReference(
                                "frame_of_reference_handle",
                                args.frameOfReferenceHandle,
                                "application_context",
                            ),
                        ),
                    )

            when (
                val result =
                    productDefinitionContext {
                        name = args.name
                        this.frameOfReference = frameOfReference
                        lifeCycleStage = args.lifeCycleStage
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.handle, EntityStoreEntry.ProductDefinitionContextEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built product_definition_context '${args.handle}': " +
                                                "name='${entity.name}', " +
                                                "life_cycle_stage='${entity.lifeCycleStage}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("name", entity.name)
                                    put("frame_of_reference_handle", args.frameOfReferenceHandle)
                                    put("life_cycle_stage", entity.lifeCycleStage)
                                    put("entityType", "product_definition_context")
                                },
                        )
                    }
                }
            }
        }
    }
}
