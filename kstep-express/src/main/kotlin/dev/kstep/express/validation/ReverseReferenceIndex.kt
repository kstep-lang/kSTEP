package dev.kstep.express.validation

import java.util.IdentityHashMap

internal fun identitySetOf(items: Collection<Any>): IdentityHashMap<Any, Unit> {
    val set = IdentityHashMap<Any, Unit>()
    for (item in items) set[item] = Unit
    return set
}

/**
 * One usage: [referrer] (concrete entity [referrerEntity]) uses [target] in the role
 * `[declaringEntity].[attribute]`. Deliberately not a `data class`: instances are compared by
 * identity (generated AP242 types are data classes, so structural equality would merge distinct
 * instances), and `toString` never dumps instance values.
 */
class InstanceReference internal constructor(
    val referrer: Any,
    val referrerEntity: String,
    val declaringEntity: String,
    val attribute: String,
    val target: Any,
) {
    override fun toString(): String = "InstanceReference($referrerEntity -> $declaringEntity.$attribute)"
}

/** One attribute of an instance that references other instances (supplied by an [InstanceDescriber]). */
data class OutgoingReference(
    /** Lower-case EXPRESS name of the entity that *declares* the attribute (not the referrer's concrete type). */
    val declaringEntity: String,
    /** Lower-case EXPRESS attribute name. */
    val attribute: String,
    val targets: List<Any>,
)

/** What the index needs to know about one instance; supplied by a domain adapter (e.g. `kstep-core`). */
data class InstanceDescription(
    /** Lower-case EXPRESS name of the concrete entity type. */
    val entityName: String,
    /** Primitive attribute values by EXPRESS attribute name, for WHERE-rule evaluation. */
    val attributeValues: Map<String, WhereRuleValue>,
    val references: List<OutgoingReference>,
)

/** Maps a domain instance to its [InstanceDescription]; returns `null` for an unknown type. */
fun interface InstanceDescriber {
    fun describe(instance: Any): InstanceDescription?
}

/**
 * An immutable, identity-based reverse-reference index over a population of entity instances
 * (ADR-0022): for every instance, who references it and in which role. It is the basis for
 * `USEDIN` and `INVERSE` evaluation.
 *
 * Built once per validation run from a snapshot of roots (the transitive closure reachable via
 * the describer's references is the population), then never modified; all state is held in
 * `val` fields that are never exposed mutably, so a built index may be read from any thread.
 * Instances are keyed by *identity* — two structurally equal data-class instances are two
 * distinct entities.
 */
