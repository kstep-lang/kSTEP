package dev.kstep.geometry.feature

/**
 * Rewrites every feature index this feature refers to (`baseIndex`, `leftIndex`/`rightIndex`,
 * [TrackedEdges.sourceIndex], [GeneratedEdges.featureIndex]) through [f]. Exhaustive over the sealed
 * hierarchy: a new feature type forces a decision here. An [ExternalRootFeature] is a root and has no indices.
 * Also used to COLLECT the referenced indices (pass an identity lambda that records its argument).
 */
internal fun Feature.remapIndices(f: (Int) -> Int): Feature =
    when (this) {
        is BoxFeature,
        is ExtrudeFeature,
        is CylinderFeature,
        is RevolveFeature,
        is ExtrudeRegionFeature,
        is RevolveRegionFeature,
        is ExtrudeRegionsFeature,
        is RevolveRegionsFeature,
        is ExternalRootFeature,
        -> this
        is FilletFeature -> copy(baseIndex = f(baseIndex))
        is ChamferFeature -> copy(baseIndex = f(baseIndex))
        is TranslateFeature -> copy(baseIndex = f(baseIndex))
        is MirrorFeature -> copy(baseIndex = f(baseIndex))
        is ScaleFeature -> copy(baseIndex = f(baseIndex))
        // FaceReference carries FACE indices of the base shape, not feature indices: nothing to remap.
        is ShellFeature -> copy(baseIndex = f(baseIndex))
        is HoleFeature -> copy(baseIndex = f(baseIndex))
        is PocketFeature -> copy(baseIndex = f(baseIndex))
        is DraftFeature -> copy(baseIndex = f(baseIndex))
        is ThickenFeature -> copy(baseIndex = f(baseIndex))
        is BooleanFeature -> copy(leftIndex = f(leftIndex), rightIndex = f(rightIndex))
        is SelectedFilletFeature -> copy(baseIndex = f(baseIndex), edges = edges.remapIndices(f))
        is SelectedChamferFeature -> copy(baseIndex = f(baseIndex), edges = edges.remapIndices(f))
    }

internal fun EdgeReference.remapIndices(f: (Int) -> Int): EdgeReference =
    when (this) {
        is SelectEdges -> this
        is TrackedEdges -> copy(sourceIndex = f(sourceIndex))
        is GeneratedEdges -> copy(featureIndex = f(featureIndex))
    }

/** Every feature index this feature refers to, in order of appearance. */
internal fun Feature.referencedIndices(): List<Int> {
    val out = ArrayList<Int>()
    remapIndices {
        out.add(it)
        it
    }
    return out
}
