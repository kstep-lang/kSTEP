package dev.kstep.mcp.tools

import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.express.validation.AcyclicEvaluationLimitExceededException
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinitionOccurrence
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.RuleViolation
import dev.kstep.mcp.UniqueConstraintFieldValue
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.BuildNextAssemblyUsageOccurrenceArgs
import dev.kstep.mcp.findProductDefinitionOrReference
import dev.kstep.mcp.limitExceededError
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.requireBoundedString
import dev.kstep.mcp.storeIfNoConflictOrError
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
// reference_designator is genuinely OPTIONAL as of kSTEP M2 Welle 10. Two NAUOs that both leave
// reference_designator unset (null) do NOT conflict under UR1 even if relating_product_definition
// matches: EXPRESS's `<>`/`=` comparison is never satisfied by two unknown ("not currently
// determined") values — an unset attribute is not equal to another unset attribute, so the UNIQUE
// rule's own equality precondition never holds between them.
//
// UNIQUE UR2 ("product_definition_occurrence_id, SELF\product_definition_relationship.relating_product_definition",
// ADR-0023): product_definition_occurrence_id is a DERIVE that is the id of the related
// product_definition_occurrence, and `?` when the related end is a plain product_definition. A usage whose
// derived id is `?` never takes part (same unknown-is-never-equal semantics as UR1); otherwise two usages
// conflict when their occurrence ids are equal (string equality, the occurrences may be distinct instances)
// and their relating_product_definition is the same instance.
//
// WR1 (inherited from product_definition_usage): acyclic_product_definition_relationship — the usage must not
// close a cycle in the assembly structure; see AssemblyCycleCheck.kt. Evaluated in the same lock as UR1/UR2.
private const val UNIQUE_RULE_UR1_LABEL = "UR1"
private const val UNIQUE_RULE_UR2_LABEL = "UR2"
private const val ACYCLIC_RULE_LABEL = "WR1"

private fun otherUsages(
    entries: Map<String, EntityStoreEntry>,
    excludingId: String,
): List<NextAssemblyUsageOccurrence> =
    entries.entries
        .sortedBy { it.key }
        .filter { (id, entry) -> id != excludingId && entry is EntityStoreEntry.NextAssemblyUsageOccurrenceEntry }
        .map { (it.value as EntityStoreEntry.NextAssemblyUsageOccurrenceEntry).value }

private fun findUr1Conflict(
    entries: Map<String, EntityStoreEntry>,
    excludingId: String,
    entity: NextAssemblyUsageOccurrence,
    relatingId: String,
): RuleViolation? {
    val referenceDesignator = entity.referenceDesignator ?: return null
    val other =
        otherUsages(entries, excludingId).firstOrNull {
            it.referenceDesignator == referenceDesignator &&
                it.relatingProductDefinition === entity.relatingProductDefinition
        } ?: return null
    return RuleViolation.Unique(
        UNIQUE_RULE_UR1_LABEL,
        other.id,
        listOf(
            UniqueConstraintFieldValue("reference_designator", referenceDesignator),
            UniqueConstraintFieldValue("relating_product_definition_id", relatingId),
        ),
    )
}

