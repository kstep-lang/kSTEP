package dev.kstep.geometry.feature

import dev.kstep.geometry.DerivedNamedShape
import dev.kstep.geometry.EdgeFate
import dev.kstep.geometry.EdgeHistory
import dev.kstep.geometry.GenerationMap
import dev.kstep.geometry.GenerationSource
import dev.kstep.geometry.NamedShape
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.OperandEdges
import dev.kstep.geometry.OperandFaces
import dev.kstep.geometry.TrackedShape
import dev.kstep.geometry.describe
import dev.kstep.geometry.resolveGeneratedImages
import dev.kstep.geometry.select.EdgeNamingException
import dev.kstep.geometry.select.EdgeSelectionException
import dev.kstep.geometry.select.SelectorTree

/**
 * Replays a [FeatureSequence] against the existing, unchanged [OcctKernel] API, producing a fresh
 * [FeatureRebuildResult] each time [rebuild] is called -- the foundation a parametric feature
 * history needs: change one [Feature]'s value, call [rebuild] again, get a new, independently
 * measurable shape.
 *
 * Edges can be addressed by opaque index, geometrically ([SelectEdges], [TrackedEdges], ADR-0031) or by
 * what generated them ([GeneratedEdges], Welle N, ADR-0033). Undo/redo lives in [FeatureHistory]; there is
 * no feature-tree UI and no serialization. See `docs/adr/ADR-0015-parametric-feature-history-foundation.adoc`.
 */
object FeatureRebuilder {
    /**
     * Upper bound on [FeatureSequence.features]' size -- the one genuinely new DoS dimension this
     * wave introduces on top of [OcctKernel]'s own already-DoS-guarded operations (which `rebuild`
     * calls unmodified, so [OcctKernel.MAX_PROFILE_POINTS]/[OcctKernel.MAX_FILLET_INPUT_FACES]/
     * [OcctKernel.MAX_FILLET_EDGES] etc. remain the only per-operation guards): a long enough chain
     * of expensive operations sums to real wall-clock cost even though each individual step is
     * already bounded. 256 is a deliberately generous placeholder -- a hand-written feature
     * history is realistically a few dozen steps at most -- carried over unmeasured from the plan
     * for this wave; see this constant's own KDoc history in
     * `docs/adr/ADR-0015-parametric-feature-history-foundation.adoc` for the actual worst-case
     * measurement this value is pinned against.
     */
    const val MAX_SEQUENCE_LENGTH: Int = 256

