package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.ProductDefinition
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.UniqueConstraintFieldValue
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildNextAssemblyUsageOccurrenceArgs
import dev.kstep.mcp.findProductDefinition
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.requireBoundedString
import dev.kstep.mcp.storeIfNoConflictOrError
import dev.kstep.mcp.uniqueConstraintViolatedError
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

// UNIQUE UR1 in the real AP242 next_assembly_usage_occurrence (ap242-v1-entities.exp):
// "SELF\assembly_component_usage.reference_designator,
//  SELF\product_definition_relationship.relating_product_definition" — i.e.
// (reference_designator, relating_product_definition) must be unique across every NAUO
// instance. relating_product_definition is compared by object identity (===), mirroring
// EntityStore.keyOf's own precedent for "no natural id" entity-typed comparisons: two
// ProductDefinitions with equal field values, built via two separate build_product_definition
// calls, are two distinct EXPRESS instances, not the same one.
//
// UNIQUE UR2 ("product_definition_occurrence_id,
// SELF\product_definition_relationship.relating_product_definition") is deliberately NOT
// enforced: product_definition_occurrence_id is itself a DERIVE value chained through
// product_definition_occurrence, an entity nowhere modeled among kstep-core's six V1 types —
// see README Status (M2 Welle 4). Faking or approximating it would be worse than the
// documented gap.
private const val UNIQUE_RULE_UR1_LABEL = "UR1"

private fun findUniqueConstraintConflict(
    entries: Map<String, EntityStoreEntry>,
    excludingId: String,
    referenceDesignator: String,
    relatingProductDefinition: ProductDefinition,
): String? =
    entries
        .entries
        .firstOrNull { (id, entry) ->
            id != excludingId &&
                entry is EntityStoreEntry.NextAssemblyUsageOccurrenceEntry &&
                entry.value.referenceDesignator == referenceDesignator &&
                entry.value.relatingProductDefinition === relatingProductDefinition
        }?.key

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
        required = listOf("id", "name", "relating_product_definition_id", "related_product_definition_id"),
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
                "'name' is mandatory in the real AP242 schema (non-OPTIONAL 'label') and omitting it " +
                "returns a structured validation_failed error (KSTEP-M-002) rather than silently defaulting " +
                "to an empty name; 'reference_designator' is genuinely OPTIONAL in the real schema (this " +
                "builder's own WHERE rule still requires it non-empty here, a known, pre-existing " +
                "over-constraint — see README). Also enforces the real AP242 UNIQUE UR1 rule: " +
                "(reference_designator, relating_product_definition_id) must be unique across every " +
                "next_assembly_usage_occurrence already in the store — a conflicting pair returns a " +
                "structured unique_constraint_violated error naming the conflicting id, instead of " +
                "silently allowing the duplicate. Stores the result under its own id, overwriting any " +
                "previous entry with the same id.",
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
                        name = args.name
                        relatingProductDefinition = relating
                        relatedProductDefinition = related
                        referenceDesignator = args.referenceDesignator ?: ""
                    }
            ) {
                is ValidationResult.Invalid -> validationFailedError(result.violations)
                is ValidationResult.Valid -> {
                    val entity = result.value

                    // The UR1 scan and the store write run inside EntityStore.putIfNoConflict's
                    // single lock (see its KDoc), so two concurrent, conflicting calls can no
                    // longer both pass the scan before either writes — id != excludingId
                    // excludes the entity's own current store slot, so a legitimate re-build of
                    // the same NAUO id with unchanged fields does not spuriously conflict with
                    // its own prior entry (EntityStore's documented overwrite semantics).
                    storeIfNoConflictOrError(
                        store = store,
                        id = args.id,
                        entry = EntityStoreEntry.NextAssemblyUsageOccurrenceEntry(entity),
                        findConflict = { entries ->
                            findUniqueConstraintConflict(
                                entries,
                                args.id,
                                entity.referenceDesignator,
                                entity.relatingProductDefinition,
                            )
                        },
                        onConflict = { conflictingId ->
                            uniqueConstraintViolatedError(
                                entityType = "next_assembly_usage_occurrence",
                                ruleLabel = UNIQUE_RULE_UR1_LABEL,
                                conflictingId = conflictingId,
                                fieldValues =
                                    listOf(
                                        UniqueConstraintFieldValue(
                                            "reference_designator",
                                            entity.referenceDesignator,
                                        ),
                                        UniqueConstraintFieldValue(
                                            "relating_product_definition_id",
                                            args.relatingProductDefinitionId,
                                        ),
                                    ),
                            )
                        },
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
