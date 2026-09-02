package dev.kstep.shape

import dev.kstep.step21.Part21ComplexInstance
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21RawDocument
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value

/**
 * The geometry-only subgraph extracted from a Part-21 document [OcctShape.writeStepFile] wrote,
 * with kSTEP's own product-structure entities discarded — see [Ap242GeometryExtraction.extract].
 */
internal data class GeometrySubgraph(
    /** In original document order — deterministic and reproducible across repeated extractions of the same input. */
    val instances: List<Part21EntityInstance>,
    /** The id of the `(ADVANCED_)*SHAPE_REPRESENTATION` this subgraph's bridge should attach to. */
    val rootRepresentationId: Int,
)

/**
 * Separates the geometry half of an OCCT-written AP242 STEP file (the transitive closure below
 * its `(ADVANCED_)*SHAPE_REPRESENTATION`) from the placeholder product-structure half OCCT also
 * writes (see ADR-0009 §0 — OCCT's own `PRODUCT`/`PRODUCT_DEFINITION`/... are discarded entirely
 * and replaced by kSTEP's validated ones).
 *
 * Verified empirically against real OCCT 7.9.2 output (a box and an extruded-and-filleted
 * solid, see ADR-0009): the geometry closure below `SHAPE_DEFINITION_REPRESENTATION`'s second
 * argument is disjoint from [PRODUCT_STRUCTURE_ENTITY_NAMES] in both fixtures. The check below
 * stays in the code regardless — a future OCCT version must not be trusted to keep that
 * invariant silently.
 */
internal object Ap242GeometryExtraction {
    private const val MAX_CLOSURE_DEPTH = 4096
    private const val MAX_CLOSURE_SIZE = 200_000

    val PRODUCT_STRUCTURE_ENTITY_NAMES: Set<String> =
        setOf(
            "PRODUCT",
            "PRODUCT_DEFINITION",
            "PRODUCT_DEFINITION_FORMATION",
            "PRODUCT_CONTEXT",
            "PRODUCT_DEFINITION_CONTEXT",
            "APPLICATION_CONTEXT",
            "APPLICATION_PROTOCOL_DEFINITION",
            "PRODUCT_DEFINITION_SHAPE",
            "SHAPE_DEFINITION_REPRESENTATION",
            "PRODUCT_RELATED_PRODUCT_CATEGORY",
        )

    val ACCEPTED_REPRESENTATION_NAMES: Set<String> =
        setOf("ADVANCED_BREP_SHAPE_REPRESENTATION", "SHAPE_REPRESENTATION")

    fun extract(document: Part21RawDocument): GeometrySubgraph {
        val byId: Map<Int, Part21EntityInstance> = document.instances.associateBy { it.id }

        val shapeDefinitionReps =
            document.instances.filterIsInstance<Part21SimpleInstance>().filter {
                it.entityName ==
                    "SHAPE_DEFINITION_REPRESENTATION"
            }
        val sdr =
            when (shapeDefinitionReps.size) {
                0 -> throw ShapeExportException(
                    "OCCT wrote no SHAPE_DEFINITION_REPRESENTATION (no shape/product link found)",
                )
                1 -> shapeDefinitionReps.single()
                else ->
                    throw ShapeExportException(
                        "OCCT wrote ${shapeDefinitionReps.size} SHAPE_DEFINITION_REPRESENTATION instances " +
                            "(expected exactly 1) — unexpected multi-shape output",
                    )
            }

        if (sdr.args.size != 2 || sdr.args[1] !is Part21Value.Ref) {
            throw ShapeExportException(
                "SHAPE_DEFINITION_REPRESENTATION #${sdr.id} does not have the expected " +
                    "(property_definition, representation) argument shape",
            )
        }
        val rootId = (sdr.args[1] as Part21Value.Ref).id
        val root =
            byId[rootId]
                ?: throw ShapeExportException(
                    "SHAPE_DEFINITION_REPRESENTATION #${sdr.id} references #$rootId, which is not defined",
                )
        val rootName =
            (root as? Part21SimpleInstance)?.entityName
                ?: throw ShapeExportException(
                    "SHAPE_DEFINITION_REPRESENTATION #${sdr.id}'s used_representation #$rootId is a complex " +
                        "instance, not a simple ${ACCEPTED_REPRESENTATION_NAMES.joinToString(" or ")}",
                )
        if (rootName !in ACCEPTED_REPRESENTATION_NAMES) {
            throw ShapeExportException(
                "SHAPE_DEFINITION_REPRESENTATION #${sdr.id}'s used_representation #$rootId is a " +
                    "$rootName, expected one of ${ACCEPTED_REPRESENTATION_NAMES.joinToString(", ")}",
            )
        }

        val closure = closureOf(rootId, byId)

        val productStructureLeak =
            closure.filter {
                entityNamesOf(it).any { name ->
                    name in
                        PRODUCT_STRUCTURE_ENTITY_NAMES
                }
            }
        if (productStructureLeak.isNotEmpty()) {
            val offender = productStructureLeak.first()
            throw ShapeExportException(
                "the geometry closure below #$rootId unexpectedly reaches product-structure instance " +
                    "#${offender.id} (${entityNamesOf(offender).joinToString("/")}) — refusing to export a file " +
                    "with two competing product structures",
            )
        }

        val hasBrep = closure.any { entityNamesOf(it).contains("MANIFOLD_SOLID_BREP") }
        if (!hasBrep) {
            throw ShapeExportException("the geometry closure below #$rootId contains no MANIFOLD_SOLID_BREP")
        }

        // Original document order, not closure-discovery order — deterministic/reproducible and
        // easier to read back for a human diffing the exported file against OCCT's own.
        val closureIds = closure.mapTo(mutableSetOf()) { it.id }
        val orderedClosure = document.instances.filter { it.id in closureIds }

        return GeometrySubgraph(orderedClosure, rootId)
    }

    // Iterative (explicit work-stack, no native recursion) closure walk — mirrors
    // Part21GraphResolver.topologicalOrder's DoS discipline: a maliciously deep or wide foreign
    // STEP file cannot exhaust the JVM stack or unbounded heap here.
    private fun closureOf(
        rootId: Int,
        byId: Map<Int, Part21EntityInstance>,
    ): List<Part21EntityInstance> {
        val visited = LinkedHashSet<Int>()
        val stack = ArrayDeque<Pair<Int, Int>>() // (id, depth)
        stack.addLast(rootId to 0)
        while (stack.isNotEmpty()) {
            val (id, depth) = stack.removeLast()
            if (!visited.add(id)) continue
            if (visited.size > MAX_CLOSURE_SIZE) {
                throw ShapeExportException(
                    "geometry closure exceeds the maximum supported size of $MAX_CLOSURE_SIZE instances",
                )
            }
            if (depth > MAX_CLOSURE_DEPTH) {
                throw ShapeExportException("geometry closure exceeds the maximum supported depth of $MAX_CLOSURE_DEPTH")
            }
            val instance =
                byId[id] ?: throw ShapeExportException("geometry closure references #$id, which is not defined")
            for (refId in instance.referencedIds()) {
                if (refId !in visited) {
                    stack.addLast(refId to depth + 1)
                }
            }
        }
        return visited.map { byId.getValue(it) }
    }

    private fun entityNamesOf(instance: Part21EntityInstance): Set<String> =
        when (instance) {
            is Part21SimpleInstance -> setOf(instance.entityName)
            is Part21ComplexInstance -> instance.parts.mapTo(mutableSetOf()) { it.entityName }
        }
}
