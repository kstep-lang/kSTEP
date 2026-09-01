package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition
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
// reference_designator is genuinely OPTIONAL as of kSTEP M2 Welle 10 (previously
// kstep-core over-constrained it non-blank via a now-removed synthesized WHERE rule — see
// NextAssemblyUsageOccurrence.kt). Two NAUOs that both leave reference_designator unset (null)
// do NOT conflict under UR1 even if relating_product_definition matches: EXPRESS's `<>`/`=`
// comparison is never satisfied by two unknown ("not currently determined") values — an unset
// attribute is not equal to another unset attribute, so the UNIQUE rule's own equality
// precondition never holds between them. The scan below is therefore skipped entirely when
// either side's reference_designator is null, not just when the *new* one is.
//
// UNIQUE UR2 ("product_definition_occurrence_id,
// SELF\product_definition_relationship.relating_product_definition") is deliberately NOT
// enforced: product_definition_occurrence_id is itself a DERIVE value chained through
// product_definition_occurrence, an entity nowhere modeled among kstep-core's twelve AP242
// types — see README Status. Faking or approximating it would be worse than the documented gap.
private const val UNIQUE_RULE_UR1_LABEL = "UR1"

private fun findUniqueConstraintConflict(
    entries: Map<String, EntityStoreEntry>,
    excludingId: String,
    referenceDesignator: String?,
    relatingProductDefinition: ProductDefinition,
): String? {
    if (referenceDesignator == null) return null
    return entries
        .entries
        .firstOrNull { (id, entry) ->
            id != excludingId &&
                entry is EntityStoreEntry.NextAssemblyUsageOccurrenceEntry &&
                entry.value.referenceDesignator == referenceDesignator &&
                entry.value.relatingProductDefinition === relatingProductDefinition
        }?.key
}

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("id") { put("type", "string") }
                putJsonObject("name") { put("type", "string") }
                putJsonObject("description") { put("type", "string") }
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
                "is returned WITHOUT attempting the build, so a structural violation on the same call (e.g. " +
                "an omitted 'name') is not also reported in that response — fix the reference(s) first, " +
                "then call again to see any remaining validation_failed violations. " +
                "'name' is mandatory in the real AP242 schema (non-OPTIONAL 'label') and omitting it " +
                "returns a structured validation_failed error (KSTEP-M-002) rather than silently defaulting " +
                "to an empty name; 'description' and 'reference_designator' are both genuinely OPTIONAL and " +
                "may be omitted. Also enforces the real AP242 UNIQUE UR1 rule: (reference_designator, " +
                "relating_product_definition_id) must be unique across every next_assembly_usage_occurrence " +
                "already in the store WHEN reference_designator is actually set on both sides — two NAUOs " +
                "that both leave reference_designator unset never conflict under UR1, matching EXPRESS's " +
                "own 'unknown values are never equal' semantics. A conflicting pair returns a structured " +
                "unique_constraint_violated error naming the conflicting id, instead of silently allowing " +
                "the duplicate. Stores the result under its own id, overwriting any previous entry with the " +
                "same id.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args =
                Json.decodeFromJsonElement<BuildNextAssemblyUsageOccurrenceArgs>(
                    request.arguments ?: JsonObject(emptyMap()),
                )
            requireBoundedString("id", args.id)
            args.name?.let { requireBoundedString("name", it) }
            args.description?.let { requireBoundedString("description", it) }
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
                        description = args.description
                        relatingProductDefinition = relating
                        relatedProductDefinition = related
                        referenceDesignator = args.referenceDesignator
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
                                        // Never null here: findUniqueConstraintConflict returns null
                                        // immediately (no scan, no conflict possible) whenever
                                        // entity.referenceDesignator is null — see its KDoc.
                                        UniqueConstraintFieldValue(
                                            "reference_designator",
                                            entity.referenceDesignator!!,
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
                                    put("description", entity.description)
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
