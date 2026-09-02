package dev.kstep.step21

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
import java.util.IdentityHashMap

/**
 * Serializes a graph of already-validated `kstep-core` AP242 V1 instances (plus, as of kSTEP M2
 * Welle 10, the six support entity types the codegen-generated shapes now require —
 * `application_context`/`product_context`/`product_definition_context`/`approval_status`/
 * `person`/`organization`), reachable from one or more `roots`, into a complete, syntactically
 * valid ISO 10303-21 physical exchange file.
 *
 * As of kSTEP Geometrie Welle 4 (see ADR-0009), [write] is implemented as [emit] (object-graph
 * walk + numbering, no text) followed by [Part21Renderer.render] (the one text-serialization
 * path also used by [Part21Document.render]) — this refactor produces byte-identical output to
 * before (see `Part21WriterTest`'s exact-string assertions, unchanged).
 */
object Part21Writer {
    private const val MAX_WRITE_GRAPH_DEPTH = 64

    fun write(
        header: Part21Header,
        roots: List<Any>,
    ): String {
        val emitted = emit(roots, startId = 1)
        return Part21Renderer.render(header, emitted.instances)
    }

    fun write(
        header: Part21Header,
        vararg roots: Any,
    ): String = write(header, roots.toList())

    /**
     * Walks the reachable object graph from [roots] (same post-order-DFS/identity-dedup
     * traversal [write] has always used — see [visit]'s KDoc) and numbers it starting at
     * [startId], WITHOUT rendering to text. This is the one numbering path [write] and
     * `kstep-shape`'s AP242 merge (see ADR-0009) both build on, so the two cannot drift apart.
     */
    fun emit(
        roots: List<Any>,
        startId: Int = 1,
    ): Part21EmitResult {
        val identityMap = IdentityHashMap<Any, Int>()
        val order = mutableListOf<Any>()
        for (root in roots) {
            visit(root, 0, identityMap, order)
        }

        val offset = startId - 1
        val finalIdentity = IdentityHashMap<Any, Int>()
        for (instance in order) {
            finalIdentity[instance] = identityMap.getValue(instance) + offset
        }

        val simpleInstances =
            order.map { instance ->
                Part21SimpleInstance(
                    id = finalIdentity.getValue(instance),
                    entityName = entityKindOf(instance).entityName,
                    args = valueArgs(instance, finalIdentity),
                )
            }
        return Part21EmitResult(simpleInstances, offset + order.size + 1, finalIdentity)
    }

    // Post-order DFS reachability walk: every referenced instance is fully discovered (and
    // numbered, in the caller-facing DATA section pass below) before the instance referencing
    // it, matching the human-readable convention illustrative Part-21 files typically follow
    // (not spec-mandated, but deterministic and reproducible across repeated calls on the same
    // object graph and root order). Dedup key is object identity (IdentityHashMap), never
    // equals()/hashCode() — two structurally-equal-but-distinct instances must get two
    // distinct #N, only the literal same object reused across multiple referencing sites
    // collapses to one. Depth-capped (not converted to an explicit work-stack) because the
    // twelve V1+support types have a true max reference-chain depth of 6 (application_context
    // -> product_context -> product -> product_definition_formation -> product_definition ->
    // next_assembly_usage_occurrence) — MAX_WRITE_GRAPH_DEPTH=64 is defense-in-depth headroom,
    // not a realistic limit, so native recursion cannot practically overflow the JVM stack here.
    private fun visit(
        instance: Any,
        depth: Int,
        identityMap: IdentityHashMap<Any, Int>,
        order: MutableList<Any>,
    ) {
        if (identityMap.containsKey(instance)) return
        if (depth >= MAX_WRITE_GRAPH_DEPTH) {
            throw Part21LimitExceededException(
                "write graph depth exceeds the maximum supported depth of $MAX_WRITE_GRAPH_DEPTH",
            )
        }
        for (ref in referencesOf(instance)) {
            visit(ref, depth + 1, identityMap, order)
        }
        if (!identityMap.containsKey(instance)) {
            identityMap[instance] = order.size + 1
            order += instance
        }
    }

    private fun referencesOf(instance: Any): List<Any> =
        when (instance) {
            is ApplicationContext -> emptyList()
            is ApprovalStatus -> emptyList()
            is Person -> emptyList()
            is Organization -> emptyList()
            is ProductContext -> listOf(instance.frameOfReference)
            is ProductDefinitionContext -> listOf(instance.frameOfReference)
            is Product -> instance.frameOfReference.toList()
            is ProductDefinitionFormation -> listOf(instance.ofProduct)
            is ProductDefinition -> listOf(instance.formation, instance.frameOfReference)
            is NextAssemblyUsageOccurrence ->
                listOf(instance.relatingProductDefinition, instance.relatedProductDefinition)
            is Approval -> listOf(instance.status)
            is PersonAndOrganization -> listOf(instance.thePerson, instance.theOrganization)
            else -> unsupportedInstanceType(instance)
        }

