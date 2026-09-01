package dev.kstep.mcp

import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.Approval
import dev.kstep.generated.ap242v1.ApprovalStatus
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.Organization
import dev.kstep.generated.ap242v1.Person
import dev.kstep.generated.ap242v1.PersonAndOrganization
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductContext
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionContext
import dev.kstep.generated.ap242v1.ProductDefinitionFormation

/**
 * A tagged wrapper around one of the twelve `kstep-core` AP242 entity types (kSTEP M2 Welle 10
 * — six V1 entities plus six support entities the codegen-generated shapes now require), as
 * held in an [EntityStore]. Sealed rather than a bare `Map<String, Any>` so every store consumer
 * ([EntityStore.findProduct] and friends, `list_entities`, `get_entity`) gets a compile-time
 * exhaustive `when` instead of an unchecked cast that could throw `ClassCastException` on a
 * wrong-type lookup.
 */
sealed interface EntityStoreEntry {
    val entityType: String
    val rawValue: Any

    data class ApplicationContextEntry(
        val value: ApplicationContext,
    ) : EntityStoreEntry {
        override val entityType: String = "application_context"
        override val rawValue: Any get() = value
    }

    data class ProductContextEntry(
        val value: ProductContext,
    ) : EntityStoreEntry {
        override val entityType: String = "product_context"
        override val rawValue: Any get() = value
    }

    data class ProductDefinitionContextEntry(
        val value: ProductDefinitionContext,
    ) : EntityStoreEntry {
        override val entityType: String = "product_definition_context"
        override val rawValue: Any get() = value
    }

    data class ApprovalStatusEntry(
        val value: ApprovalStatus,
    ) : EntityStoreEntry {
        override val entityType: String = "approval_status"
        override val rawValue: Any get() = value
    }

    data class PersonEntry(
        val value: Person,
    ) : EntityStoreEntry {
        override val entityType: String = "person"
        override val rawValue: Any get() = value
    }

    data class OrganizationEntry(
        val value: Organization,
    ) : EntityStoreEntry {
        override val entityType: String = "organization"
        override val rawValue: Any get() = value
    }

    data class ProductEntry(
        val value: Product,
    ) : EntityStoreEntry {
        override val entityType: String = "product"
        override val rawValue: Any get() = value
    }

    data class PersonAndOrganizationEntry(
        val value: PersonAndOrganization,
    ) : EntityStoreEntry {
        override val entityType: String = "person_and_organization"
        override val rawValue: Any get() = value
    }

    data class ProductDefinitionFormationEntry(
        val value: ProductDefinitionFormation,
    ) : EntityStoreEntry {
        override val entityType: String = "product_definition_formation"
        override val rawValue: Any get() = value
    }

    data class ProductDefinitionEntry(
        val value: ProductDefinition,
    ) : EntityStoreEntry {
        override val entityType: String = "product_definition"
        override val rawValue: Any get() = value
    }

    data class NextAssemblyUsageOccurrenceEntry(
        val value: NextAssemblyUsageOccurrence,
    ) : EntityStoreEntry {
        override val entityType: String = "next_assembly_usage_occurrence"
        override val rawValue: Any get() = value
    }

    data class ApprovalEntry(
        val value: Approval,
    ) : EntityStoreEntry {
        override val entityType: String = "approval"
        override val rawValue: Any get() = value
    }
}

/**
 * Sum of the lengths of this entry's own caller-controlled string content — exactly the same
 * "own" string arguments `Part21Writer.writeArgs` renders for this entity type (see that file),
 * never a referenced entity's strings. [EntityStore] uses this to bound total store memory by
 * content size, not just entity count (kSTEP M2 Welle 10 security review). Restricting the sum
 * to "own" strings, mirroring what `Part21Writer` itself serializes per instance, is what keeps
 * this additive across the whole store: a referenced entity (e.g. a `product_context` shared by
 * many `product`s) is counted once, in its own entry, never re-counted through every entry that
 * references it.
 */
fun EntityStoreEntry.estimatedCharCount(): Long =
    when (this) {
        is EntityStoreEntry.ApplicationContextEntry -> value.application.length.toLong()
        is EntityStoreEntry.ProductContextEntry ->
            value.name.length.toLong() + value.disciplineType.length
        is EntityStoreEntry.ProductDefinitionContextEntry ->
            value.name.length.toLong() + value.lifeCycleStage.length
        is EntityStoreEntry.ApprovalStatusEntry -> value.name.length.toLong()
        is EntityStoreEntry.PersonEntry ->
            value.id.length.toLong() +
                (value.lastName?.length ?: 0) +
                (value.firstName?.length ?: 0) +
                (value.middleNames?.sumOf { it.length } ?: 0) +
                (value.prefixTitles?.sumOf { it.length } ?: 0) +
                (value.suffixTitles?.sumOf { it.length } ?: 0)
        is EntityStoreEntry.OrganizationEntry ->
            (value.id?.length ?: 0).toLong() + value.name.length + (value.description?.length ?: 0)
        is EntityStoreEntry.ProductEntry ->
            value.id.length.toLong() + value.name.length + (value.description?.length ?: 0)
        is EntityStoreEntry.PersonAndOrganizationEntry -> 0L
        is EntityStoreEntry.ProductDefinitionFormationEntry ->
            value.id.length.toLong() + (value.description?.length ?: 0)
        is EntityStoreEntry.ProductDefinitionEntry ->
            value.id.length.toLong() + (value.description?.length ?: 0)
        is EntityStoreEntry.NextAssemblyUsageOccurrenceEntry ->
            value.id.length.toLong() +
                value.name.length +
                (value.description?.length ?: 0) +
                (value.referenceDesignator?.length ?: 0)
        is EntityStoreEntry.ApprovalEntry -> value.level.length.toLong()
    }
