package dev.kstep.express.codegen

import dev.kstep.express.semantic.AggregationType
import dev.kstep.express.semantic.DefinedTypeRef
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ExpressSelectItem
import dev.kstep.express.semantic.ExpressType
import dev.kstep.express.semantic.ResolvedEntity

/**
 * Which ENUMERATION and closed-SELECT TYPEs does a set of emitted entities actually need?
 * Used by [CodeGenOptions.definedTypeScope] `REACHABLE_FROM_EMITTED_ENTITIES` so a generator that
 * filters entities (Ap242V1CodeGen) does not also emit every unrelated TYPE of the schema.
 */
object DefinedTypeReachability {
    /**
     * Lowercase names of every ENUMERATION/SELECT TYPE reachable from [emittedEntities]'
     * flattened attribute types, transitively through aggregations, alias chains and SELECT
     * members (alias members included, so a wrapper's value type is reachable too).
     * Visited-set based, hence cycle-safe; unknown names are ignored (resolution reports them
     * with a precise message later). Entity members of a SELECT do NOT enlarge the entity set.
     */
    fun reachableEmittableTypes(
        emittedEntities: Collection<ResolvedEntity>,
        definedTypes: Map<String, ExpressDefinedType>,
    ): Set<String> {
        val work = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        val result = linkedSetOf<String>()

        fun visitType(type: ExpressType) {
            when (type) {
                is DefinedTypeRef -> work.addLast(type.typeName.lowercase())
                is AggregationType -> visitType(type.elementType)
                else -> Unit
            }
        }

        emittedEntities.forEach { resolved ->
            resolved.flattenedAttributes.forEach { visitType(it.attribute.declaredType) }
        }
        while (work.isNotEmpty()) {
            val key = work.removeLast()
            if (!visited.add(key)) continue
            val definedType = definedTypes[key] ?: continue
            definedType.aliasedTypeName?.let { work.addLast(it.lowercase()) }
            if (definedType.enumerationItems != null) result += key
            definedType.selectItems?.let { items ->
                result += key
                items.filterIsInstance<ExpressSelectItem.DefinedType>().forEach { work.addLast(it.name.lowercase()) }
            }
        }
        return result
    }
}