    /**
     * Replays every [Feature] in [sequence], in order, against [OcctKernel], and returns all
     * resulting shapes as a [FeatureRebuildResult].
     *
     * Structural validation (empty sequence, [MAX_SEQUENCE_LENGTH], a [DerivedFeature] index
     * that does not refer to a strictly earlier position) runs first, entirely in Kotlin, before
     * any native call -- exactly the "validate before touching native code" discipline
     * [OcctKernel]'s own functions already apply, so a malformed sequence never reaches OCCT at
     * all. Once replay starts, every shape successfully built so far is closed before this
     * function throws on a later failure -- no [OcctShape] is ever left open and unreachable by
     * the caller. That closed-before-throw guarantee is independently observable, not just
     * documented: the thrown [FeatureRebuildException] exposes those same, already-closed shapes
     * via [FeatureRebuildException.closedShapes].
     *
     * @throws IllegalArgumentException if [sequence] is empty, has more than
     *   [MAX_SEQUENCE_LENGTH] features, or contains a [DerivedFeature] whose [DerivedFeature.baseIndices]
     *   do not refer to a strictly earlier position in [sequence] -- surfaces unwrapped, exactly
     *   like [OcctKernel]'s own pre-native validation.
     * @throws FeatureRebuildException if replaying a [Feature] fails against [OcctKernel] -- an
     *   `IllegalArgumentException` (e.g. a [FilletFeature.radius] now outside
     *   [OcctKernel.MIN_FILLET_RADIUS]/[OcctKernel.MAX_FILLET_RADIUS], or an
     *   [FilletFeature.edgeIndices] entry now out of range), an [OcctGeometryException] (e.g. a
     *   radius geometrically too large for the local shape), or -- defensively, not reachable
     *   through this function's own current usage pattern, since every shape built so far is held
     *   strongly and nothing else closes one mid-replay -- an `IllegalStateException` (a
     *   [feature]'s referenced shape turning out to already be closed; see
     *   [OcctKernel.fillet]'s own `@throws IllegalStateException if shape is already closed`).
     *   All three are wrapped uniformly here, unlike [OcctKernel]'s own strict distinction between
     *   `IllegalArgumentException` and [OcctGeometryException] -- see this function's own Decision
     *   section in `docs/adr/ADR-0015-parametric-feature-history-foundation.adoc` for why: in a
     *   sequence, knowing WHICH feature at WHICH position failed matters more than which exception
     *   class OCCT itself used; the original is always still reachable via [Throwable.cause].
     * @throws dev.kstep.geometry.OcctUnavailableException if the native bridge is not available --
     *   propagates unwrapped, exactly like every [OcctKernel] function's own contract: it means
     *   "the bridge itself is missing", not "this feature is invalid".
     */
    fun rebuild(sequence: FeatureSequence): FeatureRebuildResult {
        require(sequence.features.isNotEmpty()) { "sequence must not be empty" }
        val plan = planStructure(sequence)
        val histories = HashMap<Int, EdgeHistory>()
        val namings = HashMap<Int, GenerationMap>()

        val built = ArrayList<OcctShape>(sequence.features.size)
        sequence.features.forEachIndexed { index, feature ->
            val shape =
                try {
                    val generated = plan.namingByGenerator[index]
                    if (generated != null && isDerivedGenerator(feature)) {
                        // A derived generator delivers history AND generation map from one maker (ADR-0034).
                        val derived =
                            buildDerivedNamed(index, sequence.features, built, histories, plan, namings, generated)
                        namings[index] = derived.generation
                        histories[index] = derived.history
                        derived.shape
                    } else if (generated != null) {
                        val named = buildNamed(index, sequence.features[index], generated)
                        namings[index] = named.generation
                        named.shape
                    } else if (index in plan.needsHistory) {
                        val tracked = buildTracked(index, sequence.features, built, histories, plan, namings)
                        histories[index] = tracked.history
                        tracked.shape
                    } else {
                        buildPlain(index, sequence.features, built, histories, plan, namings)
                    }
                } catch (e: IllegalArgumentException) {
                    built.forEach { it.close() }
                    throw wrapFailure(index, feature, built, e)
                } catch (e: OcctGeometryException) {
                    built.forEach { it.close() }
                    throw wrapFailure(index, feature, built, e)
                } catch (e: ExternalFeatureException) {
                    built.forEach { it.close() }
                    throw wrapFailure(index, feature, built, e)
                } catch (e: IllegalStateException) {
                    // Defensive, not reachable through this function's own current usage pattern
                    // (every shape built so far is held strongly in `built`, and nothing else
                    // closes one mid-replay) -- but OcctKernel.fillet's own contract includes
                    // "@throws IllegalStateException if shape is already closed" (raised by
                    // OcctShape.withHandle's checkOpen(), before fillet's own catch-and-wrap logic
                    // ever runs -- see OcctShape.kt), so without this branch a future change that
                    // ever violates the invariant above would both (a) escape this function
                    // unwrapped, contradicting this function's own "wrapped uniformly" KDoc, and
                    // (b) skip the built.forEach { it.close() } cleanup entirely, leaking every
                    // shape built so far. Mirrors the same close-then-wrap handling as the two
                    // catch branches above, so the "no OcctShape is ever left open and unreachable
                    // by the caller" guarantee holds even if that invariant is ever broken.
                    built.forEach { it.close() }
                    throw wrapFailure(index, feature, built, e)
                }
            built.add(shape)
        }
        return FeatureRebuildResult(built)
    }

    /**
     * Pure-Kotlin structural validation of [sequence], without any native call: [MAX_SEQUENCE_LENGTH], every
     * [DerivedFeature] index referring to a strictly earlier position, and the planning of [TrackedEdges] and
     * [GeneratedEdges] (ancestor, unique path, generator support). An EMPTY sequence is valid here ([rebuild]
     * rejects it separately); [FeatureHistory] uses this to check every state before it enters the history.
     *
     * @throws IllegalArgumentException on the first structural problem found.
     */
    fun validateStructure(sequence: FeatureSequence) {
        planStructure(sequence)
    }

