package dev.kstep.geometry.feature

import dev.kstep.geometry.ProfilePoint

/**
 * One step of a [FeatureSequence]: a value-only description of a single OCCT operation, replayed
 * by [FeatureRebuilder.rebuild] against the existing, unchanged `OcctKernel` API.
 *
 * Deliberately just three concrete cases, one per `OcctKernel` operation this wave replays
 * ([dev.kstep.geometry.OcctKernel.makeBox], [dev.kstep.geometry.OcctKernel.extrudeProfile],
 * [dev.kstep.geometry.OcctKernel.fillet]) -- no `RootFeature`/`DerivedFeature` marker interface.
 * That split would earn its keep once a second `baseIndex`-carrying feature exists (e.g. chamfer,
 * see ADR-0008's "Geometrie Welle 5a-Nachzügler" row); with only [FilletFeature] doing so today, a
 * direct `is FilletFeature` check in [FeatureRebuilder] is the simpler, honest option.
 *
 * Every case is a plain data class of `Double`/`Int`/`List` values -- no `OcctShape`, no native
 * handle, nothing that outlives a single JVM process. That is what makes a [FeatureSequence] safe
 * to hold, copy (e.g. `.copy(radius = 8.0)` to change one parameter), and rebuild repeatedly
 * without touching OCCT until [FeatureRebuilder.rebuild] actually runs.
 */
sealed interface Feature

/** Replays [dev.kstep.geometry.OcctKernel.makeBox]. */
data class BoxFeature(
    val dx: Double,
    val dy: Double,
    val dz: Double,
) : Feature

/** Replays [dev.kstep.geometry.OcctKernel.extrudeProfile]. */
data class ExtrudeFeature(
    val profile: List<ProfilePoint>,
    val height: Double,
) : Feature

/**
 * Replays [dev.kstep.geometry.OcctKernel.fillet] against the shape produced by an earlier feature
 * in the same [FeatureSequence].
 *
 * @property baseIndex Position (0-based) of an EARLIER [Feature] in the same [FeatureSequence],
 *   whose rebuilt shape is filleted -- referenced by index, never by shape/handle, so the sequence
 *   stays a pure, previous-OCCT-state-free value list. Must be strictly less than this feature's
 *   own position in the sequence; enforced centrally in [FeatureRebuilder.rebuild], not here.
 * @property edgeIndices 0-based edge indices into `baseIndex`'s shape, in the same opaque,
 *   construction-order-dependent ordering [dev.kstep.geometry.OcctKernel.fillet] itself uses. This
 *   wave inherits that limitation as-is -- see [dev.kstep.geometry.OcctKernel.fillet]'s KDoc and
 *   ADR-0008's "Geometrie Welle 5b" row: a stable, geometric edge identity remains unsolved.
 */
data class FilletFeature(
    val baseIndex: Int,
    val edgeIndices: List<Int>,
    val radius: Double,
) : Feature
