package dev.kstep.mcp

import dev.kstep.core.ap242.Ap242Population
import dev.kstep.express.validation.DerivedValueScope
import dev.kstep.express.validation.IndeterminateDerivedValueException
import dev.kstep.express.validation.InstanceGraphException
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionOccurrence
import dev.kstep.generated.ap242v1.ProductDefinitionOrReference
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Population-dependent derived values (ADR-0023) for the entry [root], read over the whole store: each value is
 * the string (`null` = EXPRESS `?`: zero or several matching `*_attribute` entities), or an `indeterminate`
 * object when the population is incomplete or too large to evaluate. The population is built once from a
 * consistent snapshot (never the unsynchronised view) and shared by all [fields].
 */
private fun derivedFields(
    store: EntityStore,
    root: Any,
    vararg fields: Pair<String, (DerivedValueScope) -> String?>,
): List<Pair<String, JsonElement>> {
    val scope: DerivedValueScope? =
        try {
            Ap242Population.of(store.consistentSnapshot().values.map { it.rawValue } + root).derivedScope
        } catch (_: InstanceGraphException) {
            null
        }

    fun indeterminate(reason: String): JsonElement =
        buildJsonObject {
            put("indeterminate", true)
            put("reason", reason)
        }
    return fields.map { (name, compute) ->
        name to
            if (scope == null) {
                indeterminate("the population is too large to evaluate")
            } else {
                try {
                    compute(scope)?.let { JsonPrimitive(it) } ?: JsonNull
                } catch (e: IndeterminateDerivedValueException) {
                    indeterminate(e.reason)
                }
            }
    }
}

// The id of the product definition (or occurrence) at one end of a usage, read from the object itself
// (not from the store key), so it stays correct when the store entry was overwritten since.
private fun idOfEnd(end: ProductDefinitionOrReference): String =
    when (end) {
        is ProductDefinition -> end.id
        is ProductDefinitionOccurrence -> end.id
    }

private fun stringArrayOrNull(items: List<String>?) =
    items?.let { names -> buildJsonArray { names.forEach { add(it) } } } ?: JsonNull

/**
 * Renders [entry] as a flat JSON field dump, entity-typed reference fields included as the
 * *referenced* entity's own store id/handle (not a nested object) — symmetric with every
 * `build_*` tool's own echo, and avoids inventing a nested-JSON entity representation for this
 * wave. Six of the sixteen entity types (`application_context`/`product_context`/
 * `product_definition_context`/`approval_status`/`organization`, plus `person_and_organization`
 * itself) have no natural mandatory `id` attribute of their own, so their references are
 * recovered via [EntityStore.keyOf] (object-identity search) instead.
 */
fun describeEntry(
    store: EntityStore,
    entry: EntityStoreEntry,
): JsonObject =
    when (entry) {
        is EntityStoreEntry.ApplicationContextEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("application", entry.value.application)
                derivedFields(
                    store,
                    entry.value,
                    "derived_description" to { entry.value.description(it) },
                    "derived_id" to { entry.value.id(it) },
                ).forEach { (key, value) -> put(key, value) }
            }
        is EntityStoreEntry.ProductContextEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("name", entry.value.name)
                put("frame_of_reference_handle", store.keyOf(entry.value.frameOfReference))
                put("discipline_type", entry.value.disciplineType)
            }
        is EntityStoreEntry.ProductDefinitionContextEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("name", entry.value.name)
                put("frame_of_reference_handle", store.keyOf(entry.value.frameOfReference))
                put("life_cycle_stage", entry.value.lifeCycleStage)
            }
        is EntityStoreEntry.ApprovalStatusEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("name", entry.value.name)
            }
        is EntityStoreEntry.PersonEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("last_name", entry.value.lastName)
                put("first_name", entry.value.firstName)
                put("middle_names", stringArrayOrNull(entry.value.middleNames))
                put("prefix_titles", stringArrayOrNull(entry.value.prefixTitles))
                put("suffix_titles", stringArrayOrNull(entry.value.suffixTitles))
            }
        is EntityStoreEntry.OrganizationEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("name", entry.value.name)
                put("description", entry.value.description)
            }
        is EntityStoreEntry.ProductEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("name", entry.value.name)
                put("description", entry.value.description)
                put(
                    "frame_of_reference_handles",
                    buildJsonArray { entry.value.frameOfReference.forEach { add(store.keyOf(it)) } },
                )
            }
        is EntityStoreEntry.PersonAndOrganizationEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("the_person_id", entry.value.thePerson.id)
                put("the_organization_handle", store.keyOf(entry.value.theOrganization))
                derivedFields(
                    store,
                    entry.value,
                    "derived_name" to { entry.value.name(it) },
                    "derived_description" to { entry.value.description(it) },
                ).forEach { (key, value) -> put(key, value) }
            }
        is EntityStoreEntry.ProductDefinitionFormationEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("description", entry.value.description)
                put("of_product_id", entry.value.ofProduct.id)
            }
        is EntityStoreEntry.ProductDefinitionEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("description", entry.value.description)
                put("formation_id", entry.value.formation.id)
                put("frame_of_reference_handle", store.keyOf(entry.value.frameOfReference))
                derivedFields(store, entry.value, "derived_name" to { entry.value.name(it) })
                    .forEach { (key, value) -> put(key, value) }
            }
        is EntityStoreEntry.NextAssemblyUsageOccurrenceEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("name", entry.value.name)
                put("description", entry.value.description)
                put("relating_product_definition_id", idOfEnd(entry.value.relatingProductDefinition))
                put("related_product_definition_id", idOfEnd(entry.value.relatedProductDefinition))
                put(
                    "related_entity_type",
                    if (entry.value.relatedProductDefinition is ProductDefinitionOccurrence) {
                        "product_definition_occurrence"
                    } else {
                        "product_definition"
                    },
                )
                put("product_definition_occurrence_id", entry.value.productDefinitionOccurrenceId)
                put("reference_designator", entry.value.referenceDesignator)
            }
        is EntityStoreEntry.ApprovalEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("status_handle", store.keyOf(entry.value.status))
                put("level", entry.value.level)
            }
        is EntityStoreEntry.ProductDefinitionOccurrenceEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("name", entry.value.name)
                put("description", entry.value.description)
                put("definition_id", entry.value.definition?.let { store.keyOf(it) })
            }
        is EntityStoreEntry.NameAttributeEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("attribute_value", entry.value.attributeValue)
                put("named_item_id", store.keyOf(entry.value.namedItem))
            }
        is EntityStoreEntry.DescriptionAttributeEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("attribute_value", entry.value.attributeValue)
                put("described_item_id", store.keyOf(entry.value.describedItem))
            }
        is EntityStoreEntry.IdAttributeEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("attribute_value", entry.value.attributeValue)
                put("identified_item_id", store.keyOf(entry.value.identifiedItem))
            }
    }
