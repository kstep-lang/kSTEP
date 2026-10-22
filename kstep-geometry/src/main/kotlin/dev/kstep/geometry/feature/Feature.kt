package dev.kstep.geometry.feature

import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.GeneratedRole
import dev.kstep.geometry.GenerationSource
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileRegion
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.select.EdgeSelector
import dev.kstep.geometry.select.SelectionTolerance

/**
 * One step of a [FeatureSequence]: a value-only description of a single OCCT operation, replayed
 * by [FeatureRebuilder.rebuild] against the existing, unchanged `OcctKernel` API.
 *
 * Two kinds of case: root features that build a shape from scalars ([BoxFeature],
 * [ExtrudeFeature], [CylinderFeature], [RevolveFeature]) and [DerivedFeature]s that consume one or
 * more EARLIER shapes of the same sequence, referenced by index ([FilletFeature],
 * [ChamferFeature], [TranslateFeature], [BooleanFeature], [HoleFeature]). The split exists so
 * [FeatureRebuilder] can validate every referenced index generically (ADR-0015 anticipated it once
 * a second `baseIndex`-carrying feature existed; ADR-0029 introduced it).
 *
 * Every case is a plain data class of `Double`/`Int`/`List`/enum values -- no `OcctShape`, no native
 * handle, nothing that outlives a single JVM process. That is what makes a [FeatureSequence] safe
 * to hold, copy (e.g. `.copy(radius = 8.0)` to change one parameter), and rebuild repeatedly
 * without touching OCCT until [FeatureRebuilder.rebuild] actually runs. (There is deliberately no
 * feature wrapping a `Placement`: that type has no value-`equals`.)
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
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/** A [Feature] that consumes earlier shapes of the same [FeatureSequence], referenced by index. */
sealed interface DerivedFeature : Feature {
    /** Positions (0-based) of the EARLIER features this one reads; each must be strictly less than
     *  this feature's own position -- enforced centrally in [FeatureRebuilder.rebuild]. */
    val baseIndices: List<Int>
}

/** Replays [dev.kstep.geometry.OcctKernel.makeCylinder]. */
data class CylinderFeature(
    val radius: Double,
    val height: Double,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val dirX: Double = 0.0,
    val dirY: Double = 0.0,
    val dirZ: Double = 1.0,
) : Feature

/** Replays [dev.kstep.geometry.OcctKernel.revolveProfile]. */
data class RevolveFeature(
    val profile: List<ProfilePoint>,
    val angleDegrees: Double = 360.0,
    val axis: RevolveAxis = RevolveAxis.Y,
) : Feature