    private fun planStructure(sequence: FeatureSequence): TrackingPlan {
        require(sequence.features.size <= MAX_SEQUENCE_LENGTH) {
            "sequence must have at most $MAX_SEQUENCE_LENGTH features, got ${sequence.features.size}"
        }
        sequence.features.forEachIndexed { index, feature ->
            if (feature is DerivedFeature) {
                feature.baseIndices.forEach { base ->
                    require(base in 0 until index) {
                        "feature[$index] baseIndex $base must refer to an earlier " +
                            "position in the sequence (0..${index - 1})"
                    }
                }
            }
        }
        // Selector trees first and iteratively: planning hashes and compares them, which recurses on a hostile deep tree.
        validateSelectorTrees(sequence.features)
        // Structural planning of TrackedEdges / GeneratedEdges (ancestor, unique-path, generator checks).
        return TrackingPlanner.plan(sequence.features)
    }

    /** Bounds every selector tree in [features] (all hierarchies, ADR-0037) before anything hashes or compares them. */
    private fun validateSelectorTrees(features: List<Feature>) {
        fun edges(reference: EdgeReference) {
            when (reference) {
                is SelectEdges -> SelectorTree.validate(reference.selector)
                is TrackedEdges -> SelectorTree.validate(reference.selector)
                is GeneratedEdges -> {
                    reference.within?.let { SelectorTree.validate(it) }
                    reference.sources.forEach {
                        when (it) {
                            is OperandEdges -> SelectorTree.validate(it.selector)
                            is OperandFaces -> SelectorTree.validate(it.selector)
                            else -> Unit
                        }
                    }
                }
            }
        }
        features.forEach { feature ->
            when (feature) {
                is SelectedFilletFeature -> edges(feature.edges)
                is SelectedChamferFeature -> edges(feature.edges)
                is ShellFeature -> (feature.openFaces as? SelectFaces)?.let { SelectorTree.validate(it.selector) }
                is PocketFeature -> (feature.face as? SelectFaces)?.let { SelectorTree.validate(it.selector) }
                is DraftFeature -> (feature.faces as? SelectFaces)?.let { SelectorTree.validate(it.selector) }
                is ThickenFeature -> (feature.faces as? SelectFaces)?.let { SelectorTree.validate(it.selector) }
                else -> Unit
            }
        }
    }

    private fun buildPlain(
        index: Int,
        features: List<Feature>,
        built: List<OcctShape>,
        histories: Map<Int, EdgeHistory>,
        plan: TrackingPlan,
        namings: Map<Int, GenerationMap>,
    ): OcctShape =
        when (val feature = features[index]) {
            is BoxFeature -> OcctKernel.makeBox(feature.dx, feature.dy, feature.dz)
            is ExtrudeFeature -> OcctKernel.extrudeProfile(feature.profile, feature.height)
            is FilletFeature -> OcctKernel.fillet(built[feature.baseIndex], feature.edgeIndices, feature.radius)
            is CylinderFeature ->
                OcctKernel.makeCylinder(
                    feature.radius,
                    feature.height,
                    feature.x,
                    feature.y,
                    feature.z,
                    feature.dirX,
                    feature.dirY,
                    feature.dirZ,
                )
            is RevolveFeature -> OcctKernel.revolveProfile(feature.profile, feature.angleDegrees, feature.axis)
            is TranslateFeature -> OcctKernel.translate(built[feature.baseIndex], feature.dx, feature.dy, feature.dz)
            is MirrorFeature ->
                OcctKernel.mirror(
                    built[feature.baseIndex],
                    feature.originX,
                    feature.originY,
                    feature.originZ,
                    feature.normalX,
                    feature.normalY,
                    feature.normalZ,
                )
            is ScaleFeature ->
                OcctKernel.scale(
                    built[feature.baseIndex],
                    feature.factor,
                    feature.aboutX,
                    feature.aboutY,
                    feature.aboutZ,
                )
            is BooleanFeature ->
                OcctKernel.boolean(
                    feature.operation,
                    built[feature.leftIndex],
                    built[feature.rightIndex],
                )
            is HoleFeature ->
                OcctKernel.hole(
                    built[feature.baseIndex],
                    feature.diameter,
                    feature.depth,
                    feature.x,
                    feature.y,
                    feature.z,
                    feature.dirX,
                    feature.dirY,
                    feature.dirZ,
                )
            is ChamferFeature -> OcctKernel.chamfer(built[feature.baseIndex], feature.edgeIndices, feature.distance)
            is ShellFeature ->
                OcctKernel.shell(
                    built[feature.baseIndex],
                    resolveFaces(feature.openFaces, feature.baseIndex, built),
                    feature.thickness,
                    feature.direction,
                )
            is PocketFeature ->
                OcctKernel.pocket(
                    built[feature.baseIndex],
                    resolvePocketFace(feature.face, feature.baseIndex, built),
                    feature.profile,
                    feature.depth,
                )
            is DraftFeature ->
                OcctKernel.draft(
                    built[feature.baseIndex],
                    resolveFaces(feature.faces, feature.baseIndex, built),
                    feature.angleDegrees,
                    feature.neutralPlane,
                )
            is ThickenFeature ->
                OcctKernel.thicken(
                    built[feature.baseIndex],
                    resolveFaces(feature.faces, feature.baseIndex, built),
                    feature.distance,
                )
            is ExtrudeRegionFeature -> OcctKernel.extrudeRegion(feature.region, feature.height)
            is RevolveRegionFeature -> OcctKernel.revolveRegion(feature.region, feature.angleDegrees, feature.axis)
            is ExtrudeRegionsFeature -> OcctKernel.extrudeRegions(feature.regions, feature.height)
            is RevolveRegionsFeature ->
                OcctKernel.revolveRegions(feature.regions, feature.angleDegrees, feature.axis)
            is ExternalRootFeature -> buildExternal(feature)
            is SelectedFilletFeature ->
                OcctKernel.fillet(
                    built[feature.baseIndex],
                    resolveEdges(index, feature.edges, feature.baseIndex, built, histories, plan, features, namings),
                    feature.radius,
                )
            is SelectedChamferFeature ->
                OcctKernel.chamfer(
                    built[feature.baseIndex],
                    resolveEdges(index, feature.edges, feature.baseIndex, built, histories, plan, features, namings),
                    feature.distance,
                )
        }

