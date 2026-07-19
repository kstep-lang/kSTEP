package dev.kstep.mcp

import dev.kstep.core.ap242.Approval
import dev.kstep.core.ap242.NextAssemblyUsageOccurrence
import dev.kstep.core.ap242.PersonAndOrganization
import dev.kstep.core.ap242.Product
import dev.kstep.core.ap242.ProductDefinition
import dev.kstep.core.ap242.ProductDefinitionFormation

/**
 * A tagged wrapper around one of the six V1 `kstep-core` AP242 entity types, as held in an
 * [EntityStore]. Sealed rather than a bare `Map<String, Any>` so every store consumer
 * ([EntityStore.findProduct] and friends, `list_entities`, `get_entity`) gets a compile-time
 * exhaustive `when` instead of an unchecked cast that could throw `ClassCastException` on a
 * wrong-type lookup.
 */
sealed interface EntityStoreEntry {
    val entityType: String
    val rawValue: Any

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
