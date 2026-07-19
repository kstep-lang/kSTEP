package dev.kstep.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders [entry] as a flat JSON field dump, entity-typed reference fields included as the
 * *referenced* entity's own store id (not a nested object) — symmetric with every `build_*`
 * tool's own echo, and avoids inventing a nested-JSON entity representation for this wave.
 * [PersonAndOrganization][dev.kstep.core.ap242.PersonAndOrganization] has no natural id of its
 * own, so [Approval][dev.kstep.core.ap242.Approval]'s `authorized_by_handle` is recovered via
 * [EntityStore.keyOf] (object-identity search) instead.
 */
fun describeEntry(
    store: EntityStore,
    entry: EntityStoreEntry,
): JsonObject =
    when (entry) {
        is EntityStoreEntry.ProductEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("name", entry.value.name)
                put("description", entry.value.description)
            }
        is EntityStoreEntry.PersonAndOrganizationEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("the_person", entry.value.thePerson)
                put("the_organization", entry.value.theOrganization)
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
            }
        is EntityStoreEntry.NextAssemblyUsageOccurrenceEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("id", entry.value.id)
                put("name", entry.value.name)
                put("relating_product_definition_id", entry.value.relatingProductDefinition.id)
                put("related_product_definition_id", entry.value.relatedProductDefinition.id)
                put("reference_designator", entry.value.referenceDesignator)
            }
        is EntityStoreEntry.ApprovalEntry ->
            buildJsonObject {
                put("entityType", entry.entityType)
                put("status", entry.value.status)
                put("level", entry.value.level)
                put("authorized_by_handle", store.keyOf(entry.value.authorizedBy))
            }
    }
