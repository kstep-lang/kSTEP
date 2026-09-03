package dev.kstep.geometry.feature

/**
 * An ordered, replayable list of [Feature]s -- a parametric feature history in its most minimal
 * form: change one value (e.g. `.copy(features = ...)` with a different [FilletFeature.radius]),
 * call [FeatureRebuilder.rebuild] again, get a new result.
 *
 * A pure value holder, not validated in its own constructor: mirrors
 * [dev.kstep.geometry.OcctKernel]'s own established pattern, where e.g. [dev.kstep.geometry.ProfilePoint]
 * does not validate itself and [dev.kstep.geometry.OcctKernel.extrudeProfile] does. Here,
 * [FeatureRebuilder.rebuild] is the single place that validates a sequence, since validity
 * (structural, e.g. `baseIndex` bounds) can only be judged in the context of the whole sequence,
 * not a single [Feature] in isolation.
 */
data class FeatureSequence(
    val features: List<Feature>,
)
