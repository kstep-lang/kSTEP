package dev.kstep.shape

import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.style.StepColour
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21RawDocument
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import dev.kstep.step21.referencedIds
import java.util.IdentityHashMap

// Instance colours (ADR-0044): one placed occurrence of a part gets its own colour. Handwritten Part-21 entities, like the
// placement chain of Ap242PlacementBridge, because OCCT's writer cannot be asked for them (ADR-0042). Pure: no OCCT call.

// Welle X (ADR-0046) extends this to occurrences of sub-assemblies: the colour hangs on the usage, the STYLED_ITEMs sit on the
// solids of the leaves below it.

/** What an instance colour is attached to: a part with a shape of its own (ADR-0044) or a sub-assembly without one (ADR-0046). */
internal enum class StyledChildKind { PART, SUBASSEMBLY }

/** One validated instance colour: [usageIndex] is the index of its usage in [AssemblyGeometryPlan.usages]. */
internal data class PlannedUsageStyle(
    val style: UsageStyle,
    val usageIndex: Int,
    val kind: StyledChildKind,
)

/** Writes the entities of [UsageStyle]s and checks its own output against a fixed allow-list. */
internal object Ap242InstanceStyleBridge {
    /** DoS guard: upper bound on instance colours per export (one per geometric usage at most). */
    const val MAX_INSTANCE_STYLES: Int = AssemblyGeometryGraph.MAX_GEOMETRIC_USAGES

    /**
     * Own allow-list (ADR-0044): exactly the entity names this bridge writes. It is deliberately NOT merged into
     * `Ap242GeometryExtraction.STYLE_ENTITY_NAMES`: OCCT never writes `PRESENTATION_STYLE_BY_CONTEXT` for a single part,
     * so the extraction of OCCT's own output stays fail closed against it.
     */
    val INSTANCE_STYLE_ENTITY_NAMES: Set<String> =
        setOf(
            "MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION",
            "STYLED_ITEM",
            "PRESENTATION_STYLE_BY_CONTEXT",
            "SURFACE_STYLE_USAGE",
            "SURFACE_SIDE_STYLE",
            "SURFACE_STYLE_FILL_AREA",
            "FILL_AREA_STYLE",
            "FILL_AREA_STYLE_COLOUR",
            "COLOUR_RGB",
            "SHAPE_REPRESENTATION",
            "SHAPE_DEFINITION_REPRESENTATION",
        )

    /** DoS guard (ADR-0046): upper bound on the STYLED_ITEMs of all sub-assembly occurrences of one export. */
    const val MAX_SUBASSEMBLY_STYLED_ITEMS: Int = 65_536

    /**
     * Own allow-list of the sub-assembly block (ADR-0046). The same eleven names as [INSTANCE_STYLE_ENTITY_NAMES], but a
     * separate set on purpose: any later difference between the two blocks has to be a deliberate change.
     */
    val SUBASSEMBLY_STYLE_ENTITY_NAMES: Set<String> =
        setOf(
            "MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION",
            "STYLED_ITEM",
            "PRESENTATION_STYLE_BY_CONTEXT",
            "SURFACE_STYLE_USAGE",
            "SURFACE_SIDE_STYLE",
            "SURFACE_STYLE_FILL_AREA",
            "FILL_AREA_STYLE",
            "FILL_AREA_STYLE_COLOUR",
            "COLOUR_RGB",
            "SHAPE_REPRESENTATION",
            "SHAPE_DEFINITION_REPRESENTATION",
        )

