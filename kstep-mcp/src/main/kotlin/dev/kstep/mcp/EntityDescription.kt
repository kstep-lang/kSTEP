package dev.kstep.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun stringArrayOrNull(items: List<String>?) =
    items?.let { names -> buildJsonArray { names.forEach { add(it) } } } ?: JsonNull

/**
 * Renders [entry] as a flat JSON field dump, entity-typed reference fields included as the
 * *referenced* entity's own store id/handle (not a nested object) — symmetric with every
 * `build_*` tool's own echo, and avoids inventing a nested-JSON entity representation for this
 * wave. Six of the twelve entity types (`application_context`/`product_context`/
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
            }
        is EntityStoreEntry.NextAssemblyUsageOccurrenceEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("name", entry.value.name)
                put("description", entry.value.description)
                put("relating_product_definition_id", entry.value.relatingProductDefinition.id)
                put("related_product_definition_id", entry.value.relatedProductDefinition.id)
                put("reference_designator", entry.value.referenceDesignator)
            }
        is EntityStoreEntry.ApprovalEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("status_handle", store.keyOf(entry.value.status))
                put("level", entry.value.level)
            }
    }
