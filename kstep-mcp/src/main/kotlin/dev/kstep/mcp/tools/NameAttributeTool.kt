package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.nameAttribute
import dev.kstep.generated.ap242v1.NameAttributeSelect
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildNameAttributeArgs
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

private const val TOOL_NAME = "build_name_attribute"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("handle") { put("type", "string") }
                putJsonObject("attribute_value") { put("type", "string") }
                putJsonObject("named_item_id") { put("type", "string") }
            },
        required = listOf("handle", "attribute_value", "named_item_id"),
    )

fun registerBuildNameAttributeTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'name_attribute' entity: attaches an attribute_value to the item named by " +
                "named_item_id (product_definition | person_and_organization; an unknown or wrong-type id " +
                "returns a structured unknown_reference error, not a crash). attribute_value is mandatory " +
                "(omitting it returns validation_failed, KSTEP-M-002). " +
                "The value is what the derived 'name' of the item reads back (EXPRESS " +
                "get_name_value): with exactly one such attribute the derived value is this " +
                "attribute_value, with none or several it is undetermined, and the item's WHERE rule " +
                "'at most one' is violated by several (reported by validate_model). Stores the result " +
                "under the caller-supplied 'handle' (this entity has no natural id of its own).",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args = Json.decodeFromJsonElement<BuildNameAttributeArgs>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedString("handle", args.handle)
            args.attributeValue?.let { requireBoundedString("attribute_value", it) }
            requireBoundedString("named_item_id", args.namedItemId)

            val item =
                store.get(args.namedItemId)?.rawValue as? NameAttributeSelect
                    ?: return@mcpToolCall unknownReferenceError(
                        listOf(
                            UnknownReference(
                                "named_item_id",
                                args.namedItemId,
                                "product_definition | person_and_organization",
                            ),
                        ),
                    )

            when (
                val result =
                    nameAttribute {
                        attributeValue = args.attributeValue
                        namedItem = item
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.handle, EntityStoreEntry.NameAttributeEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built name_attribute '${args.handle}' on '${args.namedItemId}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("attribute_value", entity.attributeValue)
                                    put("named_item_id", args.namedItemId)
                                    put("entityType", "name_attribute")
                                },
                        )
                    }
                }
            }
        }
    }
}
