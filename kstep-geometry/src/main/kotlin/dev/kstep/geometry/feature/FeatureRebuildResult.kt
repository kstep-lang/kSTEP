package dev.kstep.geometry.feature

import dev.kstep.geometry.OcctShape

/**
 * The outcome of a successful [FeatureRebuilder.rebuild]: every [OcctShape] built along the way,
 * parallel-indexed to the [FeatureSequence.features] that produced them ([shapes]`[i]` is the
 * shape built from `features[i]`).
 *
 * [AutoCloseable]: closes ALL of [shapes], not just [final] -- an intermediate shape (e.g. the
 * un-filleted extrude result a later [FilletFeature] was built from) is just as real and
 * native-handle-owning as the final one, and leaking it would be exactly the kind of native
 * resource leak [dev.kstep.geometry.OcctShape]'s own KDoc warns against.
 */
class FeatureRebuildResult(
    val shapes: List<OcctShape>,
) : AutoCloseable {
    /** The shape built from the last [Feature] in the sequence -- the rebuild's end result. */
    val final: OcctShape get() = shapes.last()

    override fun close() {
        shapes.forEach { it.close() }
    }
}
