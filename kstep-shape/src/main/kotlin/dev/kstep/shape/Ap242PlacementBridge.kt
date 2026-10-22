package dev.kstep.shape

import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.step21.Part21ComplexInstance
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21InstancePart
import dev.kstep.step21.Part21RawDocument
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import java.util.IdentityHashMap

// Placement of assembly components (ADR-0028): the graph logic that decides which usages get a
// CONTEXT_DEPENDENT_SHAPE_REPRESENTATION, and the handwritten Part-21 entities that carry it.
// Everything here is pure (no OCCT call) so it runs in every CI job.

/** One assembly usage whose child carries geometry, with its (defaulted) placement. */
internal data class GeometricUsage(
    val usage: NextAssemblyUsageOccurrence,
    val parent: ProductDefinition,
    val child: ProductDefinition,
    val placement: RigidPlacement,
)

internal data class AssemblyGeometryPlan(
    /**
     * Every product definition that is "geometric" (carries a shape or contains a geometric child),
     * in deterministic first-appearance order over [usages].
     */
    val members: List<ProductDefinition>,
    /** One entry per geometric usage, in `roots` order. */
    val usages: List<GeometricUsage>,
)

internal object AssemblyGeometryGraph {
    /** DoS guard: upper bound on geometric usages (CDSR instances) per export. */
    const val MAX_GEOMETRIC_USAGES: Int = 4096

    /**
     * Decides which assembly usages get a context-dependent shape representation.
     *
     * Only `NEXT_ASSEMBLY_USAGE_OCCURRENCE` instances registered as roots are considered, and only
     * those whose both ends are plain `product_definition`s. A product definition is geometric when
     * it is in [shapedDefinitions] or has a geometric child; every usage whose child is geometric
     * gets a placement (identity unless [placements] says otherwise).
     *
     * @throws ShapeExportException (fail closed) for a placement on a usage that is not a root, whose
     *   child carries no geometry, or that is placed twice, for a cyclic usage graph, and for more
     *   than [MAX_GEOMETRIC_USAGES] geometric usages.
     */
    fun plan(
        roots: List<Any>,
        shapedDefinitions: Collection<ProductDefinition>,
        placements: List<UsagePlacement>,
    ): AssemblyGeometryPlan {
        val shaped = identitySet(shapedDefinitions)

        val usages = mutableListOf<NextAssemblyUsageOccurrence>()
        val seenUsages = IdentityHashMap<NextAssemblyUsageOccurrence, Boolean>()
        for (r in roots) {
            if (r is NextAssemblyUsageOccurrence && seenUsages.put(r, true) == null) usages += r
        }
        val plain =
            usages.filter {
                it.relatingProductDefinition is ProductDefinition &&
                    it.relatedProductDefinition is ProductDefinition
            }

        // Geometric closure upwards: a parent of a geometric child is geometric (worklist, no recursion).
        val parentsOfChild = IdentityHashMap<ProductDefinition, MutableList<ProductDefinition>>()
        for (u in plain) {
            parentsOfChild.getOrPut(u.related()) { mutableListOf() } += u.relating()
        }
        val geometric = identitySet(shapedDefinitions)
        val work = ArrayDeque<ProductDefinition>(shaped.keys)
        while (work.isNotEmpty()) {
            val pd = work.removeFirst()
            for (parent in parentsOfChild[pd].orEmpty()) {
                if (geometric.put(parent, true) == null) work.addLast(parent)
            }
        }

        val geometricUsages = plain.filter { geometric.containsKey(it.related()) }
        if (geometricUsages.size > MAX_GEOMETRIC_USAGES) {
            throw ShapeExportException(
                "too many geometric assembly usages: ${geometricUsages.size} (limit $MAX_GEOMETRIC_USAGES)",
            )
        }
        rejectCycles(geometricUsages)

        val placementOf = IdentityHashMap<NextAssemblyUsageOccurrence, RigidPlacement>()
        val plainSet = identitySet(plain)
        val geometricSet = identitySet(geometricUsages)
        for (p in placements) {
            val id = p.usage.id
            if (!plainSet.containsKey(p.usage)) {
                throw ShapeExportException(
                    "a placement refers to assembly usage '$id', which is not registered as a root " +
                        "(or does not connect two product definitions)",
                )
            }
            if (!geometricSet.containsKey(p.usage)) {
                throw ShapeExportException(
                    "a placement refers to assembly usage '$id', whose child carries no geometry " +
                        "(no shape is bound to it or to any of its descendants)",
                )
            }
            if (placementOf.put(p.usage, p.placement) != null) {
                throw ShapeExportException("assembly usage '$id' is placed more than once")
            }
        }

        val result =
            geometricUsages.map {
                GeometricUsage(
                    usage = it,
                    parent = it.relating(),
                    child = it.related(),
                    placement = placementOf[it] ?: RigidPlacement.IDENTITY,
                )
            }
        val members = IdentityHashMap<ProductDefinition, Boolean>()
        val ordered = mutableListOf<ProductDefinition>()
        for (u in result) {
            for (pd in listOf(u.parent, u.child)) if (members.put(pd, true) == null) ordered += pd
        }
        return AssemblyGeometryPlan(ordered, result)
    }

