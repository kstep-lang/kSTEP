package dev.kstep.mcp

import dev.kstep.core.ap242.Approval
import dev.kstep.core.ap242.NextAssemblyUsageOccurrence
import dev.kstep.core.ap242.PersonAndOrganization
import dev.kstep.core.ap242.Product
import dev.kstep.core.ap242.ProductDefinition
import dev.kstep.core.ap242.ProductDefinitionFormation
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped, in-memory store for the entities an LLM agent builds across a sequence of
 * MCP tool calls — one process-lifetime [Server][io.modelcontextprotocol.kotlin.sdk.server.Server]
 * owns one store, reset only on server restart. This is what lets a later `build_*` call
 * reference an entity an earlier call already validated, without ever passing a live Kotlin
 * object across the JSON tool-call boundary: only string IDs cross that boundary, and this
 * store resolves them back to the real, already-validated object.
 *
 * Re-storing under an already-used [id] with the SAME entity type overwrites the previous
 * entry (see `build_product`'s tool description for the caller-facing consequence: entities
 * that already captured the old object as a reference keep pointing at it — Kotlin
 * `data class` values are immutable, so an overwrite can never retroactively rewrite an
 * already-built dependent). Re-storing under an already-used [id] with a DIFFERENT entity
 * type is rejected ([PutOutcome.TypeMismatch]) rather than silently evicting the original
 * entry — a flat `Map<String, EntityStoreEntry>` has no natural per-type namespacing, so
 * without this check an id collision across two different `build_*` tools (e.g.
 * `build_product(id="A")` then `build_person_and_organization(handle="A")`) would silently
 * discard the first entity behind a normal-looking "success" result, only surfacing as a
 * confusing `unknown_reference` error on a later lookup by its original type.
 *
 * Bounded at [maxEntities] to cap worst-case memory from an adversarial/runaway caller — see
 * kSTEP M2 Welle 1 plan §4.3. [put]'s capacity/type checks and insert run under a single lock
 * so two concurrent calls near the cap can never both slip through (a
 * `ConcurrentHashMap.size()` read followed by a separate `put()` would be a classic TOCTOU
 * race here); plain reads ([get]/[snapshot]) stay lock-free on top of [ConcurrentHashMap].
 *
 * [putIfNoConflict] extends the same single-lock guarantee to a caller-supplied UNIQUE-rule
 * scan (see `NextAssemblyUsageOccurrenceTool`'s UR1 check) — the scan and the insert happen
 * inside the same [capacityLock] critical section as [put]'s own checks, so two concurrent
 * calls that would both pass an unlocked "scan the store, then put" sequence can no longer
 * both slip a conflicting pair past the check (kSTEP M2 Welle 4 review/security finding).
 */
class EntityStore(
    private val maxEntities: Int = DEFAULT_MAX_ENTITIES,
) {
    private val entries = ConcurrentHashMap<String, EntityStoreEntry>()
    private val capacityLock = Any()

    sealed interface PutOutcome {
        data object Ok : PutOutcome

        data class CapacityExceeded(
            val currentSize: Int,
            val maxEntities: Int,
        ) : PutOutcome

        data class TypeMismatch(
            val id: String,
            val existingEntityType: String,
            val newEntityType: String,
        ) : PutOutcome

        data class Conflict(
            val conflictingId: String,
        ) : PutOutcome
    }

    fun put(
        id: String,
        entry: EntityStoreEntry,
    ): PutOutcome = putIfNoConflict(id, entry) { null }

    /**
     * Same as [put], but runs [findConflict] — a scan over the current entries, excluding
     * [id]'s own prior slot is [findConflict]'s own responsibility — inside the same lock as
     * the type/capacity checks and the insert itself, so the whole check-then-write sequence
     * is atomic. Returns [PutOutcome.Conflict] if [findConflict] returns a non-null id instead
     * of writing [entry].
     */
    fun putIfNoConflict(
        id: String,
        entry: EntityStoreEntry,
        findConflict: (Map<String, EntityStoreEntry>) -> String?,
    ): PutOutcome =
        synchronized(capacityLock) {
            val existing = entries[id]
            when {
                existing != null && existing.entityType != entry.entityType ->
                    PutOutcome.TypeMismatch(id, existing.entityType, entry.entityType)
                else ->
                    when (val conflictingId = findConflict(entries)) {
                        null ->
                            when {
                                existing == null && entries.size >= maxEntities ->
                                    PutOutcome.CapacityExceeded(entries.size, maxEntities)
                                else -> {
                                    entries[id] = entry
                                    PutOutcome.Ok
                                }
                            }
                        else -> PutOutcome.Conflict(conflictingId)
                    }
            }
        }

    fun get(id: String): EntityStoreEntry? = entries[id]

    fun snapshot(): Map<String, EntityStoreEntry> = entries.toMap()

    /**
     * Finds the store key currently holding [value] by object identity, not structural
     * equality — used only where an entity type has no natural id of its own
     * ([PersonAndOrganization], referenced by [Approval.authorizedBy]) and the caller-supplied
     * handle string can't otherwise be recovered from the value alone. `O(n)` in store size,
     * which is fine at this store's capped scale (see [maxEntities]) and its only two call
     * sites (`build_approval`'s echo, `get_entity`'s field dump).
     */
    fun keyOf(value: Any): String? = entries.entries.firstOrNull { it.value.rawValue === value }?.key

    companion object {
        const val DEFAULT_MAX_ENTITIES = 512
    }
}

fun EntityStore.findProduct(id: String): Product? = (get(id) as? EntityStoreEntry.ProductEntry)?.value

fun EntityStore.findPersonAndOrganization(id: String): PersonAndOrganization? =
    (get(id) as? EntityStoreEntry.PersonAndOrganizationEntry)?.value

fun EntityStore.findProductDefinitionFormation(id: String): ProductDefinitionFormation? =
    (get(id) as? EntityStoreEntry.ProductDefinitionFormationEntry)?.value

fun EntityStore.findProductDefinition(id: String): ProductDefinition? =
    (get(id) as? EntityStoreEntry.ProductDefinitionEntry)?.value

fun EntityStore.findNextAssemblyUsageOccurrence(id: String): NextAssemblyUsageOccurrence? =
    (get(id) as? EntityStoreEntry.NextAssemblyUsageOccurrenceEntry)?.value

fun EntityStore.findApproval(id: String): Approval? = (get(id) as? EntityStoreEntry.ApprovalEntry)?.value
