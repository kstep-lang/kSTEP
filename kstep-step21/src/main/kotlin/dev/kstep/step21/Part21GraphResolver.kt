package dev.kstep.step21

import dev.kstep.core.DslViolation
import dev.kstep.core.ValidationResult
import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.approvalStatus
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.ApprovalStatus
import dev.kstep.generated.ap242v1.Organization
import dev.kstep.generated.ap242v1.Person
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductContext
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionContext
import dev.kstep.generated.ap242v1.ProductDefinitionFormation

/**
 * Pass 2: resolves a [Part21RawDocument] (pass-1 output) into a [Part21ReadResult].
 *
 * Three sub-steps, in this exact order — the order is load-bearing, not incidental:
 *
 * 1. Build the type-agnostic `#N` reference graph, detect dangling references (a `#N` used but
 *    never defined) and reference cycles via an iterative (non-recursive) topological sort.
 *    This step must run *before* step 2, because per-reference target-*type* verification
 *    would otherwise make [Part21CycleException] permanently unreachable dead code: the twelve
 *    real V1+support entity shapes form a strict, acyclic reference DAG, so the only way to
 *    construct a genuinely reachable cycle in a test (or a hostile file) is to cross-wire two
 *    entities whose `REFERENCE`-argument *shape* matches at the same position but whose declared
 *    target *type* doesn't — a case step 2's type check would reject first if it ran first.
 * 2. Per-reference target-entity-type verification, now that every reference is known to
 *    resolve to a real, cycle-free instance. Applies to both single `REFERENCE` positions and
 *    every element of a `REFERENCE_LIST` position.
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
        val edges: Map<Int, List<Int>> = rawById.mapValues { (_, raw) -> referenceIdsOf(raw) }

        checkDangling(rawById, edges)
        val order = topologicalOrder(idOrder, edges)
        checkReferenceTargetTypes(order, rawById)
        return construct(document.header, order, rawById, edges)
    }

    // Every #N a raw instance's arguments mention, single REFERENCE positions and every element
    // of a REFERENCE_LIST position alike — this is the edge set the dangling/cycle/topological
    // passes below all operate on, so a missing or cyclic reference nested inside a
    // REFERENCE_LIST is caught exactly like a single-REFERENCE one.
    private fun referenceIdsOf(raw: Part21RawInstance): List<Int> =
        raw.args.flatMap { arg ->
            when (arg) {
                is Part21Value.Ref -> listOf(arg.id)
                is Part21Value.ListValue -> arg.items.filterIsInstance<Part21Value.Ref>().map { it.id }
                else -> emptyList()
            }
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
                val expectedTarget = kind.referenceTargets[index] ?: return@forEachIndexed
                when (arg) {
                    is Part21Value.Ref -> checkOneReferenceTarget(kind, id, index, expectedTarget, arg.id, rawById)
                    is Part21Value.ListValue ->
                        arg.items.filterIsInstance<Part21Value.Ref>().forEach { ref ->
                            checkOneReferenceTarget(kind, id, index, expectedTarget, ref.id, rawById)
                        }
                    else -> Unit
                }
            }
        }
    }

    private fun checkOneReferenceTarget(
        kind: Part21EntityKind,
        id: Int,
        index: Int,
        expectedTarget: Part21EntityKind,
        refId: Int,
        rawById: Map<Int, Part21RawInstance>,
    ) {
        val actual = rawById.getValue(refId)
        if (actual.entityName != expectedTarget.entityName) {
            throw Part21SyntaxException(
                "${kind.entityName} #$id argument ${index + 1} must reference a " +
                    "${expectedTarget.entityName}, but #$refId is a ${actual.entityName}",
            )
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

        fun strOrNull(index: Int) = (raw.args[index] as? Part21Value.Str)?.text

        fun strList(index: Int): List<String>? =
            when (val arg = raw.args[index]) {
                is Part21Value.ListValue -> arg.items.map { (it as Part21Value.Str).text }
                else -> null
            }

        fun refId(index: Int) = (raw.args[index] as Part21Value.Ref).id

        @Suppress("UNCHECKED_CAST")
        fun <T> refValue(index: Int): T = built.getValue(refId(index)) as T

        @Suppress("UNCHECKED_CAST")
        fun <T> refListValues(index: Int): List<T> =
            (raw.args[index] as Part21Value.ListValue).items.map { item ->
                built.getValue((item as Part21Value.Ref).id) as T
            }

        return when (kind) {
            Part21EntityKind.APPLICATION_CONTEXT ->
                applicationContext { application = str(0) }
            Part21EntityKind.PRODUCT_CONTEXT ->
                productContext {
                    name = str(0)
                    frameOfReference = refValue<ApplicationContext>(1)
                    disciplineType = str(2)
                }
            Part21EntityKind.PRODUCT_DEFINITION_CONTEXT ->
                productDefinitionContext {
                    name = str(0)
                    frameOfReference = refValue<ApplicationContext>(1)
                    lifeCycleStage = str(2)
                }
            Part21EntityKind.APPROVAL_STATUS ->
                approvalStatus { name = str(0) }
            Part21EntityKind.PERSON ->
                person(str(0)) {
                    lastName = strOrNull(1)
                    firstName = strOrNull(2)
                    middleNames = strList(3)
                    prefixTitles = strList(4)
                    suffixTitles = strList(5)
                }
            Part21EntityKind.ORGANIZATION ->
                organization {
                    id = strOrNull(0)
                    name = str(1)
                    description = strOrNull(2)
                }
            Part21EntityKind.PRODUCT ->
                product(str(0)) {
                    name = str(1)
                    description = strOrNull(2)
                    frameOfReference = refListValues<ProductContext>(3).toSet()
                }
            Part21EntityKind.PRODUCT_DEFINITION_FORMATION ->
                productDefinitionFormation(str(0)) {
                    description = strOrNull(1)
                    ofProduct = refValue<Product>(2)
                }
            Part21EntityKind.PRODUCT_DEFINITION ->
                productDefinition(str(0)) {
                    description = strOrNull(1)
                    formation = refValue<ProductDefinitionFormation>(2)
                    frameOfReference = refValue<ProductDefinitionContext>(3)
                }
            Part21EntityKind.NEXT_ASSEMBLY_USAGE_OCCURRENCE ->
                nextAssemblyUsageOccurrence(str(0)) {
                    name = str(1)
                    description = strOrNull(2)
                    relatingProductDefinition = refValue<ProductDefinition>(3)
                    relatedProductDefinition = refValue<ProductDefinition>(4)
                    referenceDesignator = strOrNull(5)
                }
            Part21EntityKind.APPROVAL ->
                approval {
                    status = refValue<ApprovalStatus>(0)
                    level = str(1)
                }
            Part21EntityKind.PERSON_AND_ORGANIZATION ->
                personAndOrganization {
                    thePerson = refValue<Person>(0)
                    theOrganization = refValue<Organization>(1)
                }
        }
    }
}