    // Iterative three-colour DFS over the geometric usage graph (parent -> child).
    private fun rejectCycles(usages: List<NextAssemblyUsageOccurrence>) {
        val children = IdentityHashMap<ProductDefinition, MutableList<ProductDefinition>>()
        for (u in usages) children.getOrPut(u.relating()) { mutableListOf() } += u.related()
        val state = IdentityHashMap<ProductDefinition, Int>() // 1 = on stack, 2 = done
        for (start in children.keys.toList()) {
            if (state.containsKey(start)) continue
            val stack = ArrayDeque<Pair<ProductDefinition, Iterator<ProductDefinition>>>()
            state[start] = 1
            stack.addLast(start to children[start].orEmpty().iterator())
            while (stack.isNotEmpty()) {
                val (pd, it) = stack.last()
                if (it.hasNext()) {
                    val next = it.next()
                    when (state[next]) {
                        1 -> throw ShapeExportException("assembly usage cycle through '${next.id}'")
                        2 -> Unit
                        else -> {
                            state[next] = 1
                            stack.addLast(next to children[next].orEmpty().iterator())
                        }
                    }
                } else {
                    state[pd] = 2
                    stack.removeLast()
                }
            }
        }
    }

    private fun NextAssemblyUsageOccurrence.relating(): ProductDefinition =
        relatingProductDefinition as ProductDefinition

    private fun NextAssemblyUsageOccurrence.related(): ProductDefinition = relatedProductDefinition as ProductDefinition

    private fun <T : Any> identitySet(items: Collection<T>): IdentityHashMap<T, Boolean> {
        val m = IdentityHashMap<T, Boolean>()
        for (i in items) m[i] = true
        return m
    }
}

/** Overflow-checked id allocator for the entities added after the geometry (instance-id integrity). */
internal class IdAllocator(
    start: Int,
) {
    private var nextId = start
    private var exhausted = false

    fun next(): Int {
        if (exhausted) throw ShapeExportException("placement-entity id allocation overflowed a 32-bit Int")
        val id = nextId
        if (id == Int.MAX_VALUE) exhausted = true else nextId = id + 1
        return id
    }
}

/** Entities written for one placement axis; [axisId] is the `AXIS2_PLACEMENT_3D`. */
internal data class AxisInstances(
    val axisId: Int,
    val instances: List<Part21SimpleInstance>,
)

internal data class PlacementBuildResult(
    /** ABSR instances that gained extra items, keyed by their (unchanged) id. */
    val replaced: Map<Int, Part21SimpleInstance>,
    val added: List<Part21EntityInstance>,
)