    /**
     * Validates [styles] against [plan] (fail closed) and returns them with the index of their usage in
     * [AssemblyGeometryPlan.usages], in that order (not in registration order), so the output is deterministic.
     *
     * A child without a shape of its own is a sub-assembly (ADR-0046, [StyledChildKind.SUBASSEMBLY]); a plan usage is only
     * geometric when something with a shape sits below it, so such a child always has leaves.
     *
     * @throws ShapeExportException for a usage that is not a geometric usage of the plan, a second colour for one usage, a
     *   transparent colour, and more than [MAX_INSTANCE_STYLES] colours
     */
    fun plan(
        styles: List<UsageStyle>,
        plan: AssemblyGeometryPlan,
        shapedDefinitions: Collection<ProductDefinition>,
    ): List<PlannedUsageStyle> {
        if (styles.isEmpty()) return emptyList()
        if (styles.size > MAX_INSTANCE_STYLES) {
            throw ShapeExportException("too many instance colours: ${styles.size} (limit $MAX_INSTANCE_STYLES)")
        }
        val indexOf = IdentityHashMap<NextAssemblyUsageOccurrence, Int>()
        for ((i, u) in plan.usages.withIndex()) indexOf[u.usage] = i
        val shaped = IdentityHashMap<ProductDefinition, Boolean>()
        for (d in shapedDefinitions) shaped[d] = true
        val seen = IdentityHashMap<NextAssemblyUsageOccurrence, Boolean>()
        val planned = mutableListOf<PlannedUsageStyle>()
        for (s in styles) {
            val id = s.usage.id
            val index =
                indexOf[s.usage]
                    ?: throw ShapeExportException(
                        "an instance colour refers to assembly usage '$id', which is not registered as a root, " +
                            "does not connect two product definitions, or whose child carries no geometry",
                    )
            if (seen.put(s.usage, true) != null) {
                throw ShapeExportException("assembly usage '$id' has more than one instance colour")
            }
            if (!s.colour.isOpaque) {
                throw ShapeExportException(
                    "instance colours must be opaque (ADR-0044), usage '$id' has alpha ${s.colour.a}",
                )
            }
            val kind =
                if (shaped.containsKey(
                        plan.usages[index].child,
                    )
                ) {
                    StyledChildKind.PART
                } else {
                    StyledChildKind.SUBASSEMBLY
                }
            planned += PlannedUsageStyle(s, index, kind)
        }
        return planned.sortedBy { it.usageIndex }
    }

    /**
     * Builds the entities of the [StyledChildKind.PART] entries of [planned], starting at id [startId]: per coloured usage
     * the colour chain, a one-item `SHAPE_REPRESENTATION` tied to the usage's existing
     * `PRODUCT_DEFINITION_SHAPE('Placement', ...)` by a `SHAPE_DEFINITION_REPRESENTATION` (the pattern OCCT's own writer
     * produces), one `PRESENTATION_STYLE_BY_CONTEXT` and one `STYLED_ITEM` per solid of the child; then one
     * `MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION` for all. The result is verified with [verify] before it is
     * returned. Entries of another kind are ignored (see [buildSubassembly]); the bytes of the part block do not depend on
     * them.
     */
    fun build(
        planned: List<PlannedUsageStyle>,
        refs: List<UsageEntityRefs>,
        startId: Int,
        solidIdsOfChild: (ProductDefinition) -> List<Int>,
        contextIdOf: (representationId: Int) -> Int,
        childOf: (UsageEntityRefs) -> ProductDefinition,
    ): List<Part21EntityInstance> =
        buildBlock(
            planned.filter { it.kind == StyledChildKind.PART },
            refs,
            startId,
            { solidIdsOfChild(it) },
            contextIdOf,
            childOf,
            INSTANCE_STYLE_ENTITY_NAMES,
            Int.MAX_VALUE,
            "the child of assembly usage '%s' has no solid to carry an instance colour",
        )

