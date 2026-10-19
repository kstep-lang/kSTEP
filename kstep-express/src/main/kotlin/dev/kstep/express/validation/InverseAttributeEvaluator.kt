package dev.kstep.express.validation

import dev.kstep.express.semantic.AggregationType
import dev.kstep.express.semantic.DefinedTypeRef
import dev.kstep.express.semantic.EntityTypeRef
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ExpressInverseAttribute
import dev.kstep.express.semantic.ExpressSelectItem
import dev.kstep.express.semantic.InverseAggregationKind
import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.semantic.SemanticModelException
import java.util.IdentityHashMap

/** Cardinality of an `INVERSE` attribute; [upper] `null` means unbounded (`?`). */
data class InverseBounds(
    val lower: Long,
    val upper: Long?,
) {
    fun contains(size: Int): Boolean = size >= lower && (upper == null || size <= upper)

    override fun toString(): String = "[$lower:${upper ?: "?"}]"
}

/** The value of an `INVERSE` attribute for one instance, with the bound check outcome. */
data class InverseEvaluation(
    val attributeName: String,
    val value: WhereRuleValue.InstanceAggregate,
    val bounds: InverseBounds,
    val withinBounds: Boolean,
    /** Human-readable link, e.g. `a.b <- c.d: found 0, required [1:?]`. */
    val failedLink: String,
)

/** Thrown for an `INVERSE` form outside the supported subset (expression-based bounds, redeclaration). */
class UnsupportedInverseException(
    message: String,
) : RuntimeException(message)

/**
 * Evaluates an `INVERSE` attribute against an instance population (ISO 10303-11 section 9.2.1.3,
 * ADR-0022): the value is the set/bag of instances of the target entity (or a subtype) that
 * reference the owning instance through the `FOR` attribute. Bounds default to `[1:1]` without an
 * aggregation and `[0:?]` for a `SET`/`BAG` without bounds.
 */
object InverseAttributeEvaluator {
    private val BOUNDS_PATTERN = Regex("""\[\s*(\d+)\s*:\s*(\d+|\?)\s*]""")