internal object Ap242PlacementBridge {
    /**
     * Builds every entity of [plan]. Representation-side convention (verified against OCCT's own
     * `STEPCAFControl_Writer` output, ADR-0028): `rep_1` is the component's representation, `rep_2`
     * the assembly's; `item_1` is an axis in the component's representation, `item_2` the placement
     * axis in the assembly's representation.
     *
     * @param shapedRepresentationId the `(ADVANCED_)SHAPE_REPRESENTATION` id of a shaped product definition
     * @param representation the already-renumbered instance with a given id
     * @param contextId the geometric representation context for synthesized representations (lazy)
     */
    fun build(
        plan: AssemblyGeometryPlan,
        startId: Int,
        shapedRepresentationId: (ProductDefinition) -> Int?,
        representation: (Int) -> Part21EntityInstance,
        contextId: () -> Int,
        productDefinitionId: (ProductDefinition) -> Int,
        usageId: (NextAssemblyUsageOccurrence) -> Int,
    ): PlacementBuildResult {
        val alloc = IdAllocator(startId)
        val added = mutableListOf<Part21EntityInstance>()
        val replaced = LinkedHashMap<Int, Part21SimpleInstance>()

        val usageIndexByParent = IdentityHashMap<ProductDefinition, MutableList<Int>>()
        val childCount = IdentityHashMap<ProductDefinition, Int>()
        for ((i, u) in plan.usages.withIndex()) {
            usageIndexByParent.getOrPut(u.parent) { mutableListOf() } += i
            childCount[u.child] = (childCount[u.child] ?: 0) + 1
        }

        val repOf = IdentityHashMap<ProductDefinition, Int>()
        val originAxisOf = IdentityHashMap<ProductDefinition, Int>()
        val placementAxisOfUsage = IntArray(plan.usages.size)

        for (pd in plan.members) {
            val items = mutableListOf<Int>()
            if (childCount.containsKey(pd)) {
                val axis = axis2Placement3d(alloc, RigidPlacement.IDENTITY)
                added += axis.instances
                originAxisOf[pd] = axis.axisId
                items += axis.axisId
            }
            for (i in usageIndexByParent[pd].orEmpty()) {
                val axis = axis2Placement3d(alloc, plan.usages[i].placement)
                added += axis.instances
                placementAxisOfUsage[i] = axis.axisId
                items += axis.axisId
            }
            val shapedRep = shapedRepresentationId(pd)
            if (shapedRep != null) {
                replaced[shapedRep] = withExtraItems(representation(shapedRep), items)
                repOf[pd] = shapedRep
            } else {
                val rep = alloc.next()
                val pds = alloc.next()
                val sdr = alloc.next()
                added += shapeRepresentation(rep, items, contextId())
                added += Ap242ShapeBridgeEntities.productDefinitionShape(pds, "", productDefinitionId(pd))
                added += Ap242ShapeBridgeEntities.shapeDefinitionRepresentation(sdr, pds, rep)
                repOf[pd] = rep
            }
        }

        for ((i, u) in plan.usages.withIndex()) {
            val pds = alloc.next()
            val idt = alloc.next()
            val srr = alloc.next()
            val cdsr = alloc.next()
            added += nauoShape(pds, usageId(u.usage))
            added += itemDefinedTransformation(idt, originAxisOf.getValue(u.child), placementAxisOfUsage[i])
            added +=
                shapeRepresentationRelationshipWithTransformation(
                    srr,
                    repOf.getValue(u.child),
                    repOf.getValue(u.parent),
                    idt,
                )
            added += contextDependentShapeRepresentation(cdsr, srr, pds)
        }
        return PlacementBuildResult(replaced, added)
    }

