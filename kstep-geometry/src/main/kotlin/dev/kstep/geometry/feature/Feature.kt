package dev.kstep.geometry.feature

import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.GeneratedRole
import dev.kstep.geometry.GenerationSource
import dev.kstep.geometry.NeutralPlane
import dev.kstep.geometry.PocketDepth
import dev.kstep.geometry.PocketProfile
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileRegion
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.ShellDirection
import dev.kstep.geometry.select.EdgeSelector
import dev.kstep.geometry.select.FaceSelector
import dev.kstep.geometry.select.SelectionTolerance

/**
 * One step of a [FeatureSequence]: a value-only description of a single OCCT operation, replayed
 * by [FeatureRebuilder.rebuild] against the existing, unchanged `OcctKernel` API.
 *
 * Two kinds of case: root features that build a shape from scalars ([BoxFeature],
 * [ExtrudeFeature], [CylinderFeature], [RevolveFeature]) and [DerivedFeature]s that consume one or
 * more EARLIER shapes of the same sequence, referenced by index ([FilletFeature],
 * [ChamferFeature], [TranslateFeature], [MirrorFeature], [ScaleFeature], [ShellFeature], [PocketFeature],
 * [DraftFeature], [ThickenFeature], [BooleanFeature], [HoleFeature]). The split exists so
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

/**
 * Replays [dev.kstep.geometry.OcctKernel.mirror] (Welle P, ADR-0036): a mirrored COPY of feature [baseIndex],
 * reflected in the plane through ([originX], [originY], [originZ]) with the normal ([normalX], [normalY], [normalZ]).
 * The defaults describe the plane x = 0 (normal +X), so `MirrorFeature(0)` mirrors in the YZ plane.
 */
data class MirrorFeature(
    val baseIndex: Int,
    val originX: Double = 0.0,
    val originY: Double = 0.0,
    val originZ: Double = 0.0,
    val normalX: Double = 1.0,
    val normalY: Double = 0.0,
    val normalZ: Double = 0.0,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/**
 * Replays [dev.kstep.geometry.OcctKernel.scale] (Welle P, ADR-0036): a COPY of feature [baseIndex] uniformly scaled
 * by [factor] about ([aboutX], [aboutY], [aboutZ]) (default: the origin).
 */
data class ScaleFeature(
    val baseIndex: Int,
    val factor: Double,
    val aboutX: Double = 0.0,
    val aboutY: Double = 0.0,
    val aboutZ: Double = 0.0,
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
 * Since Welle N2 (ADR-0034) [featureIndex] may also point to a DERIVED generator: a fillet or chamfer (sources
 * `BlendedEdges`, `OperandEdges`, role `BOUNDARY`), a boolean (`BooleanSection`, `OperandFaces`, role `SECTION`) or a hole
 * (additionally `HoleWall` with the roles `SECTION`, `RIM_ENTRY`, `RIM_EXIT`). The combination of generator, sources and
 * role is checked before any native call. The signature is unchanged, so `SelectEdges`, `TrackedEdges` and the index
 * API keep working.
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

/**
 * Which faces a [ShellFeature] opens, described geometrically instead of by opaque index (Welle Q, ADR-0037).
 */
sealed interface FaceReference

/**
 * Resolve [selector] against the shape of the feature's own `baseIndex`, afresh on every rebuild. [expected], if
 * set, must equal the number of matched faces; an empty or ambiguous match is an error listing the candidates.
 */
data class SelectFaces(
    val selector: FaceSelector,
    val expected: Int? = null,
    val tolerance: SelectionTolerance = SelectionTolerance(),
) : FaceReference

/**
 * Open exactly the faces with these 0-based FACE indices of the base shape (`MapShapes(TopAbs_FACE)` order, the
 * order of `OcctShape.faces`) -- the index API's counterpart of [SelectFaces]. These are face indices of the base
 * shape, NOT feature indices: [FeatureIndexRemap] never rewrites them.
 */
data class FaceIndices(
    val indices: List<Int>,
) : FaceReference

/**
 * Replays [dev.kstep.geometry.OcctKernel.shell] (Welle Q, ADR-0037): hollows the shape of feature [baseIndex], opening
 * the faces [openFaces] and growing a wall of [thickness] (> 0) in [direction]. A tracked edge can be followed through
 * an INWARD shell (the original edges stay the edges of the outer skin); an OUTWARD shell cannot be tracked through.
 * A shell is not a generator: the new inner wall has no persistent names yet -- name edges at their generator
 * before the shell.
 */
data class ShellFeature(
    val baseIndex: Int,
    val openFaces: FaceReference,
    val thickness: Double,
    val direction: ShellDirection = ShellDirection.INWARD,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/**
 * Replays [dev.kstep.geometry.OcctKernel.pocket] (Welle Q2, ADR-0040): cuts [profile] to [depth] into the ONE planar
 * face [face] resolves to on the shape of feature [baseIndex]. Resolving to several faces is an error (a pocket never
 * picks the first of a tie); [FaceIndices] must hold exactly one index. A tracked edge can be followed through a
 * pocket; the new pocket walls have no persistent names yet.
 */
data class PocketFeature(
    val baseIndex: Int,
    val face: FaceReference,
    val profile: PocketProfile,
    val depth: PocketDepth,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/**
 * Replays [dev.kstep.geometry.OcctKernel.draft] (Welle Q2, ADR-0040): drafts the planar [faces] of the shape of feature
 * [baseIndex] by [angleDegrees] about [neutralPlane]. A positive angle narrows the material along the pull direction.
 * Edges cannot be tracked through a draft (every edge of a draft is new).
 */
data class DraftFeature(
    val baseIndex: Int,
    val faces: FaceReference,
    val angleDegrees: Double,
    val neutralPlane: NeutralPlane,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}

/**
 * Replays [dev.kstep.geometry.OcctKernel.thicken] (Welle Q2, ADR-0040): pushes the planar [faces] of the shape of
 * feature [baseIndex] outward by [distance] (> 0), extending the neighbours. Edges cannot be tracked through it.
 */
data class ThickenFeature(
    val baseIndex: Int,
    val faces: FaceReference,
    val distance: Double,
) : DerivedFeature {
    override val baseIndices: List<Int> get() = listOf(baseIndex)
}
