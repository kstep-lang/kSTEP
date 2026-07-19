package dev.kstep.step21

import dev.kstep.core.DslViolation
import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.PersonAndOrganization
import dev.kstep.core.ap242.Product
import dev.kstep.core.ap242.ProductDefinition
import dev.kstep.core.ap242.ProductDefinitionFormation
import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionFormation

/**
 * Pass 2: resolves a [Part21RawDocument] (pass-1 output) into a [Part21ReadResult].
 *
 * Three sub-steps, in this exact order — the order is load-bearing, not incidental:
 *
 * 1. Build the type-agnostic `#N` reference graph, detect dangling references (a `#N` used but
 *    never defined) and reference cycles via an iterative (non-recursive) topological sort.
 *    This step must run *before* step 2, because per-reference target-*type* verification
 *    would otherwise make [Part21CycleException] permanently unreachable dead code: the six
 *    real V1 entity shapes form a strict, acyclic reference DAG, so the only way to construct
 *    a genuinely reachable cycle in a test (or a hostile file) is to cross-wire two entities
 *    whose `REFERENCE`-argument *shape* matches at the same position but whose declared target
 *    *type* doesn't — a case step 2's type check would reject first if it ran first.
 * 2. Per-reference target-entity-type verification, now that every reference is known to
 *    resolve to a real, cycle-free instance.
 * 3. Typed construction via the matching `dev.kstep.core.ap242` builder function, in
 *    topological order (dependencies before dependents), so every entity-typed reference
 *    argument can be resolved from an already-built instance. A builder's
 *    [ValidationResult.Invalid] result is not thrown — it is recorded per-instance, and any
 *    instance depending (directly or, transitively via the topological order, indirectly) on
 *    a failed or skipped instance is itself recorded as skipped rather than attempted.
 */
internal object Part21GraphResolver {
    private const val MAX_REFERENCE_CHAIN_DEPTH = 64

    fun resolve(document: Part21RawDocument): Part21ReadResult {
        val rawById: Map<Int, Part21RawInstance> = document.instances.associateBy { it.id }
        val idOrder: List<Int> = document.instances.map { it.id }
        val edges: Map<Int, List<Int>> =
            rawById.mapValues { (_, raw) -> raw.args.filterIsInstance<Part21Value.Ref>().map { it.id } }

        checkDangling(rawById, edges)
        val order = topologicalOrder(idOrder, edges)
        checkReferenceTargetTypes(order, rawById)
        return construct(document.header, order, rawById, edges)
    }

    private fun checkDangling(
        rawById: Map<Int, Part21RawInstance>,
        edges: Map<Int, List<Int>>,
    ) {
        val dangling = mutableListOf<Pair<Int, Int>>()
        for ((id, targets) in edges) {
            for (target in targets) {
                if (target !in rawById) {
                    dangling += id to target
                }
            }
        }
        if (dangling.isNotEmpty()) {
            val message =
                dangling.joinToString("; ") { (from, to) -> "#$from references #$to, which is never defined in DATA" }
            throw Part21DanglingReferenceException(message)
        }
    }

    private class Frame(
        val id: Int,
        var childIndex: Int = 0,
    )

    private enum class VisitState { IN_PROGRESS, DONE }

    // Iterative (explicit work-stack, no native recursion) post-order DFS topological sort —
    // so a maliciously long reference chain can't exhaust the JVM stack, mirroring
    // ExpressParserFactory's StackOverflowError guard and ExpressSemanticModelBuilder's
    // MAX_TYPE_NESTING_DEPTH.
    private fun topologicalOrder(
        idOrder: List<Int>,
        edges: Map<Int, List<Int>>,
    ): List<Int> {
        val state = HashMap<Int, VisitState>()
        val order = mutableListOf<Int>()

        for (start in idOrder) {
            if (state[start] == VisitState.DONE) continue

            val workStack = ArrayDeque<Frame>()
            workStack.addLast(Frame(start))
            state[start] = VisitState.IN_PROGRESS

            while (workStack.isNotEmpty()) {
                val frame = workStack.last()
                val children = edges[frame.id] ?: emptyList()
                if (frame.childIndex < children.size) {
                    val child = children[frame.childIndex]
                    frame.childIndex++
                    when (state[child]) {
                        null -> {
                            if (workStack.size >= MAX_REFERENCE_CHAIN_DEPTH) {
                                throw Part21LimitExceededException(
                                    "reference chain exceeds the maximum supported depth of " +
                                        "$MAX_REFERENCE_CHAIN_DEPTH (while descending from #$start toward #$child)",
                                )
                            }
                            state[child] = VisitState.IN_PROGRESS
                            workStack.addLast(Frame(child))
                        }
                        VisitState.IN_PROGRESS -> {
                            val path = workStack.map { it.id }
                            val cycleStart = path.indexOf(child)
                            val cycle = path.subList(cycleStart, path.size) + child
                            throw Part21CycleException(
                                "reference cycle detected: ${cycle.joinToString(" -> ") { "#$it" }}",
                            )
                        }
                        VisitState.DONE -> {
                            // already fully processed via an earlier branch of the DAG, nothing to do
                        }
                    }
                } else {
                    workStack.removeLast()
                    state[frame.id] = VisitState.DONE
                    order += frame.id
                }
            }
        }
        return order
    }

