package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.product
import dev.kstep.generated.ap242v1.ProductContext
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildProductArgs
import dev.kstep.mcp.findProductContext
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.requireBoundedList
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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val TOOL_NAME = "build_product"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
                putJsonObject("frame_of_reference_handles") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            },
        required = listOf("id", "name", "frame_of_reference_handles"),
    )

fun registerBuildProductTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'product' entity (id, name, description, frame_of_reference_handles) " +
                "and stores it in this session under its own id, for later reference by " +
                "build_product_definition_formation's of_product_id. 'name' is mandatory in the real AP242 " +
                "schema (non-OPTIONAL 'label') and omitting it returns a structured validation_failed error " +
                "(KSTEP-M-002); 'description' is genuinely OPTIONAL and may be omitted. " +
                "'frame_of_reference_handles' must name at least one already-built product_context (handles " +
                "previously stored by build_product_context) — the real AP242 schema requires a NON-EMPTY " +
                "SET here: an unknown/wrong-type handle in the list returns a structured unknown_reference " +
                "error listing every bad handle, and an empty list returns a structured validation_failed " +
                "error (KSTEP-A-001), distinct from omitting the field entirely (KSTEP-M-001). Capped at 64 " +
                "handles per call. Re-building with an id already in the store OVERWRITES the previous entry " +
                "— entities that already resolved a reference to the old value keep pointing at it, since " +
                "kstep-core entities are immutable.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args = Json.decodeFromJsonElement<BuildProductArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("id", args.id)
            args.name?.let { requireBoundedString("name", it) }
            args.description?.let { requireBoundedString("description", it) }
            args.frameOfReferenceHandles?.let { handles ->
                requireBoundedList("frame_of_reference_handles", handles)
                handles.forEach { requireBoundedString("frame_of_reference_handles", it) }
            }

            val resolved = mutableListOf<ProductContext>()
            val unknownRefs =
                buildList {
                    for (handle in args.frameOfReferenceHandles.orEmpty()) {
                        val context = store.findProductContext(handle)
                        if (context == null) {
                            add(UnknownReference("frame_of_reference_handles", handle, "product_context"))
                        } else {
                            resolved += context
                        }
                    }
                }
            if (unknownRefs.isNotEmpty()) return@mcpToolCall unknownReferenceError(unknownRefs)

            when (
                val result =
                    product(args.id) {
                        name = args.name
                        description = args.description
                        // `null` (field omitted entirely) stays `null` here so `product()` raises
                        // KSTEP-M-001; only an explicitly-present (possibly empty) list becomes a
                        // Set, so an explicitly-empty list raises the distinct KSTEP-A-001 — see
                        // this tool's description and `ProductBuilder.frameOfReference`'s KDoc.
                        frameOfReference = args.frameOfReferenceHandles?.let { resolved.toSet() }
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.id, EntityStoreEntry.ProductEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built product '${entity.id}': name='${entity.name}', " +
                                                "description='${entity.description}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("id", entity.id)
                                    put("name", entity.name)
                                    put("description", entity.description)
                                    put(
                                        "frame_of_reference_handles",
                                        buildJsonArray { args.frameOfReferenceHandles.orEmpty().forEach { add(it) } },
                                    )
                                    put("entityType", "product")
                                },
                        )
                    }
                }
            }
        }
    }
}
