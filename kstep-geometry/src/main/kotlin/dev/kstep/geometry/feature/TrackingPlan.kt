package dev.kstep.geometry.feature

import dev.kstep.geometry.BlendedEdges
import dev.kstep.geometry.BooleanSection
import dev.kstep.geometry.DerivedGenerationSource
import dev.kstep.geometry.GeneratedRole
import dev.kstep.geometry.GenerationSource
import dev.kstep.geometry.HoleWall
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OperandEdges
import dev.kstep.geometry.OperandFaces
import dev.kstep.geometry.describe
import dev.kstep.geometry.select.EdgeSelection

/** One operation on the path from a tracked edge's source feature to its consumer: feature [nodeIndex], reading the path through its operand [operandSlot]. */
internal class TrackStep(
    val nodeIndex: Int,
    val operandSlot: Int,
)

/**
 * Result of [TrackingPlanner.plan]: for every consuming feature with a [TrackedEdges] reference,
 * the unique operation path from the source feature; and the set of features that must be built
 * with their edge history (so nothing pays for history unless something tracks).
 */
internal class TrackingPlan(
    val stepsByConsumer: Map<Int, List<TrackStep>>,
    val needsHistory: Set<Int>,
    /** Generator feature -> union of the sources its consumers name; those features are built with a generation map. */
    val namingByGenerator: Map<Int, Set<GenerationSource>> = emptyMap(),
)

/** Pure-Kotlin structural planning for [TrackedEdges] and [GeneratedEdges]; runs before any native call. */
internal object TrackingPlanner {
    fun plan(features: List<Feature>): TrackingPlan {
        val steps = HashMap<Int, List<TrackStep>>()
        val needsHistory = HashSet<Int>()
        val naming = HashMap<Int, MutableSet<GenerationSource>>()
        features.forEachIndexed { index, feature ->
            val (reference, base) =
                when (feature) {
                    is SelectedFilletFeature -> feature.edges to feature.baseIndex
                    is SelectedChamferFeature -> feature.edges to feature.baseIndex
                    else -> return@forEachIndexed
                }
            if (reference is TrackedEdges) {
                val path = findPath(features, index, reference.sourceIndex, base, "TrackedEdges.sourceIndex")
                steps[index] = path
                path.forEach { needsHistory.add(it.nodeIndex) }
            } else if (reference is GeneratedEdges) {
                planGenerated(features, index, reference, base, steps, needsHistory, naming)
            }
        }
        return TrackingPlan(steps, needsHistory, naming)
    }

    private fun planGenerated(
        features: List<Feature>,
        consumer: Int,
        reference: GeneratedEdges,
        base: Int,
        steps: MutableMap<Int, List<TrackStep>>,
        needsHistory: MutableSet<Int>,
        naming: MutableMap<Int, MutableSet<GenerationSource>>,
    ) {
        val generator = reference.featureIndex
        require(generator in 0..base) {
            "feature[$consumer] GeneratedEdges.featureIndex $generator must refer to a position within 0..$base " +
                "(the baseIndex or an earlier feature)"
        }
        require(reference.sources.isNotEmpty()) { "feature[$consumer] GeneratedEdges needs at least one source" }
        require(reference.sources.size <= OcctKernel.MAX_NAMING_SOURCES) {
            "feature[$consumer] GeneratedEdges may name at most ${OcctKernel.MAX_NAMING_SOURCES} sources, " +
                "got ${reference.sources.size}"
        }
        // Selector trees first and iteratively: hashing, comparing or describing a hostile deep tree recurses.
        reference.sources.forEach { if (it is OperandEdges) EdgeSelection.validateTree(it.selector) }
        require(reference.sources.toSet().size == reference.sources.size) {
            "feature[$consumer] GeneratedEdges contains duplicate sources"
        }
        val source = features[generator]
        val kind = generatorKind(source)
        require(kind != GeneratorKind.NONE) {
            val name = source::class.simpleName
            val why =
                if (source is ExtrudeRegionsFeature || source is RevolveRegionsFeature) {
                    "multi-body regions cannot name generated edges yet (ADR-0033)"
                } else {
                    "it generates no named edges (only extrude/revolve of a profile or region, external features " +
                        "that support naming, fillet, chamfer, boolean and hole can be generators)"
                }
            "feature[$consumer] GeneratedEdges.featureIndex $generator ($name) cannot be a generator: $why"
        }
        requireSourcesFit(consumer, generator, source, kind, reference)
        naming.getOrPut(generator) { LinkedHashSet() }.addAll(reference.sources)
        if (generator < base) {
            val path = findPath(features, consumer, generator, base, "GeneratedEdges.featureIndex")
            steps[consumer] = path
            path.forEach { needsHistory.add(it.nodeIndex) }
        } else {
            steps[consumer] = emptyList()
        }
    }

    /** What a generator feature produces names for (ADR-0033 sweeps, ADR-0034 derived features). */
    private enum class GeneratorKind { SWEEP, BLEND, BOOLEAN, HOLE, NONE }