    private fun checkReferenceTargetTypes(
        order: List<Int>,
        rawById: Map<Int, Part21RawInstance>,
    ) {
        for (id in order) {
            val raw = rawById.getValue(id)
            val kind = Part21EntityKind.byEntityName.getValue(raw.entityName)
            raw.args.forEachIndexed { index, arg ->
                if (arg is Part21Value.Ref) {
                    val expectedTarget = kind.referenceTargets.getValue(index)
                    val actual = rawById.getValue(arg.id)
                    if (actual.entityName != expectedTarget.entityName) {
                        throw Part21SyntaxException(
                            "${kind.entityName} #$id argument ${index + 1} must reference a " +
                                "${expectedTarget.entityName}, but #${arg.id} is a ${actual.entityName}",
                        )
                    }
                }
            }
        }
    }

    private fun construct(
        header: Part21Header,
        order: List<Int>,
        rawById: Map<Int, Part21RawInstance>,
        edges: Map<Int, List<Int>>,
    ): Part21ReadResult {
        val built = LinkedHashMap<Int, Any>()
        val violations = LinkedHashMap<Int, List<DslViolation>>()
        val skipped = LinkedHashMap<Int, List<Int>>()

        for (id in order) {
            val failedDeps = edges.getValue(id).filter { it in violations || it in skipped }
            if (failedDeps.isNotEmpty()) {
                skipped[id] = failedDeps
                continue
            }

            val raw = rawById.getValue(id)
            val kind = Part21EntityKind.byEntityName.getValue(raw.entityName)
            val result: ValidationResult<Any> = buildInstance(kind, raw, built)

            when (result) {
                is ValidationResult.Valid -> built[id] = result.value
                is ValidationResult.Invalid -> violations[id] = result.violations
            }
        }

        return Part21ReadResult(header, built, violations, skipped)
    }

    private fun buildInstance(
        kind: Part21EntityKind,
        raw: Part21RawInstance,
        built: Map<Int, Any>,
    ): ValidationResult<Any> {
        fun str(index: Int) = (raw.args[index] as Part21Value.Str).text

        fun refId(index: Int) = (raw.args[index] as Part21Value.Ref).id

        return when (kind) {
            Part21EntityKind.PRODUCT ->
                product(str(0)) {
                    name = str(1)
                    description = str(2)
                }
            Part21EntityKind.PERSON_AND_ORGANIZATION ->
                personAndOrganization {
                    thePerson = str(0)
                    theOrganization = str(1)
                }
            Part21EntityKind.PRODUCT_DEFINITION_FORMATION ->
                productDefinitionFormation(str(0)) {
                    description = str(1)
                    ofProduct = built.getValue(refId(2)) as Product
                }
            Part21EntityKind.PRODUCT_DEFINITION ->
                productDefinition(str(0)) {
                    description = str(1)
                    formation = built.getValue(refId(2)) as ProductDefinitionFormation
                }
            Part21EntityKind.NEXT_ASSEMBLY_USAGE_OCCURRENCE ->
                nextAssemblyUsageOccurrence(str(0)) {
                    name = str(1)
                    relatingProductDefinition = built.getValue(refId(2)) as ProductDefinition
                    relatedProductDefinition = built.getValue(refId(3)) as ProductDefinition
                    referenceDesignator = str(4)
                }
            Part21EntityKind.APPROVAL ->
                approval(str(0)) {
                    level = str(1)
                    authorizedBy = built.getValue(refId(2)) as PersonAndOrganization
                }
        }
    }
}
