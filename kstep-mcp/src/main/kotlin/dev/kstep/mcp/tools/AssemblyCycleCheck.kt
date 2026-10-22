package dev.kstep.mcp.tools

import dev.kstep.express.validation.AcyclicRelationshipEvaluator
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionOccurrence
import dev.kstep.generated.ap242v1.ProductDefinitionOrReference
import dev.kstep.mcp.EntityStoreEntry
import java.util.IdentityHashMap

/**
 * The WR1 check of `product_definition_usage` (`acyclic_product_definition_relationship`, ADR-0023) for a
 * `next_assembly_usage_occurrence` that is about to be stored: would adding `related -> relating` close a
 * cycle in the assembly structure? This is the same O(V+E) walk the population validator uses
 * ([AcyclicRelationshipEvaluator.walk]), over the usages in [entries].
 *
 * Returns the cycle as product-definition ids, or `null` when the usage is acyclic. Throws
 * [dev.kstep.express.validation.AcyclicEvaluationLimitExceededException] when [limits] are exceeded; the
 * caller must treat that as "not stored", never as "acyclic". [excludingId] is the entry that the new usage
 * replaces (a rebuild under the same id must not be blocked by its own previous version). Pure and
 * deterministic: edges are visited in store-id order.
 */
internal fun findAcyclicViolation(
    entries: Map<String, EntityStoreEntry>,
    excludingId: String,
    relating: ProductDefinitionOrReference,
    related: ProductDefinitionOrReference,
    limits: AcyclicRelationshipEvaluator.Limits = AcyclicRelationshipEvaluator.Limits(),
): List<String>? {
    // related -> relating ends, one list per related node (identity), in store-id order.
    val parentsByRelated = IdentityHashMap<Any, MutableList<Any>>()
    for ((id, entry) in entries.entries.sortedBy { it.key }) {
        if (id == excludingId || entry !is EntityStoreEntry.NextAssemblyUsageOccurrenceEntry) continue
        parentsByRelated
            .getOrPut(entry.value.relatedProductDefinition) { mutableListOf() }
            .add(entry.value.relatingProductDefinition)
    }
    val outcome =
        AcyclicRelationshipEvaluator.walk(
            relating = relating,
            related = related,
            parentsOf = { node -> parentsByRelated[node].orEmpty() },
            limits = limits,
        )
    return when (outcome) {
        is AcyclicRelationshipEvaluator.Outcome.Cycle -> outcome.path.take(MAX_PATH_LENGTH).map { label(it) }
        // The store has every usage, so the population is complete: Indeterminate cannot occur here.
        AcyclicRelationshipEvaluator.Outcome.Acyclic,
        is AcyclicRelationshipEvaluator.Outcome.Indeterminate,
        -> null
    }
}

private const val MAX_LABEL_LENGTH = 64
private const val MAX_PATH_LENGTH = 32

private fun label(node: Any): String =
    when (node) {
        is ProductDefinition -> node.id
        is ProductDefinitionOccurrence -> node.id
        else -> "?"
    }.take(MAX_LABEL_LENGTH)
