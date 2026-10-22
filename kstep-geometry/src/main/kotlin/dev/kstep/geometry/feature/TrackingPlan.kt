package dev.kstep.geometry.feature

import dev.kstep.geometry.GenerationSource
import dev.kstep.geometry.OcctKernel

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
        require(reference.sources.toSet().size == reference.sources.size) {
            "feature[$consumer] GeneratedEdges contains duplicate sources"
        }
        val source = features[generator]
        val supported =
            when (source) {
                is ExtrudeFeature, is RevolveFeature, is ExtrudeRegionFeature, is RevolveRegionFeature -> true
                is ExternalRootFeature -> source.supportsGeneratedNaming
                else -> false
            }
        require(supported) {
            val name = source::class.simpleName
            val why =
                if (source is ExtrudeRegionsFeature || source is RevolveRegionsFeature) {
                    "multi-body regions cannot name generated edges yet (ADR-0033)"
                } else {
                    "it generates no named profile edges (only extrude/revolve of a profile or region, and external " +
                        "features that support naming, can be generators)"
                }
            "feature[$consumer] GeneratedEdges.featureIndex $generator ($name) cannot be a generator: $why"
        }
        naming.getOrPut(generator) { LinkedHashSet() }.addAll(reference.sources)
        if (generator < base) {
            val path = findPath(features, consumer, generator, base, "GeneratedEdges.featureIndex")
            steps[consumer] = path
            path.forEach { needsHistory.add(it.nodeIndex) }
        } else {
            steps[consumer] = emptyList()
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