    /**
     * Builds the block of the [StyledChildKind.SUBASSEMBLY] entries of [planned] (ADR-0046): the same chain per usage as
     * [build], but the `STYLED_ITEM`s sit on the solids of the leaves below the usage ([leafSolidsOf]), and the block has
     * its own `MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION`. Verified against
     * [SUBASSEMBLY_STYLE_ENTITY_NAMES]; the allowed external references are exactly the placement entities, the context and
     * the collected leaf solids.
     *
     * @param maxStyledItems upper bound on the STYLED_ITEMs of the whole block (default
     *   [MAX_SUBASSEMBLY_STYLED_ITEMS]; injectable so that a test does not need thousands of solids)
     * @throws ShapeExportException if a sub-assembly has no solid below it or the item bound is exceeded
     */
    fun buildSubassembly(
        planned: List<PlannedUsageStyle>,
        refs: List<UsageEntityRefs>,
        startId: Int,
        leafSolidsOf: (ProductDefinition) -> List<Int>,
        contextIdOf: (representationId: Int) -> Int,
        childOf: (UsageEntityRefs) -> ProductDefinition,
        maxStyledItems: Int = MAX_SUBASSEMBLY_STYLED_ITEMS,
    ): List<Part21EntityInstance> =
        buildBlock(
            planned.filter { it.kind == StyledChildKind.SUBASSEMBLY },
            refs,
            startId,
            leafSolidsOf,
            contextIdOf,
            childOf,
            SUBASSEMBLY_STYLE_ENTITY_NAMES,
            maxStyledItems,
            "the sub-assembly of assembly usage '%s' has no solid below it to carry an instance colour",
        )

    /**
     * The solids of [child] and of everything below it in [plan], each once, in the order of [AssemblyGeometryPlan.usages]
     * (depth first, parents before their later siblings' subtrees). Iterative with a visited set, so the work is bounded by
     * the number of product definitions of the plan and a deep or diamond-shaped assembly cannot blow the stack or repeat
     * a subtree. [solidsOf] gives the solids of one product definition (empty for one without a shape).
     *
     * @throws ShapeExportException if there is no solid at all
     */
    internal fun leafSolidsBelow(
        child: ProductDefinition,
        plan: AssemblyGeometryPlan,
        solidsOf: (ProductDefinition) -> List<Int>,
    ): List<Int> {
        val childrenOf = IdentityHashMap<ProductDefinition, MutableList<ProductDefinition>>()
        for (u in plan.usages) childrenOf.getOrPut(u.parent) { mutableListOf() } += u.child
        val visited = IdentityHashMap<ProductDefinition, Boolean>()
        val found = LinkedHashSet<Int>()
        val stack = ArrayDeque<ProductDefinition>()
        stack.addLast(child)
        while (stack.isNotEmpty()) {
            val pd = stack.removeLast()
            if (visited.put(pd, true) != null) continue
            found += solidsOf(pd)
            for (c in childrenOf[pd].orEmpty().asReversed()) stack.addLast(c)
        }
        if (found.isEmpty()) {
            throw ShapeExportException(
                "the product definition '${child.id}' has no solid below it to carry an instance colour",
            )
        }
        return found.toList()
    }