    fun parseBounds(inverse: ExpressInverseAttribute): InverseBounds {
        val raw = inverse.boundsRawText
        if (inverse.kind == null) {
            if (raw != null) throw UnsupportedInverseException("INVERSE bounds without a SET/BAG aggregation")
            return InverseBounds(1, 1)
        }
        if (raw == null) return InverseBounds(0, null)
        val match =
            BOUNDS_PATTERN.matchEntire(raw.trim())
                ?: throw UnsupportedInverseException(
                    "INVERSE bounds '${clipForMessage(
                        raw,
                    )}' are not a literal [<int>:<int|?>] (expression bounds are unsupported)",
                )
        val lower =
            match.groupValues[1].toLongOrNull()
                ?: throw UnsupportedInverseException(
                    "INVERSE lower bound '${clipForMessage(match.groupValues[1])}' is out of range",
                )
        val upperText = match.groupValues[2]
        val upper =
            if (upperText == "?") {
                null
            } else {
                upperText.toLongOrNull()
                    ?: throw UnsupportedInverseException(
                        "INVERSE upper bound '${clipForMessage(upperText)}' is out of range",
                    )
            }
        if (upper != null && lower > upper) {
            throw UnsupportedInverseException("INVERSE bounds [$lower:$upper] have lower > upper")
        }
        return InverseBounds(lower, upper)
    }

    fun evaluate(
        owningEntity: String,
        inverse: ExpressInverseAttribute,
        resolvedEntities: Map<String, ResolvedEntity>,
        context: InstanceEvaluationContext,
        /**
         * The schema's TYPE declarations (lower-case keyed). Needed when the `FOR` attribute is typed by a
         * SELECT (e.g. `product_definition_or_reference`): the owner must then be a member (ADR-0023).
         */
        definedTypes: Map<String, ExpressDefinedType> = emptyMap(),
    ): InverseEvaluation {
        val explicit =
            inverse as? ExpressInverseAttribute.Explicit
                ?: throw UnsupportedInverseException(
                    "redeclared INVERSE attribute '${clipForMessage(
                        (inverse as ExpressInverseAttribute.Redeclared).rawText,
                    )}' " +
                        "is unsupported",
                )
        val bounds = parseBounds(explicit)
        val byLowerName = resolvedEntities.values.associateBy { it.entity.name.lowercase() }
        val owner = owningEntity.lowercase()
        val ownerResolved =
            byLowerName[owner]
                ?: throw SemanticModelException("INVERSE owner '${clipForMessage(owningEntity)}' is not a known entity")
        val target = explicit.targetEntity.lowercase()
        val forEntityName = (explicit.forEntity ?: explicit.targetEntity).lowercase()
        val forEntity =
            byLowerName[forEntityName]
                ?: throw SemanticModelException(
                    "INVERSE '${explicit.name}': FOR entity '${clipForMessage(forEntityName)}' is not a known entity",
                )
        val forAttribute = explicit.forAttribute.lowercase()
        val resolvedAttribute =
            forEntity.flattenedAttributes.firstOrNull { it.attribute.name.lowercase() == forAttribute }
                ?: throw SemanticModelException(
                    "INVERSE '${explicit.name}': entity '${forEntity.entity.name}' has no attribute " +
                        "'${clipForMessage(forAttribute)}'",
                )
        val referenced = referencedEntities(resolvedAttribute.attribute.declaredType, definedTypes)
        val ownerNames = (ownerResolved.ancestorChain + ownerResolved.entity.name).map { it.lowercase() }
        if (referenced.none { it.lowercase() in ownerNames }) {
            throw SemanticModelException(
                "INVERSE '${explicit.name}': attribute " +
                    "'${resolvedAttribute.declaringEntity}.${resolvedAttribute.attribute.name}' " +
                    "does not reference entity '${ownerResolved.entity.name}' or a supertype of it",
            )
        }
        val declaring = resolvedAttribute.declaringEntity.lowercase()
        val edges =
            context.index
                .usedIn(context.self, declaring, forAttribute)
                .filter { isInstanceOf(it.referrerEntity, target, byLowerName) }
        val kind = if (explicit.kind == InverseAggregationKind.BAG) AggregateKind.BAG else AggregateKind.SET
        val elements =
            if (kind == AggregateKind.SET) {
                val seen = IdentityHashMap<Any, Boolean>()
                edges.filter { seen.put(it.referrer, true) == null }
            } else {
                edges
            }
        val withinBounds = bounds.contains(elements.size)
        val link =
            "${ownerResolved.entity.name}.${explicit.name} <- ${resolvedAttribute.declaringEntity}." +
                "${resolvedAttribute.attribute.name}: found ${elements.size}, required $bounds"
        return InverseEvaluation(
            attributeName = explicit.name,
            value = WhereRuleValue.InstanceAggregate(kind, elements),
            bounds = bounds,
            withinBounds = withinBounds,
            failedLink = link,
        )
    }

    // The entities an attribute type can hold: the entity itself, or every entity member of a
    // (possibly aliased, possibly nested) SELECT. Visited-set based and depth-bounded.
    private fun referencedEntities(
        type: dev.kstep.express.semantic.ExpressType,
        definedTypes: Map<String, ExpressDefinedType>,
    ): Set<String> =
        when (type) {
            is EntityTypeRef -> setOf(type.entityName)
            is AggregationType -> referencedEntities(type.elementType, definedTypes)
            is DefinedTypeRef -> entitiesOfDefinedType(type.typeName, definedTypes)
            else -> emptySet()
        }

    private fun entitiesOfDefinedType(
        typeName: String,
        definedTypes: Map<String, ExpressDefinedType>,
    ): Set<String> {
        val result = linkedSetOf<String>()
        val visited = mutableSetOf<String>()
        val work = ArrayDeque(listOf(typeName.lowercase()))
        while (work.isNotEmpty() && visited.size < MAX_SELECT_WALK) {
            val key = work.removeLast()
            if (!visited.add(key)) continue
            val definedType = definedTypes[key] ?: continue
            definedType.aliasedTypeName?.let { work.addLast(it.lowercase()) }
            definedType.selectItems?.forEach { item ->
                when (item) {
                    is ExpressSelectItem.Entity -> result += item.name
                    is ExpressSelectItem.DefinedType -> work.addLast(item.name.lowercase())
                }
            }
        }
        return result
    }

    private const val MAX_SELECT_WALK = 256

    private fun isInstanceOf(
        entityName: String,
        expected: String,
        byLowerName: Map<String, ResolvedEntity>,
    ): Boolean {
        val resolved = byLowerName[entityName.lowercase()] ?: return false
        return resolved.entity.name.lowercase() == expected ||
            resolved.ancestorChain.any { it.lowercase() == expected }
    }
}