/** Replays [dev.kstep.geometry.OcctKernel.translate] on the shape of feature [baseIndex]. */
data class TranslateFeature(
    val baseIndex: Int,
    val dx: Double,
    val dy: Double,
    val dz: Double,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/** Replays [dev.kstep.geometry.OcctKernel.boolean] on the shapes of features [leftIndex] and [rightIndex]. */
data class BooleanFeature(
    val operation: BooleanOperation,
    val leftIndex: Int,
    val rightIndex: Int,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(leftIndex, rightIndex)
}

/** Replays [dev.kstep.geometry.OcctKernel.hole] on the shape of feature [baseIndex]. */
data class HoleFeature(
    val baseIndex: Int,
    val diameter: Double,
    val depth: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    val dirX: Double = 0.0,
    val dirY: Double = 0.0,
    val dirZ: Double = -1.0,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/** Replays [dev.kstep.geometry.OcctKernel.chamfer]; [edgeIndices] share [FilletFeature]'s opaque addressing. */
data class ChamferFeature(
    val baseIndex: Int,
    val edgeIndices: List<Int>,
    val distance: Double,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/** Replays [dev.kstep.geometry.OcctKernel.extrudeRegion] (Welle L, ADR-0031): a planar region with holes, extruded along +Z. */
data class ExtrudeRegionFeature(
    val region: ProfileRegion,
    val height: Double,
) : Feature

/** Replays [dev.kstep.geometry.OcctKernel.revolveRegion] (Welle L, ADR-0031). */
data class RevolveRegionFeature(
    val region: ProfileRegion,
    val angleDegrees: Double = 360.0,
    val axis: RevolveAxis = RevolveAxis.Y,
) : Feature

/** Replays [dev.kstep.geometry.OcctKernel.extrudeRegions] (Welle M, ADR-0032): several disjoint regions as separate bodies. */
data class ExtrudeRegionsFeature(
    val regions: List<ProfileRegion>,
    val height: Double,
) : Feature

/** Replays [dev.kstep.geometry.OcctKernel.revolveRegions] (Welle M, ADR-0032). */
data class RevolveRegionsFeature(
    val regions: List<ProfileRegion>,
    val angleDegrees: Double = 360.0,
    val axis: RevolveAxis = RevolveAxis.Y,
) : Feature

/**
 * Which edges a [SelectedFilletFeature] / [SelectedChamferFeature] acts on, described
 * geometrically instead of by opaque index (Welle L, ADR-0031).
 */
sealed interface EdgeReference

/**
 * Resolve [selector] against the shape of the feature's own `baseIndex`, afresh on every rebuild.
 * [expected], if set, must equal the number of matched edges.
 */
data class SelectEdges(
    val selector: EdgeSelector,
    val expected: Int? = null,
    val tolerance: SelectionTolerance = SelectionTolerance(),
) : EdgeReference

/**
 * Resolve [selector] against the shape of the EARLIER feature [sourceIndex] (e.g. the original box
 * before a pocket was cut), then follow those edges through every operation between [sourceIndex]
 * and the consuming feature's `baseIndex` using OCCT's own edge history (`Modified`/`IsDeleted`).
 * That keeps an edge addressable by what it WAS even after later operations created look-alike
 * edges. [expected], if set, must equal the number of resulting edges (a split edge maps to all its
 * pieces). Requires exactly one operation path from [sourceIndex] to the consuming `baseIndex`.
 */
data class TrackedEdges(
    val sourceIndex: Int,
    val selector: EdgeSelector,
    val expected: Int? = null,
    val tolerance: SelectionTolerance = SelectionTolerance(),
) : EdgeReference

/**
 * The edges that the EARLIER root feature [featureIndex] GENERATED from the profile [sources] (Welle N, ADR-0033):
 * the vertical edge swept from a corner, or the START / END copy of a profile edge. The name is "generated by
 * feature k from source s" -- the source is the profile definition (for a sketch, a point name), not the
 * geometry, so a parameter change keeps hitting the same logical edges. The edges are followed from
 * [featureIndex] to the consuming feature's `baseIndex` through OCCT's own edge history. Strict: a source
 * without an image, a deleted or lost edge, a wrong [expected] count or a generator that cannot name edges
 * ends in an [dev.kstep.geometry.select.EdgeNamingException] listing candidates -- never a guess.
 *
 * [featureIndex] may equal the consuming feature's `baseIndex` (no step in between); otherwise exactly one
 * operation path must lead from it to `baseIndex`. [within], if set, additionally restricts the result with
 * a geometric selector on the base shape.
 */
data class GeneratedEdges(
    val featureIndex: Int,
    val sources: List<GenerationSource>,
    val role: GeneratedRole = GeneratedRole.SIDE,
    val expected: Int? = null,
    val within: EdgeSelector? = null,
    val tolerance: SelectionTolerance = SelectionTolerance(),
) : EdgeReference

/** Like [FilletFeature], but the edges are chosen by an [EdgeReference] instead of by index. */
data class SelectedFilletFeature(
    val baseIndex: Int,
    val edges: EdgeReference,
    val radius: Double,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/** Like [ChamferFeature], but the edges are chosen by an [EdgeReference] instead of by index. */
data class SelectedChamferFeature(
    val baseIndex: Int,
    val edges: EdgeReference,
    val distance: Double,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}