    private fun buildBlock(
        planned: List<PlannedUsageStyle>,
        refs: List<UsageEntityRefs>,
        startId: Int,
        itemSolidsOf: (ProductDefinition) -> List<Int>,
        contextIdOf: (representationId: Int) -> Int,
        childOf: (UsageEntityRefs) -> ProductDefinition,
        allowList: Set<String>,
        maxStyledItems: Int,
        noSolidMessage: String,
    ): List<Part21EntityInstance> {
        if (planned.isEmpty()) return emptyList()
        val alloc = IdAllocator(startId)
        val out = mutableListOf<Part21EntityInstance>()
        val allowedExternal = linkedSetOf<Int>()
        val styledIds = mutableListOf<Int>()
        var mdgprContext = -1

        fun simple(
            id: Int,
            name: String,
            vararg args: Part21Value,
        ) {
            out += Part21SimpleInstance(id, name, args.toList())
        }

        for ((style, index, _) in planned) {
            val r = refs[index]
            check(r.usage === style.usage) { "usage refs are out of step with the plan" }
            val solids =
                try {
                    itemSolidsOf(childOf(r))
                } catch (e: ShapeExportException) {
                    throw ShapeExportException(noSolidMessage.format(style.usage.id), e)
                }
            if (solids.isEmpty()) throw ShapeExportException(noSolidMessage.format(style.usage.id))
            if (styledIds.size + solids.size > maxStyledItems) {
                throw ShapeExportException("too many styled items for instance colours: limit $maxStyledItems")
            }
            val context = contextIdOf(r.parentRepresentationId)
            if (mdgprContext < 0) mdgprContext = context
            allowedExternal += listOf(r.placementShapeId, r.placementAxisId, context) + solids
            val colour = alloc.next()
            val fillColour = alloc.next()
            val fillStyle = alloc.next()
            val fillArea = alloc.next()
            val sideStyle = alloc.next()
            val usageStyle = alloc.next()
            val representation = alloc.next()
            val definition = alloc.next()
            val byContext = alloc.next()
            val c = style.colour
            simple(
                colour,
                "COLOUR_RGB",
                Part21Value.Str(""),
                Part21Value.Num(stepReal(c.r)),
                Part21Value.Num(stepReal(c.g)),
                Part21Value.Num(stepReal(c.b)),
            )
            simple(fillColour, "FILL_AREA_STYLE_COLOUR", Part21Value.Str(""), Part21Value.Ref(colour))
            simple(
                fillStyle,
                "FILL_AREA_STYLE",
                Part21Value.Str(""),
                Part21Value.ListValue(listOf(Part21Value.Ref(fillColour))),
            )
            simple(fillArea, "SURFACE_STYLE_FILL_AREA", Part21Value.Ref(fillStyle))
            simple(
                sideStyle,
                "SURFACE_SIDE_STYLE",
                Part21Value.Str(""),
                Part21Value.ListValue(listOf(Part21Value.Ref(fillArea))),
            )
            simple(usageStyle, "SURFACE_STYLE_USAGE", Part21Value.Enumeration("BOTH"), Part21Value.Ref(sideStyle))
            simple(
                representation,
                "SHAPE_REPRESENTATION",
                Part21Value.Str(""),
                Part21Value.ListValue(listOf(Part21Value.Ref(r.placementAxisId))),
                Part21Value.Ref(context),
            )
            simple(
                definition,
                "SHAPE_DEFINITION_REPRESENTATION",
                Part21Value.Ref(r.placementShapeId),
                Part21Value.Ref(representation),
            )
            simple(
                byContext,
                "PRESENTATION_STYLE_BY_CONTEXT",
                Part21Value.ListValue(listOf(Part21Value.Ref(usageStyle))),
                Part21Value.Ref(representation),
            )
            for (solid in solids) {
                val styled = alloc.next()
                simple(
                    styled,
                    "STYLED_ITEM",
                    Part21Value.Str("color"),
                    Part21Value.ListValue(listOf(Part21Value.Ref(byContext))),
                    Part21Value.Ref(solid),
                )
                styledIds += styled
            }
        }
        simple(
            alloc.next(),
            "MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION",
            Part21Value.Str(""),
            Part21Value.ListValue(styledIds.map { Part21Value.Ref(it) }),
            Part21Value.Ref(mdgprContext),
        )
        verify(out, allowedExternal, allowList)
        return out
    }

