package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.descriptionAttribute
import dev.kstep.generated.ap242v1.DescriptionAttributeSelect
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildDescriptionAttributeArgs
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

private const val TOOL_NAME = "build_description_attribute"

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("handle") { put("type", "string") }
                putJsonObject("attribute_value") { put("type", "string") }
                putJsonObject("described_item_id") { put("type", "string") }
            },
        required = listOf("handle", "attribute_value", "described_item_id"),
    )

fun registerBuildDescriptionAttributeTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Builds a kSTEP AP242 'description_attribute' entity: attaches an attribute_value to the item named by " +
                "described_item_id (application_context | person_and_organization; an unknown or wrong-type id " +
                "returns a structured unknown_reference error, not a crash). attribute_value is mandatory " +
                "(omitting it returns validation_failed, KSTEP-M-002). " +
                "The value is what the derived 'description' of the item reads back (EXPRESS " +
                "get_description_value): with exactly one such attribute the derived value is this " +
                "attribute_value, with none or several it is undetermined, and the item's WHERE rule " +
                "'at most one' is violated by several (reported by validate_model). Stores the result " +
                "under the caller-supplied 'handle' (this entity has no natural id of its own).",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildDescriptionAttributeArgs>(
                    request.arguments ?: JsonObject(emptyMap()),
                )
            requireBoundedString("handle", args.handle)
            args.attributeValue?.let { requireBoundedString("attribute_value", it) }
            requireBoundedString("described_item_id", args.describedItemId)

            val item =
                store.get(args.describedItemId)?.rawValue as? DescriptionAttributeSelect
                    ?: return@mcpToolCall unknownReferenceError(
                        listOf(
                            UnknownReference(
                                "described_item_id",
                                args.describedItemId,
                                "application_context | person_and_organization",
                            ),
                        ),
                    )

            when (
                val result =
                    descriptionAttribute {
                        attributeValue = args.attributeValue
                        describedItem = item
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value
                    storeOrCapacityError(store, args.handle, EntityStoreEntry.DescriptionAttributeEntry(entity)) {
                        CallToolResult(
                            content =
                                listOf(
                                    TextContent(
                                        text =
                                            "Built description_attribute '${args.handle}' on '${args.describedItemId}'",
                                    ),
                                ),
                            structuredContent =
                                buildJsonObject {
                                    put("handle", args.handle)
                                    put("attribute_value", entity.attributeValue)
                                    put("described_item_id", args.describedItemId)
                                    put("entityType", "description_attribute")
                                },
                        )
                    }
                }
            }
        }
    }
}