    /**
     * `CARTESIAN_POINT` + two `DIRECTION` + `AXIS2_PLACEMENT_3D` for [p]: `location` = translation,
     * `axis` = R * ez, `ref_direction` = R * ex.
     */
    fun axis2Placement3d(
        ids: IdAllocator,
        p: RigidPlacement,
    ): AxisInstances {
        val point = ids.next()
        val axisDir = ids.next()
        val refDir = ids.next()
        val axis = ids.next()

        fun triple(v: Vec3) = Part21Value.ListValue(listOf(real(v.x), real(v.y), real(v.z)))
        return AxisInstances(
            axis,
            listOf(
                Part21SimpleInstance(point, "CARTESIAN_POINT", listOf(Part21Value.Str(""), triple(p.translation))),
                Part21SimpleInstance(axisDir, "DIRECTION", listOf(Part21Value.Str(""), triple(p.rotation.axis))),
                Part21SimpleInstance(refDir, "DIRECTION", listOf(Part21Value.Str(""), triple(p.rotation.refDirection))),
                Part21SimpleInstance(
                    axis,
                    "AXIS2_PLACEMENT_3D",
                    listOf(
                        Part21Value.Str(""),
                        Part21Value.Ref(point),
                        Part21Value.Ref(axisDir),
                        Part21Value.Ref(refDir),
                    ),
                ),
            ),
        )
    }

    private fun real(d: Double) = Part21Value.Num(stepReal(d))

    /** `SHAPE_REPRESENTATION(name, items, context_of_items)` of a part that has no geometry of its own. */
    fun shapeRepresentation(
        id: Int,
        itemIds: List<Int>,
        contextId: Int,
    ): Part21SimpleInstance =
        Part21SimpleInstance(
            id,
            "SHAPE_REPRESENTATION",
            listOf(
                Part21Value.Str(""),
                Part21Value.ListValue(itemIds.map { Part21Value.Ref(it) }),
                Part21Value.Ref(contextId),
            ),
        )

    /** `PRODUCT_DEFINITION_SHAPE` on a `NEXT_ASSEMBLY_USAGE_OCCURRENCE` (OCCT's name/description). */
    fun nauoShape(
        id: Int,
        nauoId: Int,
    ): Part21SimpleInstance =
        Part21SimpleInstance(
            id,
            "PRODUCT_DEFINITION_SHAPE",
            listOf(Part21Value.Str("Placement"), Part21Value.Str("Placement of an item"), Part21Value.Ref(nauoId)),
        )

    fun itemDefinedTransformation(
        id: Int,
        item1: Int,
        item2: Int,
    ): Part21SimpleInstance =
        Part21SimpleInstance(
            id,
            "ITEM_DEFINED_TRANSFORMATION",
            listOf(Part21Value.Str(""), Part21Value.Str(""), Part21Value.Ref(item1), Part21Value.Ref(item2)),
        )

    /**
     * `( REPRESENTATION_RELATIONSHIP(name, description, rep_1, rep_2)
     * REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION(transformation) SHAPE_REPRESENTATION_RELATIONSHIP() )`;
     * parts in alphabetical order as Part 21 requires for complex instances.
     */
    fun shapeRepresentationRelationshipWithTransformation(
        id: Int,
        rep1: Int,
        rep2: Int,
        transformationId: Int,
    ): Part21ComplexInstance =
        Part21ComplexInstance(
            id,
            listOf(
                Part21InstancePart(
                    "REPRESENTATION_RELATIONSHIP",
                    listOf(Part21Value.Str(""), Part21Value.Str(""), Part21Value.Ref(rep1), Part21Value.Ref(rep2)),
                ),
                Part21InstancePart(
                    "REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION",
                    listOf(Part21Value.Ref(transformationId)),
                ),
                Part21InstancePart("SHAPE_REPRESENTATION_RELATIONSHIP", emptyList()),
            ),
        )

    fun contextDependentShapeRepresentation(
        id: Int,
        relationshipId: Int,
        nauoShapeId: Int,
    ): Part21SimpleInstance =
        Part21SimpleInstance(
            id,
            "CONTEXT_DEPENDENT_SHAPE_REPRESENTATION",
            listOf(Part21Value.Ref(relationshipId), Part21Value.Ref(nauoShapeId)),
        )