class ReverseReferenceIndex private constructor(
    private val descriptions: IdentityHashMap<Any, InstanceDescription>,
    private val incoming: IdentityHashMap<Any, List<InstanceReference>>,
    private val order: List<Any>,
    val instanceCount: Int,
    val referenceCount: Int,
) {
    data class Limits(
        val maxInstances: Int = 100_000,
        val maxReferences: Int = 1_000_000,
        /** Maximum length of a reference chain (in instances) walked from a root. */
        val maxDepth: Int = 64,
        val maxReferencesPerAttribute: Int = 65_536,
    ) {
        init {
            require(maxInstances > 0 && maxReferences > 0 && maxDepth > 0 && maxReferencesPerAttribute > 0) {
                "all limits must be positive"
            }
        }
    }

    fun contains(instance: Any): Boolean = descriptions.containsKey(instance)

    fun describe(instance: Any): InstanceDescription? = descriptions[instance]

    fun entityNameOf(instance: Any): String? = descriptions[instance]?.entityName

    /** Every usage of [target] in any role (a BAG: one entry per usage). */
    fun incoming(target: Any): List<InstanceReference> = incoming[target] ?: emptyList()

    /** `USEDIN(target, 'schema.declaringEntity.attribute')` — a BAG with one entry per usage. */
    fun usedIn(
        target: Any,
        declaringEntity: String,
        attribute: String,
    ): List<InstanceReference> =
        incoming(target).filter {
            it.declaringEntity == declaringEntity.lowercase() && it.attribute == attribute.lowercase()
        }

    /** All instances in deterministic discovery order. */
    fun instances(): List<Any> = order

    /**
     * A [DerivedValueScope] over this index. [incomplete] holds the instances (compared by identity)
     * whose referrers are partly invisible, e.g. on a Part 21 read: opaque or rejected instances
     * reference them.
     */
    fun asDerivedValueScope(incomplete: Collection<Any> = emptyList()): DerivedValueScope {
        val incompleteSet = identitySetOf(incomplete)
        val index = this
        return object : DerivedValueScope {
            override fun usedIn(
                target: Any,
                declaringEntity: String,
                attribute: String,
            ): List<InstanceReference> = index.usedIn(target, declaringEntity, attribute)

            override fun describe(instance: Any): InstanceDescription? = index.describe(instance)

            override fun isComplete(instance: Any): Boolean = !incompleteSet.containsKey(instance)
        }
    }

    companion object {
        fun build(
            roots: Collection<Any>,
            describer: InstanceDescriber,
            limits: Limits = Limits(),
        ): ReverseReferenceIndex {
            val rootList = roots.toList()
            val descriptions = IdentityHashMap<Any, InstanceDescription>()
            val incoming = IdentityHashMap<Any, MutableList<InstanceReference>>()
            val order = ArrayList<Any>()
            var referenceCount = 0

            class Frame(
                val instance: Any,
                val depth: Int,
                val via: String,
            )

            // Explicit stack: a 100 000-link chain must not overflow the JVM stack.
            val stack = ArrayDeque<Frame>()
            for (root in rootList.asReversed()) stack.addLast(Frame(root, 0, "<root>"))
            while (stack.isNotEmpty()) {
                val frame = stack.removeLast()
                val instance = frame.instance
                if (descriptions.containsKey(instance)) continue
                if (frame.depth >= limits.maxDepth) {
                    throw ReverseReferenceIndexLimitExceededException(
                        "reference chain exceeds ${limits.maxDepth} levels at link '${clipForMessage(frame.via)}'",
                    )
                }
                val description =
                    describer.describe(instance)
                        ?: throw UnknownInstanceTypeException(
                            "instance reached via '${clipForMessage(frame.via)}' is of a type the describer " +
                                "does not know (${clipForMessage(instance::class.java.name)})",
                        )
                if (descriptions.size >= limits.maxInstances) {
                    throw ReverseReferenceIndexLimitExceededException(
                        "population exceeds ${limits.maxInstances} instances at link '${clipForMessage(frame.via)}'",
                    )
                }
                val entityName = description.entityName.lowercase()
                val entityShown = clipForMessage(entityName)
                descriptions[instance] = description
                order.add(instance)
                for (reference in description.references) {
                    val declaring = reference.declaringEntity.lowercase()
                    val attribute = reference.attribute.lowercase()
                    val roleShown = clipForMessage("$declaring.$attribute")
                    val link = "$roleShown ($entityShown) -> ${reference.targets.size} target(s)"
                    if (reference.targets.size > limits.maxReferencesPerAttribute) {
                        throw ReverseReferenceIndexLimitExceededException(
                            "attribute '$roleShown' of '$entityShown' holds ${reference.targets.size} " +
                                "references, more than ${limits.maxReferencesPerAttribute}",
                        )
                    }
                    for (target in reference.targets) {
                        if (referenceCount >= limits.maxReferences) {
                            throw ReverseReferenceIndexLimitExceededException(
                                "population exceeds ${limits.maxReferences} references at link '$link'",
                            )
                        }
                        referenceCount++
                        incoming
                            .getOrPut(target) { ArrayList() }
                            .add(InstanceReference(instance, entityName, declaring, attribute, target))
                        if (!descriptions.containsKey(target)) {
                            stack.addLast(Frame(target, frame.depth + 1, "$roleShown ($entityShown)"))
                        }
                    }
                }
            }

            val frozen = IdentityHashMap<Any, List<InstanceReference>>()
            for ((key, value) in incoming) frozen[key] = java.util.Collections.unmodifiableList(value)
            return ReverseReferenceIndex(
                descriptions,
                frozen,
                java.util.Collections.unmodifiableList(order),
                descriptions.size,
                referenceCount,
            )
        }
    }
}
