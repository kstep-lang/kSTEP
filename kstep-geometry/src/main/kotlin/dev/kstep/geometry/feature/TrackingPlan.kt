package dev.kstep.geometry.feature

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
)

/** Pure-Kotlin structural planning for [TrackedEdges]; runs before any native call. */
internal object TrackingPlanner {
    fun plan(features: List<Feature>): TrackingPlan {
        val steps = HashMap<Int, List<TrackStep>>()
        val needsHistory = HashSet<Int>()
        features.forEachIndexed { index, feature ->
            val (reference, base) =
                when (feature) {
                    is SelectedFilletFeature -> feature.edges to feature.baseIndex
                    is SelectedChamferFeature -> feature.edges to feature.baseIndex
                    else -> return@forEachIndexed
                }
            if (reference is TrackedEdges) {
                val path = findPath(features, index, reference.sourceIndex, base)
                steps[index] = path
                path.forEach { needsHistory.add(it.nodeIndex) }
            }
        }
        return TrackingPlan(steps, needsHistory)
    }

    private fun findPath(
        features: List<Feature>,
        consumer: Int,
        source: Int,
        base: Int,
    ): List<TrackStep> {
        require(source in 0 until base) {
            "feature[$consumer] TrackedEdges.sourceIndex $source must refer to a position before baseIndex $base"
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
            "feature[$consumer] TrackedEdges.sourceIndex $source is not an ancestor of baseIndex $base"
        }
        require(paths[base] == 1) {
            "feature[$consumer] TrackedEdges is ambiguous: more than one operation path leads from feature[$source] to feature[$base]"
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