    private fun withExtraItems(
        rep: Part21EntityInstance,
        extra: List<Int>,
    ): Part21SimpleInstance {
        val simple = rep as? Part21SimpleInstance
        val args = simple?.args
        if (simple == null ||
            args == null ||
            args.size != 3 ||
            args[1] !is Part21Value.ListValue ||
            args[2] !is Part21Value.Ref
        ) {
            throw ShapeExportException(
                "representation #${rep.id} does not have the expected (name, items, context) shape; " +
                    "cannot attach placement axes",
            )
        }
        val items = (args[1] as Part21Value.ListValue).items
        return simple.copy(
            args = listOf(args[0], Part21Value.ListValue(items + extra.map { Part21Value.Ref(it) }), args[2]),
        )
    }
}

/**
 * Reads the component placements back out of a Part-21 document written by [Ap242ShapeExporter]:
 * NAUO `id` string to [RigidPlacement]. A diagnostic/round-trip tool (it proves the written numbers
 * independently of the writer's own data); not a general STEP placement reader -- it understands
 * exactly the entity pattern this module writes and throws [IllegalArgumentException] otherwise.
 */
object Ap242PlacementReader {
    fun read(doc: Part21RawDocument): Map<String, RigidPlacement> {
        val byId = doc.instances.associateBy { it.id }

        fun simple(
            id: Int,
            name: String,
        ): Part21SimpleInstance =
            (byId[id] as? Part21SimpleInstance)?.takeIf { it.entityName == name }
                ?: throw IllegalArgumentException("#$id is not a simple $name")

        fun ref(v: Part21Value): Int =
            (v as? Part21Value.Ref)?.id ?: throw IllegalArgumentException("expected a reference, got $v")

        fun num(v: Part21Value): Double =
            (v as? Part21Value.Num)?.lexeme?.toDouble() ?: throw IllegalArgumentException("expected a number, got $v")

        fun triple(v: Part21Value): Vec3 {
            val items =
                (v as? Part21Value.ListValue)?.items ?: throw IllegalArgumentException("expected a list, got $v")
            require(items.size == 3) { "expected 3 coordinates" }
            return Vec3(num(items[0]), num(items[1]), num(items[2]))
        }

        fun axis(id: Int): RigidPlacement {
            val a = simple(id, "AXIS2_PLACEMENT_3D")
            val loc = triple(simple(ref(a.args[1]), "CARTESIAN_POINT").args[1])
            val z = triple(simple(ref(a.args[2]), "DIRECTION").args[1])
            val x = triple(simple(ref(a.args[3]), "DIRECTION").args[1])
            return RigidPlacement(loc, Rotation.fromAxes(z, x))
        }

        val result = LinkedHashMap<String, RigidPlacement>()
        for (cdsr in doc.instances.filterIsInstance<Part21SimpleInstance>().filter {
            it.entityName == "CONTEXT_DEPENDENT_SHAPE_REPRESENTATION"
        }) {
            val pds = simple(ref(cdsr.args[1]), "PRODUCT_DEFINITION_SHAPE")
            val nauo = simple(ref(pds.args[2]), "NEXT_ASSEMBLY_USAGE_OCCURRENCE")
            val nauoId = (nauo.args[0] as Part21Value.Str).text
            val srr =
                byId[ref(cdsr.args[0])] as? Part21ComplexInstance
                    ?: throw IllegalArgumentException("SRR is not complex")
            val rrwt =
                srr.parts.single { it.entityName == "REPRESENTATION_RELATIONSHIP_WITH_TRANSFORMATION" }
            val idt = simple(ref(rrwt.args[0]), "ITEM_DEFINED_TRANSFORMATION")
            result[nauoId] = axis(ref(idt.args[3]))
        }
        return result
    }
}