    private fun entityKindOf(instance: Any): Part21EntityKind =
        when (instance) {
            is ApplicationContext -> Part21EntityKind.APPLICATION_CONTEXT
            is ProductContext -> Part21EntityKind.PRODUCT_CONTEXT
            is ProductDefinitionContext -> Part21EntityKind.PRODUCT_DEFINITION_CONTEXT
            is ApprovalStatus -> Part21EntityKind.APPROVAL_STATUS
            is Person -> Part21EntityKind.PERSON
            is Organization -> Part21EntityKind.ORGANIZATION
            is Product -> Part21EntityKind.PRODUCT
            is ProductDefinitionFormation -> Part21EntityKind.PRODUCT_DEFINITION_FORMATION
            is ProductDefinition -> Part21EntityKind.PRODUCT_DEFINITION
            is NextAssemblyUsageOccurrence -> Part21EntityKind.NEXT_ASSEMBLY_USAGE_OCCURRENCE
            is Approval -> Part21EntityKind.APPROVAL
            is PersonAndOrganization -> Part21EntityKind.PERSON_AND_ORGANIZATION
            else -> unsupportedInstanceType(instance)
        }

    // Structured equivalent of the pre-Welle-4 string-producing `writeArgs`: same per-type
    // attribute order, but builds Part21Value data instead of pre-rendered text — rendering
    // (including all quoting/encoding checks) is Part21Renderer's job alone now.
    private fun valueArgs(
        instance: Any,
        identityMap: IdentityHashMap<Any, Int>,
    ): List<Part21Value> {
        fun ref(target: Any) = Part21Value.Ref(identityMap.getValue(target))

        fun refList(targets: Collection<Any>) = Part21Value.ListValue(targets.map { ref(it) })

        fun str(text: String) = Part21Value.Str(text)

        fun strOrUnset(text: String?): Part21Value = if (text == null) Part21Value.Unset else Part21Value.Str(text)

        fun strListOrUnset(items: List<String>?): Part21Value =
            if (items == null) Part21Value.Unset else Part21Value.ListValue(items.map { Part21Value.Str(it) })

        return when (instance) {
            is ApplicationContext -> listOf(str(instance.application))
            is ProductContext ->
                listOf(str(instance.name), ref(instance.frameOfReference), str(instance.disciplineType))
            is ProductDefinitionContext ->
                listOf(str(instance.name), ref(instance.frameOfReference), str(instance.lifeCycleStage))
            is ApprovalStatus -> listOf(str(instance.name))
            is Person ->
                listOf(
                    str(instance.id),
                    strOrUnset(instance.lastName),
                    strOrUnset(instance.firstName),
                    strListOrUnset(instance.middleNames),
                    strListOrUnset(instance.prefixTitles),
                    strListOrUnset(instance.suffixTitles),
                )
            is Organization ->
                listOf(strOrUnset(instance.id), str(instance.name), strOrUnset(instance.description))
            is Product ->
                listOf(
                    str(instance.id),
                    str(instance.name),
                    strOrUnset(instance.description),
                    refList(instance.frameOfReference),
                )
            is ProductDefinitionFormation ->
                listOf(str(instance.id), strOrUnset(instance.description), ref(instance.ofProduct))
            is ProductDefinition ->
                listOf(
                    str(instance.id),
                    strOrUnset(instance.description),
                    ref(instance.formation),
                    ref(instance.frameOfReference),
                )
            is NextAssemblyUsageOccurrence ->
                listOf(
                    str(instance.id),
                    str(instance.name),
                    strOrUnset(instance.description),
                    ref(instance.relatingProductDefinition),
                    ref(instance.relatedProductDefinition),
                    strOrUnset(instance.referenceDesignator),
                )
            is Approval -> listOf(ref(instance.status), str(instance.level))
            is PersonAndOrganization -> listOf(ref(instance.thePerson), ref(instance.theOrganization))
            else -> unsupportedInstanceType(instance)
        }
    }

    private fun unsupportedInstanceType(instance: Any): Nothing =
        throw Part21WriteException(
            "object of type '${instance::class.qualifiedName}' reachable from the writer's roots is not " +
                "one of the twelve supported kstep-core AP242 V1/support entity types",
        )
}

/**
 * The result of [Part21Writer.emit]: the numbered [instances] (ready for [Part21Renderer.render]
 * or further merging — see `dev.kstep.shape.Ap242ShapeExporter`), [nextId] (the first `#N` not
 * used by this emission, for a caller appending more instances afterward), and an identity-based
 * [idOf] lookup back from a specific object in the original `roots` graph to its assigned `#N`.
 */
class Part21EmitResult internal constructor(
    val instances: List<Part21SimpleInstance>,
    val nextId: Int,
    private val identity: IdentityHashMap<Any, Int>,
) {
    /**
     * The `#N` assigned to this exact object instance. Identity-based (`IdentityHashMap`), not
     * `equals()`-based — mirrors [Part21Writer.visit]'s own dedup key, so two structurally-equal
     * but distinct objects reliably resolve to their own, distinct `#N` rather than being
     * silently conflated (see ADR-0009 §8 stolperfalle 7).
     *
     * @throws NoSuchElementException if [instance] was not reachable from the `roots` passed to [Part21Writer.emit].
     */
    fun idOf(instance: Any): Int = identity.getValue(instance)
}