    /**
     * Runs an [ExternalRootFeature]. Argument and geometry failures pass through unchanged (they are
     * wrapped by [rebuild] like those of any other feature); any other runtime failure is labelled and
     * wrapped in an [ExternalFeatureException] so that [rebuild] still closes every shape built so far.
     */
    private fun buildExternal(feature: ExternalRootFeature): OcctShape = guardExternal(feature) { feature.buildShape() }

    private fun <T> guardExternal(
        feature: ExternalRootFeature,
        build: () -> T,
    ): T =
        try {
            build()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: OcctGeometryException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: dev.kstep.geometry.OcctUnavailableException) {
            throw e
        } catch (e: RuntimeException) {
            throw ExternalFeatureException("feature '${feature.label}' failed: ${e.message}", e)
        }

    /** Builds a generator feature together with the generation map of the sources its consumers name. */
    private fun buildNamed(
        index: Int,
        feature: Feature,
        sources: Set<GenerationSource>,
    ): NamedShape {
        val list = sources.toList()
        return when (feature) {
            is ExtrudeFeature -> OcctKernel.extrudeProfileNamed(feature.profile, feature.height, list)
            is RevolveFeature ->
                OcctKernel.revolveProfileNamed(
                    feature.profile,
                    feature.angleDegrees,
                    feature.axis,
                    list,
                )
            is ExtrudeRegionFeature -> OcctKernel.extrudeRegionNamed(feature.region, feature.height, list)
            is RevolveRegionFeature ->
                OcctKernel.revolveRegionNamed(feature.region, feature.angleDegrees, feature.axis, list)
            is ExternalRootFeature -> guardExternal(feature) { feature.buildNamedShape(list) }
            else -> error("feature[$index] (${feature::class.simpleName}) cannot be built with a generation map")
        }
    }

    private fun isDerivedGenerator(feature: Feature): Boolean =
        feature is FilletFeature ||
            feature is ChamferFeature ||
            feature is SelectedFilletFeature ||
            feature is SelectedChamferFeature ||
            feature is BooleanFeature ||
            feature is HoleFeature