    /**
     * Self-check of [added]: every entity is a simple instance whose name is on [allowList], ids are unique, and every
     * reference points to an entity of [added] itself or to one of the [allowedExternal] ids.
     *
     * @throws ShapeExportException otherwise
     */
    internal fun verify(
        added: List<Part21EntityInstance>,
        allowedExternal: Set<Int>,
        allowList: Set<String> = INSTANCE_STYLE_ENTITY_NAMES,
    ) {
        val own = HashSet<Int>()
        for (e in added) {
            if (!own.add(e.id)) throw ShapeExportException("instance colour entity #${e.id} is defined twice")
        }
        for (e in added) {
            val name = (e as? Part21SimpleInstance)?.entityName
            if (name == null || name !in allowList) {
                throw ShapeExportException(
                    "instance colour entity #${e.id} is not on the allow-list: ${name ?: "complex instance"}",
                )
            }
            for (ref in e.referencedIds()) {
                if (ref !in own && ref !in allowedExternal) {
                    throw ShapeExportException("instance colour entity #${e.id} refers to #$ref, which it may not")
                }
            }
        }
    }
}

/**
 * Reads the instance colours back out of a document written by [Ap242ShapeExporter]: NAUO `id` string to colour. A
 * diagnostic/round-trip tool for the pattern of [Ap242InstanceStyleBridge]; it throws [IllegalArgumentException] for
 * anything else (like [Ap242PlacementReader]).
 */
object Ap242InstanceStyleReader {
    fun read(doc: Part21RawDocument): Map<String, StepColour> {
        val byId = doc.instances.associateBy { it.id }

        fun simple(
            id: Int,
            name: String,
        ): Part21SimpleInstance =
            (byId[id] as? Part21SimpleInstance)?.takeIf { it.entityName == name }
                ?: throw IllegalArgumentException("#$id is not a simple $name")

        fun ref(v: Part21Value): Int =
            (v as? Part21Value.Ref)?.id ?: throw IllegalArgumentException("expected a reference, got $v")

        fun single(v: Part21Value): Int =
            (
                (v as? Part21Value.ListValue)?.items?.singleOrNull()
                    ?: throw IllegalArgumentException("expected a one-item list, got $v")
            ).let(::ref)

        fun num(v: Part21Value): Double =
            (v as? Part21Value.Num)?.lexeme?.toDouble() ?: throw IllegalArgumentException("expected a number, got $v")

        // style context SHAPE_REPRESENTATION -> PRODUCT_DEFINITION_SHAPE
        val usageOfRepresentation = HashMap<Int, Int>()
        for (sdr in doc.instances.filterIsInstance<Part21SimpleInstance>().filter {
            it.entityName ==
                "SHAPE_DEFINITION_REPRESENTATION"
        }) {
            usageOfRepresentation[ref(sdr.args[1])] = ref(sdr.args[0])
        }
        val result = LinkedHashMap<String, StepColour>()
        for (psbc in doc.instances.filterIsInstance<Part21SimpleInstance>().filter {
            it.entityName ==
                "PRESENTATION_STYLE_BY_CONTEXT"
        }) {
            val usageStyle = simple(single(psbc.args[0]), "SURFACE_STYLE_USAGE")
            val side = simple(ref(usageStyle.args[1]), "SURFACE_SIDE_STYLE")
            val fill = simple(ref(simple(single(side.args[1]), "SURFACE_STYLE_FILL_AREA").args[0]), "FILL_AREA_STYLE")
            val fillColour = simple(single(fill.args[1]), "FILL_AREA_STYLE_COLOUR")
            val rgb = simple(ref(fillColour.args[1]), "COLOUR_RGB")
            val colour = StepColour(num(rgb.args[1]), num(rgb.args[2]), num(rgb.args[3]))
            val shapeId =
                usageOfRepresentation[ref(psbc.args[1])]
                    ?: throw IllegalArgumentException(
                        "the context of #${psbc.id} has no SHAPE_DEFINITION_REPRESENTATION",
                    )
            val nauo =
                simple(ref(simple(shapeId, "PRODUCT_DEFINITION_SHAPE").args[2]), "NEXT_ASSEMBLY_USAGE_OCCURRENCE")
            val id = (nauo.args[0] as Part21Value.Str).text
            val previous = result.put(id, colour)
            require(previous == null || previous == colour) { "usage '$id' has two different instance colours" }
        }
        return result
    }
}