    private fun generatorKind(feature: Feature): GeneratorKind =
        when (feature) {
            is ExtrudeFeature, is RevolveFeature, is ExtrudeRegionFeature, is RevolveRegionFeature ->
                GeneratorKind.SWEEP
            is ExternalRootFeature ->
                if (feature.supportsGeneratedNaming) GeneratorKind.SWEEP else GeneratorKind.NONE
            is FilletFeature, is ChamferFeature, is SelectedFilletFeature, is SelectedChamferFeature ->
                GeneratorKind.BLEND
            is BooleanFeature -> GeneratorKind.BOOLEAN
            is HoleFeature -> GeneratorKind.HOLE
            else -> GeneratorKind.NONE
        }

    /**
     * Structural check of the source/role/generator matrix of ADR-0034, before any native call:
     * SIDE/START/END only on sweeps with profile sources; BOUNDARY on fillet/chamfer with [BlendedEdges] /
     * [OperandEdges]; SECTION on booleans and holes with [OperandFaces] / [BooleanSection] / [HoleWall] (the
     * latter on holes only); RIM_ENTRY / RIM_EXIT on holes with exactly [HoleWall].
     */
    private fun requireSourcesFit(
        consumer: Int,
        generator: Int,
        generatorFeature: Feature,
        kind: GeneratorKind,
        reference: GeneratedEdges,
    ) {
        val where = "feature[$consumer] GeneratedEdges.featureIndex $generator (${generatorFeature::class.simpleName})"
        val role = reference.role
        if (kind == GeneratorKind.SWEEP) {
            require(reference.sources.none { it is DerivedGenerationSource }) {
                "$where generates profile edges; its sources must be profile sources, not derived ones"
            }
            require(role == GeneratedRole.SIDE || role == GeneratedRole.START || role == GeneratedRole.END) {
                "$where is a sweep: role $role only fits derived features (sweeps have SIDE, START and END images)"
            }
            return
        }
        require(reference.sources.all { it is DerivedGenerationSource }) {
            "$where is a derived feature: its sources must be BlendedEdges, OperandEdges, OperandFaces, " +
                "BooleanSection or HoleWall, not profile sources"
        }
        reference.sources.forEach { source ->
            val fits =
                when (kind) {
                    GeneratorKind.BLEND -> source == BlendedEdges || (source is OperandEdges && source.operand == 0)
                    GeneratorKind.BOOLEAN ->
                        source == BooleanSection ||
                            (source is OperandFaces && source.operand in 0..1)
                    GeneratorKind.HOLE ->
                        source == BooleanSection ||
                            source == HoleWall ||
                            (source is OperandFaces && source.operand in 0..1)
                    else -> false
                }
            require(fits) { "$where cannot be named by ${source.describe()}" }
        }
        val roleFits =
            when (kind) {
                GeneratorKind.BLEND -> role == GeneratedRole.BOUNDARY
                GeneratorKind.BOOLEAN -> role == GeneratedRole.SECTION
                GeneratorKind.HOLE ->
                    role == GeneratedRole.SECTION ||
                        (
                            (role == GeneratedRole.RIM_ENTRY || role == GeneratedRole.RIM_EXIT) &&
                                reference.sources == listOf<GenerationSource>(HoleWall)
                        )
                else -> false
            }
        require(roleFits) {
            val expected =
                when (kind) {
                    GeneratorKind.BLEND -> "BOUNDARY"
                    GeneratorKind.BOOLEAN -> "SECTION"
                    else -> "SECTION, or RIM_ENTRY / RIM_EXIT with exactly the source HoleWall"
                }
            "$where does not support role $role; expected $expected"
        }
    }

    private fun findPath(
        features: List<Feature>,
        consumer: Int,
        source: Int,
        base: Int,
        what: String,
    ): List<TrackStep> {
        require(source in 0 until base) {
            "feature[$consumer] $what $source must refer to a position before baseIndex $base"
        }
        // Number of distinct operation paths from `source` to each later feature, capped at 2.
        val paths = IntArray(features.size)
        paths[source] = 1
        for (i in source + 1..base) {
            val derived = features[i] as? DerivedFeature ?: continue
            require(supportsHistory(derived)) { "feature[$i] (${derived::class.simpleName}) cannot be tracked through" }
            var sum = 0
            derived.baseIndices.forEach { b -> if (b in source until i) sum = minOf(2, sum + paths[b]) }
            paths[i] = sum
        }
        require(paths[base] >= 1) {
            "feature[$consumer] $what $source is not an ancestor of baseIndex $base"
        }
        require(paths[base] == 1) {
            "feature[$consumer] ${what.substringBefore('.')} is ambiguous: more than one operation path leads from " +
                "feature[$source] to feature[$base]"
        }
        val steps = ArrayList<TrackStep>()
        var node = base
        while (node != source) {
            val derived = features[node] as DerivedFeature
            val slot = derived.baseIndices.indexOfFirst { b -> b in source until node && paths[b] >= 1 }
            steps.add(TrackStep(node, slot))
            node = derived.baseIndices[slot]
        }
        steps.reverse()
        return steps
    }

    // Exhaustive over the sealed DerivedFeature hierarchy: adding a new derived feature forces a decision here.
    private fun supportsHistory(feature: DerivedFeature): Boolean =
        when (feature) {
            is FilletFeature,
            is ChamferFeature,
            is SelectedFilletFeature,
            is SelectedChamferFeature,
            is TranslateFeature,
            is BooleanFeature,
            is HoleFeature,
            -> true
        }
}