    /** Builds a derived generator with edge history and the generation map of the sources its consumers name. */
    private fun buildDerivedNamed(
        index: Int,
        features: List<Feature>,
        built: List<OcctShape>,
        histories: Map<Int, EdgeHistory>,
        plan: TrackingPlan,
        namings: Map<Int, GenerationMap>,
        sources: Set<GenerationSource>,
    ): DerivedNamedShape {
        val list = sources.toList()
        return when (val feature = features[index]) {
            is FilletFeature ->
                OcctKernel.filletNamed(
                    built[feature.baseIndex],
                    feature.edgeIndices,
                    feature.radius,
                    list,
                )
            is ChamferFeature ->
                OcctKernel.chamferNamed(built[feature.baseIndex], feature.edgeIndices, feature.distance, list)
            is SelectedFilletFeature ->
                OcctKernel.filletNamed(
                    built[feature.baseIndex],
                    resolveEdges(index, feature.edges, feature.baseIndex, built, histories, plan, features, namings),
                    feature.radius,
                    list,
                )
            is SelectedChamferFeature ->
                OcctKernel.chamferNamed(
                    built[feature.baseIndex],
                    resolveEdges(index, feature.edges, feature.baseIndex, built, histories, plan, features, namings),
                    feature.distance,
                    list,
                )
            is BooleanFeature ->
                OcctKernel.booleanNamed(feature.operation, built[feature.leftIndex], built[feature.rightIndex], list)
            is HoleFeature ->
                OcctKernel.holeNamed(
                    built[feature.baseIndex],
                    feature.diameter,
                    feature.depth,
                    feature.x,
                    feature.y,
                    feature.z,
                    feature.dirX,
                    feature.dirY,
                    feature.dirZ,
                    list,
                )
            else ->
                error(
                    "feature[$index] (${feature::class.simpleName}) cannot be built with a derived generation map",
                )
        }
    }

    /** Builds a feature that lies on a tracked-edge path, keeping the edge history OCCT recorded for it. */
    private fun buildTracked(
        index: Int,
        features: List<Feature>,
        built: List<OcctShape>,
        histories: Map<Int, EdgeHistory>,
        plan: TrackingPlan,
        namings: Map<Int, GenerationMap>,
    ): TrackedShape =
        when (val feature = features[index]) {
            is FilletFeature ->
                OcctKernel.filletWithHistory(
                    built[feature.baseIndex],
                    feature.edgeIndices,
                    feature.radius,
                )
            is ChamferFeature ->
                OcctKernel.chamferWithHistory(
                    built[feature.baseIndex],
                    feature.edgeIndices,
                    feature.distance,
                )
            is TranslateFeature ->
                OcctKernel.translateWithHistory(
                    built[feature.baseIndex],
                    feature.dx,
                    feature.dy,
                    feature.dz,
                )
            is MirrorFeature ->
                OcctKernel.mirrorWithHistory(
                    built[feature.baseIndex],
                    feature.originX,
                    feature.originY,
                    feature.originZ,
                    feature.normalX,
                    feature.normalY,
                    feature.normalZ,
                )
            is ScaleFeature ->
                OcctKernel.scaleWithHistory(
                    built[feature.baseIndex],
                    feature.factor,
                    feature.aboutX,
                    feature.aboutY,
                    feature.aboutZ,
                )
            is ShellFeature ->
                OcctKernel.shellWithHistory(
                    built[feature.baseIndex],
                    resolveFaces(feature.openFaces, feature.baseIndex, built),
                    feature.thickness,
                    feature.direction,
                )
            is PocketFeature ->
                OcctKernel.pocketWithHistory(
                    built[feature.baseIndex],
                    resolvePocketFace(feature.face, feature.baseIndex, built),
                    feature.profile,
                    feature.depth,
                )
            // Draft and thicken rebuild every edge (ADR-0040); the tracking plan refuses them before this is reached.
            is DraftFeature, is ThickenFeature ->
                error("feature[$index] (${feature::class.simpleName}) cannot be built with edge history")
            is BooleanFeature ->
                OcctKernel.booleanWithHistory(feature.operation, built[feature.leftIndex], built[feature.rightIndex])
            is HoleFeature ->
                OcctKernel.holeWithHistory(
                    built[feature.baseIndex],
                    feature.diameter,
                    feature.depth,
                    feature.x,
                    feature.y,
                    feature.z,
                    feature.dirX,
                    feature.dirY,
                    feature.dirZ,
                )
            is SelectedFilletFeature ->
                OcctKernel.filletWithHistory(
                    built[feature.baseIndex],
                    resolveEdges(index, feature.edges, feature.baseIndex, built, histories, plan, features, namings),
                    feature.radius,
                )
            is SelectedChamferFeature ->
                OcctKernel.chamferWithHistory(
                    built[feature.baseIndex],
                    resolveEdges(index, feature.edges, feature.baseIndex, built, histories, plan, features, namings),
                    feature.distance,
                )
            // Root features have no operands and are never planned onto a tracking path.
            is BoxFeature,
            is ExtrudeFeature,
            is CylinderFeature,
            is RevolveFeature,
            is ExtrudeRegionFeature,
            is RevolveRegionFeature,
            is ExtrudeRegionsFeature,
            is RevolveRegionsFeature,
            is ExternalRootFeature,
            ->
                error("feature[$index] (${feature::class.simpleName}) cannot be built with edge history")
        }

