package dev.kstep.express.validation

import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.semantic.ResolvedUniqueRule

/** A `UNIQUE` rule uses a construct kSTEP cannot evaluate (loud, never silently skipped). */
class UnsupportedUniqueRuleException(
    message: String,
) : RuntimeException(message)

/**
 * Evaluates the `UNIQUE` rules of a population (ISO 10303-11 section 9.2.1.2, ADR-0023).
 *
 * Semantics. A rule `UR: a, b` demands that no two instances of the declaring entity (or one of its
 * subtypes) agree on *all* listed values. EXPRESS compares with `=`, which is never satisfied by an
 * indeterminate value, so an instance whose tuple contains an unset OPTIONAL attribute, or a derived
 * value that is `?`, takes no part in the comparison (ADR-0023 D6). Entity-valued attributes are
 * compared by identity, strings by equality. Grouping uses hashing, so the cost is O(n) per rule.
 *
 * Listed attributes may be explicit value attributes, entity references, or derived attributes of
 * the instance's entity (evaluated through the same expression machinery as `DERIVE`; the
 * `SELF\entity.` qualifier is accepted and resolved by attribute name).
 */
object UniqueRuleEvaluator {
    /** One violated rule: [instances] (at least two) agree on [fieldValues]. */
    class Violation(
        val rule: ResolvedUniqueRule,
        val instances: List<Any>,
        val fieldValues: List<Pair<String, String>>,
    ) {
        override fun toString(): String =
            "Violation(${rule.declaringEntity}.${rule.rule.label}, ${instances.size} instances)"
    }

    class Result(
        val violations: List<Violation>,
        /** Tuples skipped because a derived value could not be determined (incomplete population). */
        val indeterminateCount: Int,
    )

    private sealed interface Key {
        data class Str(
            val value: String,
        ) : Key

        class Ref(
            val target: Any,
        ) : Key {
            override fun equals(other: Any?): Boolean = other is Ref && other.target === target

            override fun hashCode(): Int = System.identityHashCode(target)
        }
    }

    private class Unknown : RuntimeException(null, null, false, false)

    fun evaluate(
        index: ReverseReferenceIndex,
        resolvedEntities: Map<String, ResolvedEntity>,
        roleResolver: UsedInRoleResolver,
        intrinsics: SchemaFunctionIntrinsicSet = SchemaFunctionIntrinsicSet.NONE,
        incomplete: Collection<Any> = emptyList(),
    ): Result {
        val byLowerName = resolvedEntities.values.associateBy { it.entity.name.lowercase() }
        // (declaring entity, rule label, tuple) -> instances, grouped per rule.
        val groups = LinkedHashMap<Pair<String, List<Key>>, MutableList<Any>>()
        val fieldNames = HashMap<Pair<String, List<Key>>, List<String>>()
        val ruleByGroup = HashMap<Pair<String, List<Key>>, ResolvedUniqueRule>()
        var indeterminate = 0
        for (instance in index.instances()) {
            val description = checkNotNull(index.describe(instance))
            val resolved = byLowerName[description.entityName.lowercase()] ?: continue
            if (resolved.flattenedUniqueRules.isEmpty()) continue
            val context = InstanceEvaluationContext(instance, description.entityName, index, roleResolver, incomplete)
            for (unique in resolved.flattenedUniqueRules) {
                val names = unique.rule.referencedAttributes.map { attributeNameOf(it, unique) }
                val tuple = ArrayList<Key>(names.size)
                try {
                    for (name in names) tuple += keyOf(name, instance, description, resolved, context, intrinsics)
                } catch (_: Unknown) {
                    continue
                } catch (_: IndeterminateDerivedValueException) {
                    indeterminate++
                    continue
                }
                val groupKey = "${unique.declaringEntity.lowercase()}.${unique.rule.label ?: ""}" to tuple
                groups.getOrPut(groupKey) { ArrayList() }.add(instance)
                fieldNames.putIfAbsent(groupKey, names)
                ruleByGroup.putIfAbsent(groupKey, unique)
            }
        }
        val violations =
            groups
                .filterValues { it.size > 1 }
                .map { (groupKey, members) ->
                    val rule = ruleByGroup.getValue(groupKey)
                    val names = fieldNames.getValue(groupKey)
                    Violation(rule, members, names.zip(groupKey.second.map(::render)))
                }
        return Result(violations, indeterminate)
    }

    private fun render(key: Key): String =
        when (key) {
            is Key.Str -> "'${clipForMessage(key.value, 64)}'"
            is Key.Ref -> "<instance>"
        }

    private val ATTRIBUTE_REFERENCE = Regex("""(?:SELF\\([A-Za-z0-9_]+)\.)?([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE)

    private fun attributeNameOf(
        text: String,
        rule: ResolvedUniqueRule,
    ): String =
        ATTRIBUTE_REFERENCE
            .matchEntire(text.trim())
            ?.groupValues
            ?.get(2)
            ?.lowercase()
            ?: throw UnsupportedUniqueRuleException(
                "UNIQUE rule ${rule.declaringEntity}.${rule.rule.label ?: "(unlabeled)"}: referenced attribute " +
                    "'${clipForMessage(text)}' is not a plain or SELF\\entity.attribute reference",
            )

    private fun keyOf(
        name: String,
        instance: Any,
        description: InstanceDescription,
        resolved: ResolvedEntity,
        context: InstanceEvaluationContext,
        intrinsics: SchemaFunctionIntrinsicSet,
    ): Key {
        description.references.firstOrNull { it.attribute.lowercase() == name }?.let { reference ->
            val target = reference.targets.singleOrNull() ?: throw Unknown()
            return Key.Ref(target)
        }
        description.attributeValues[name]?.let { return valueKey(it) }
        val derived = resolved.flattenedDerivedAttributes.firstOrNull { it.name.lowercase() == name }
        if (derived != null) {
            val value =
                DerivedAttributeEvaluator.evaluate(
                    derived.expressionText,
                    description.attributeValues,
                    context,
                    intrinsics,
                )
            return valueKey(value)
        }
        throw UnsupportedUniqueRuleException(
            "UNIQUE attribute '${clipForMessage(name)}' of '${clipForMessage(description.entityName)}' is neither " +
                "an explicit nor a derived attribute known to the instance describer",
        )
    }

    private fun valueKey(value: WhereRuleValue): Key =
        when (value) {
            is WhereRuleValue.StringValue -> Key.Str(value.value)
            is WhereRuleValue.IntegerValue -> Key.Str(value.value.toString())
            is WhereRuleValue.RealValue -> Key.Str(value.value.toString())
            is WhereRuleValue.BooleanValue -> Key.Str(value.value.toString())
            WhereRuleValue.Unset -> throw Unknown()
            WhereRuleValue.EntityReference -> throw Unknown()
            is WhereRuleValue.InstanceAggregate ->
                throw UnsupportedUniqueRuleException("UNIQUE over an aggregate value is not supported")
        }
}