private fun findUr2Conflict(
    entries: Map<String, EntityStoreEntry>,
    excludingId: String,
    entity: NextAssemblyUsageOccurrence,
    relatingId: String,
): RuleViolation? {
    val occurrenceId = entity.productDefinitionOccurrenceId ?: return null
    val other =
        otherUsages(entries, excludingId).firstOrNull {
            it.productDefinitionOccurrenceId == occurrenceId &&
                it.relatingProductDefinition === entity.relatingProductDefinition
        } ?: return null
    return RuleViolation.Unique(
        UNIQUE_RULE_UR2_LABEL,
        other.id,
        listOf(
            UniqueConstraintFieldValue("product_definition_occurrence_id", occurrenceId),
            UniqueConstraintFieldValue("relating_product_definition_id", relatingId),
        ),
    )
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
                "between two already-built product definitions. relating_product_definition_id and " +
                "related_product_definition_id each name a product_definition OR a " +
                "product_definition_occurrence (the SELECT product_definition_or_reference); both are " +
                "resolved before the entity is built, and if either (or both) is unknown or wrong-type, a " +
                "structured unknown_reference error listing every bad reference is returned WITHOUT " +
                "attempting the build, so a structural violation on the same call (e.g. an omitted 'name') " +
                "is not also reported in that response — fix the reference(s) first, then call again to see " +
                "any remaining validation_failed violations. 'name' is mandatory in the real AP242 schema " +
                "(non-OPTIONAL 'label') and omitting it returns a structured validation_failed error " +
                "(KSTEP-M-002) rather than silently defaulting to an empty name; 'description' and " +
                "'reference_designator' are both genuinely OPTIONAL and may be omitted. " +
                "Also enforces three rules of the real schema, all against the usages already in the store, " +
                "all evaluated atomically with the write, all reported together (not just the first): " +
                "UR1 — (reference_designator, relating_product_definition_id) must be unique WHEN " +
                "reference_designator is set on both sides (unset values are never equal under EXPRESS " +
                "semantics); UR2 — (product_definition_occurrence_id, relating_product_definition_id) must be " +
                "unique, where product_definition_occurrence_id is the id of the related " +
                "product_definition_occurrence and is undetermined (so UR2 does not apply) when the related " +
                "end is a plain product_definition; WR1 — the usage must not close a cycle in the assembly " +
                "structure (a usage of a definition within itself, directly or transitively). A violation " +
                "returns a structured unique_constraint_violated or acyclic_relationship_violated error " +
                "(with a 'violations' list of every violated rule) instead of storing the entity; a " +
                "structure too large to check within the safety limits returns limit_exceeded and stores " +
                "nothing. Stores the result under its own id, overwriting any previous entry with the " +
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

            val relating = store.findProductDefinitionOrReference(args.relatingProductDefinitionId)
            val related = store.findProductDefinitionOrReference(args.relatedProductDefinitionId)
            val unknownRefs =
                buildList {
                    if (relating == null) {
                        add(
                            UnknownReference(
                                "relating_product_definition_id",
                                args.relatingProductDefinitionId,
                                "product_definition | product_definition_occurrence",
                            ),
                        )
                    }
                    if (related == null) {
                        add(
                            UnknownReference(
                                "related_product_definition_id",
                                args.relatedProductDefinitionId,
                                "product_definition | product_definition_occurrence",
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

                    // The UR1/UR2/WR1 scans and the store write run inside EntityStore.putIfNoConflict's
                    // single lock (see its KDoc), so two concurrent, conflicting calls can no longer both
                    // pass the scans before either writes — id != excludingId excludes the entity's own
                    // current store slot, so a legitimate re-build of the same NAUO id with unchanged fields
                    // does not spuriously conflict with its own prior entry (EntityStore's documented
                    // overwrite semantics). All violated rules are reported, in the fixed order UR1, UR2, WR1.
                    try {
                        storeIfNoConflictOrError(
                            store = store,
                            id = args.id,
                            entry = EntityStoreEntry.NextAssemblyUsageOccurrenceEntry(entity),
                            entityType = "next_assembly_usage_occurrence",
                            findConflict = { entries ->
                                listOfNotNull(
                                    findUr1Conflict(entries, args.id, entity, args.relatingProductDefinitionId),
                                    findUr2Conflict(entries, args.id, entity, args.relatingProductDefinitionId),
                                    findAcyclicViolation(
                                        entries,
                                        args.id,
                                        entity.relatingProductDefinition,
                                        entity.relatedProductDefinition,
                                    )?.let { RuleViolation.Cyclic(ACYCLIC_RULE_LABEL, it) },
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
                                        put(
                                            "related_entity_type",
                                            if (entity.relatedProductDefinition is ProductDefinitionOccurrence) {
                                                "product_definition_occurrence"
                                            } else {
                                                "product_definition"
                                            },
                                        )
                                        put("product_definition_occurrence_id", entity.productDefinitionOccurrenceId)
                                        put("reference_designator", entity.referenceDesignator)
                                        put("entityType", "next_assembly_usage_occurrence")
                                    },
                            )
                        }
                    } catch (e: AcyclicEvaluationLimitExceededException) {
                        limitExceededError(e.message)
                    }
                }
            }
        }
    }
}