    /**
     * The ONE face a [PocketFeature] cuts into: a selector is resolved with `expected = 1` even when the feature does
     * not say so, so a tie is an error with the candidate list instead of a silent first pick (ADR-0040).
     */
    private fun resolvePocketFace(
        reference: FaceReference,
        baseIndex: Int,
        built: List<OcctShape>,
    ): Int =
        when (reference) {
            is SelectFaces ->
                OcctKernel
                    .selectFaces(built[baseIndex], reference.selector, reference.expected ?: 1, reference.tolerance)
                    .also {
                        require(it.size == 1) { "a pocket needs exactly one face, the selection matched ${it.size}" }
                    }.single()
            is FaceIndices -> {
                require(reference.indices.size == 1) {
                    "a pocket needs exactly one face index, got ${reference.indices.size}"
                }
                reference.indices.single()
            }
        }

    /** Resolves the faces a [ShellFeature], [DraftFeature] or [ThickenFeature] works on against the shape of its own base feature, afresh on every rebuild. */
    private fun resolveFaces(
        reference: FaceReference,
        baseIndex: Int,
        built: List<OcctShape>,
    ): List<Int> =
        when (reference) {
            is SelectFaces ->
                OcctKernel.selectFaces(built[baseIndex], reference.selector, reference.expected, reference.tolerance)
            is FaceIndices -> reference.indices
        }

    private fun resolveEdges(
        index: Int,
        reference: EdgeReference,
        baseIndex: Int,
        built: List<OcctShape>,
        histories: Map<Int, EdgeHistory>,
        plan: TrackingPlan,
        features: List<Feature>,
        namings: Map<Int, GenerationMap>,
    ): List<Int> =
        when (reference) {
            is SelectEdges ->
                OcctKernel.selectEdges(
                    built[baseIndex],
                    reference.selector,
                    reference.expected,
                    reference.tolerance,
                )
            is TrackedEdges -> resolveTracked(index, reference, baseIndex, built, histories, plan, features)
            is GeneratedEdges ->
                resolveGenerated(index, reference, baseIndex, built, histories, plan, features, namings)
        }

    private fun resolveTracked(
        index: Int,
        reference: TrackedEdges,
        baseIndex: Int,
        built: List<OcctShape>,
        histories: Map<Int, EdgeHistory>,
        plan: TrackingPlan,
        features: List<Feature>,
    ): List<Int> {
        val source = built[reference.sourceIndex]
        val initial = OcctKernel.selectEdges(source, reference.selector, null, reference.tolerance)
        val steps = checkNotNull(plan.stepsByConsumer[index]) { "no tracking plan for feature[$index]" }
        val (current, holder) =
            followSteps(reference.sourceIndex, initial, steps, histories, built) {
                edge,
                holderShape,
                verdict,
                node,
                ->
                throw EdgeSelectionException(
                    "tracked edge $edge of feature[${node.holder}] was $verdict by " +
                        "feature[${node.step}] " +
                        "(${features[node.step]::class.simpleName}; the edge no longer exists after that operation; selected by ${reference.selector} on feature[${reference.sourceIndex}])",
                    reference.selector,
                    reference.expected,
                    node.current,
                    node.current.map { holderShape.edges[it] },
                )
            }
        val target = built[baseIndex]
        if (reference.expected != null && current.size != reference.expected) {
            throw EdgeSelectionException(
                "tracked edge selection from feature[${reference.sourceIndex}] is ambiguous at feature[$holder]: " +
                    "expected ${reference.expected} edge(s) but ${current.size} edge(s) result: $current",
                reference.selector,
                reference.expected,
                current,
                current.map { target.edges[it] },
            )
        }
        return current
    }

