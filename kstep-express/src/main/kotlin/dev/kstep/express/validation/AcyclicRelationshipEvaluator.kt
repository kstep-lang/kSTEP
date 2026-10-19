package dev.kstep.express.validation

import java.util.IdentityHashMap

/** A bound of [AcyclicRelationshipEvaluator] was exceeded; the verdict is neither "acyclic" nor "cyclic". */
class AcyclicEvaluationLimitExceededException(
    message: String,
) : InstanceGraphException(message)

/**
 * `acyclic_product_definition_relationship(relation, [related], specific_relation)` of the AP242
 * MIM schema (ADR-0023), as an O(V+E) depth-first search instead of the schema function's
 * path-set recursion (which is exponential on diamond-shaped assemblies).
 *
 * Semantics. Let `A = relation.relating_product_definition`, `B = relation.related_product_definition`.
 * The EXPRESS function returns `FALSE` iff `A = B`, or walking *upwards* from `A` (to the
 * `relating_product_definition` of every relation of type `specific_relation` that has the current
 * node as its `related_product_definition`) reaches `B` or revisits a node of the current path. The
 * DFS below reports exactly that: grey nodes are on the current path (revisit = cycle), black
 * nodes are fully explored without a hit (never re-explored). It is iterative, so a long chain
 * cannot overflow the JVM stack.
 *
 * Note for readers: a cycle anywhere *above* `A` makes every relation below it invalid, because the
 * EXPRESS function walks the whole ancestor closure. That is the specified behaviour, not a false
 * positive.
 */
object AcyclicRelationshipEvaluator {
    data class Limits(
        val maxDepth: Int = 256,
        val maxVisited: Int = 100_000,
        /**
         * Visited nodes summed over all walks that share one [Budget] (one population validation). Each walk
         * is O(V+E), so without a shared cap a hostile population could make N walks cost N*(V+E).
         */
        val maxTotalVisited: Long = 20_000_000L,
    ) {
        init {
            require(maxDepth > 0 && maxVisited > 0 && maxTotalVisited > 0) { "all limits must be positive" }
        }
    }

    /** A mutable counter shared by the walks of one validation run; not thread-safe, never shared across runs. */
    class Budget(
        private val maxTotalVisited: Long = Limits().maxTotalVisited,
    ) {
        private var used = 0L

        internal fun spend() {
            used++
            if (used > maxTotalVisited) {
                throw AcyclicEvaluationLimitExceededException(
                    "the acyclic checks of this population visited more than $maxTotalVisited product definitions " +
                        "in total",
                )
            }
        }
    }

    sealed interface Outcome {
        data object Acyclic : Outcome

        /** [path] is the node sequence `A, ..., B` (or `A, ..., X, A'` when an ancestor loop was found). */
        data class Cycle(
            val path: List<Any>,
        ) : Outcome

        /** No cycle was found, but a visited node has invisible referrers (incomplete population). */
        data class Indeterminate(
            val reason: String,
        ) : Outcome
    }

    /**
     * The graph-agnostic core. [parentsOf] returns the `relating` end of every relation of the
     * specific type whose `related` end is the given node. Nodes are compared by identity.
     */
    fun walk(
        relating: Any,
        related: Any,
        parentsOf: (Any) -> List<Any>,
        isComplete: (Any) -> Boolean = { true },
        limits: Limits = Limits(),
        budget: Budget? = null,
    ): Outcome {
        if (relating === related) return Outcome.Cycle(listOf(relating, relating))

        class Frame(
            val node: Any,
            val parents: List<Any>,
        ) {
            var next = 0
        }

        val stack = ArrayList<Frame>()
        val grey = IdentityHashMap<Any, Int>()
        val black = IdentityHashMap<Any, Unit>()
        var visited = 0
        var sawIncomplete = false

        fun enter(node: Any) {
            budget?.spend()
            visited++
            if (visited > limits.maxVisited) {
                throw AcyclicEvaluationLimitExceededException(
                    "acyclic check visited more than ${limits.maxVisited} product definitions",
                )
            }
            if (stack.size >= limits.maxDepth) {
                throw AcyclicEvaluationLimitExceededException(
                    "acyclic check exceeded the ancestor depth of ${limits.maxDepth}",
                )
            }
            if (!isComplete(node)) sawIncomplete = true
            grey[node] = stack.size
            stack.add(Frame(node, parentsOf(node)))
        }

        enter(relating)
        while (stack.isNotEmpty()) {
            val frame = stack[stack.size - 1]
            if (frame.next < frame.parents.size) {
                val parent = frame.parents[frame.next++]
                if (parent === related) return Outcome.Cycle(stack.map { it.node } + parent)
                val greyAt = grey[parent]
                if (greyAt != null) return Outcome.Cycle(stack.drop(greyAt).map { it.node } + parent)
                if (black.containsKey(parent)) continue
                enter(parent)
            } else {
                stack.removeAt(stack.size - 1)
                grey.remove(frame.node)
                black[frame.node] = Unit
            }
        }
        return if (sawIncomplete) {
            Outcome.Indeterminate("an instance referencing a visited product definition could not be reconstructed")
        } else {
            Outcome.Acyclic
        }
    }

    private const val RELATIONSHIP = "product_definition_relationship"
    private const val RELATING = "relating_product_definition"
    private const val RELATED = "related_product_definition"

    /**
     * Evaluates the check for the relation [context]`.self` over the population, restricting the
     * walked relations to [specificRelation] (a lower-case entity name; subtypes included).
     */
    fun evaluate(
        context: InstanceEvaluationContext,
        specificRelation: String,
    ): Outcome {
        val index = context.index
        val resolver = context.roleResolver

        fun endOf(
            relation: Any,
            attribute: String,
        ): Any {
            val description =
                index.describe(relation)
                    ?: throw WhereRuleEvaluationException("relation is not part of the population")
            val targets =
                description.references
                    .firstOrNull {
                        it.declaringEntity.lowercase() == RELATIONSHIP && it.attribute.lowercase() == attribute
                    }?.targets
            return targets?.singleOrNull()
                ?: throw WhereRuleEvaluationException(
                    "'${clipForMessage(description.entityName)}' has no single '$attribute' reference",
                )
        }

        return walk(
            relating = endOf(context.self, RELATING),
            related = endOf(context.self, RELATED),
            parentsOf = { node ->
                index
                    .usedIn(node, RELATIONSHIP, RELATED)
                    .filter { resolver.isSubtypeOrSelf(it.referrerEntity, specificRelation) }
                    .map { endOf(it.referrer, RELATING) }
            },
            isComplete = { context.scope.isComplete(it) },
            limits = context.acyclicLimits,
            budget = context.acyclicBudget,
        )
    }

    /** `1: 'pd-a' -> 'pd-b'`-style rendering of a cycle path, with each node labelled by its `id` attribute. */
    fun describePath(
        path: List<Any>,
        index: ReverseReferenceIndex,
        maxNodes: Int = 12,
    ): String {
        fun label(node: Any): String {
            val description = index.describe(node)
            val id = (description?.attributeValues?.get("id") as? WhereRuleValue.StringValue)?.value
            return "'${clipForMessage(id ?: description?.entityName ?: "?", 64)}'"
        }
        val shown = path.take(maxNodes).joinToString(" -> ") { label(it) }
        return if (path.size > maxNodes) "$shown -> ... (${path.size - maxNodes} more)" else shown
    }
}