    /** Where an edge was lost: the holder feature, the operation that lost it, and the edges followed so far. */
    private class LossPoint(
        val holder: Int,
        val step: Int,
        val current: List<Int>,
    )

    /**
     * Follows [initial] (edge indices of feature [start]) through [steps] using the recorded edge histories.
     * Returns the resulting edge indices (of the last step's feature) and that feature's index. [onLost] must
     * throw when an edge has no successor.
     */
    private fun followSteps(
        start: Int,
        initial: List<Int>,
        steps: List<TrackStep>,
        histories: Map<Int, EdgeHistory>,
        built: List<OcctShape>,
        onLost: (edge: Int, holderShape: OcctShape, verdict: String, at: LossPoint) -> Nothing,
    ): Pair<List<Int>, Int> {
        var current = initial
        var holder = start
        for (step in steps) {
            // `current` indexes the edges of built[holder] (the intermediate shape), not of the start shape.
            val holderShape = built[holder]
            val fates =
                checkNotNull(histories[step.nodeIndex]) {
                    "no edge history for feature[${step.nodeIndex}]"
                }.operands[step.operandSlot]
            val next = java.util.TreeSet<Int>()
            for (edge in current) {
                val fate = fates[edge]
                if (fate.resultEdges.isEmpty()) {
                    val verdict = if (fate is EdgeFate.Deleted) "deleted" else "lost"
                    onLost(edge, holderShape, verdict, LossPoint(holder, step.nodeIndex, current))
                }
                next.addAll(fate.resultEdges)
            }
            current = next.toList()
            holder = step.nodeIndex
        }
        return current to holder
    }

    private fun resolveGenerated(
        index: Int,
        reference: GeneratedEdges,
        baseIndex: Int,
        built: List<OcctShape>,
        histories: Map<Int, EdgeHistory>,
        plan: TrackingPlan,
        features: List<Feature>,
        namings: Map<Int, GenerationMap>,
    ): List<Int> {
        val generation =
            checkNotNull(namings[reference.featureIndex]) { "no generation map for feature[${reference.featureIndex}]" }
        val generatorShape = built[reference.featureIndex]
        val initial =
            resolveGeneratedImages(generation, reference.sources, reference.role, null, generatorShape, index)
        val steps = checkNotNull(plan.stepsByConsumer[index]) { "no tracking plan for feature[$index]" }
        val perSource = reference.sources.associate { it.describe() to generation.images(it, reference.role) }
        val named = reference.sources.joinToString { it.describe() }
        val (followed, holder) =
            followSteps(reference.featureIndex, initial, steps, histories, built) {
                edge,
                holderShape,
                verdict,
                node,
                ->
                throw EdgeNamingException(
                    "generated edge $edge of feature[${node.holder}] was $verdict by feature[${node.step}] " +
                        "(${features[node.step]::class.simpleName}; the edge no longer exists after that operation; " +
                        "named by [$named] on feature[${reference.featureIndex}])\n" +
                        EdgeNamingException.detail(perSource, holderShape.edges),
                    index,
                    reference.sources,
                    node.current,
                    perSource,
                    holderShape.edges,
                )
            }
        val target = built[baseIndex]
        var result = followed
        if (reference.within != null) {
            val inside = OcctKernel.selectEdges(target, reference.within, null, reference.tolerance).toSet()
            result = followed.filter { it in inside }
        }
        val wrongCount = if (reference.expected != null) result.size != reference.expected else result.isEmpty()
        if (wrongCount) {
            throw EdgeNamingException(
                "generated edge selection from feature[${reference.featureIndex}] is ambiguous at feature[$holder]: " +
                    (if (reference.expected != null) "expected ${reference.expected} edge(s) but " else "") +
                    "${result.size} edge(s) result: $result\n" + EdgeNamingException.detail(perSource, target.edges),
                index,
                reference.sources,
                result,
                perSource,
                target.edges,
            )
        }
        return result
    }

    private fun wrapFailure(
        index: Int,
        feature: Feature,
        closedShapes: List<OcctShape>,
        cause: RuntimeException,
    ) = FeatureRebuildException(
        index,
        feature,
        closedShapes.toList(),
        "Rebuild failed at feature[$index] ($feature): ${cause.message}",
        cause,
    )
}
