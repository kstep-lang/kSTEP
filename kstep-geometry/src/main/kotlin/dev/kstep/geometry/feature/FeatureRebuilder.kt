package dev.kstep.geometry.feature

import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape

/**
 * Replays a [FeatureSequence] against the existing, unchanged [OcctKernel] API, producing a fresh
 * [FeatureRebuildResult] each time [rebuild] is called -- the foundation a parametric feature
 * history needs: change one [Feature]'s value, call [rebuild] again, get a new, independently
 * measurable shape.
 *
 * Deliberately NOT the whole of "Geometrie Welle 5b": no stable *geometric* edge identity (this
 * wave inherits [OcctKernel.fillet]'s opaque, construction-order-dependent edge indices as-is, see
 * that function's KDoc and ADR-0008), no undo/redo, no feature-tree UI, no serialization. See
 * `docs/adr/ADR-0015-parametric-feature-history-foundation.adoc`.
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
     * Structural validation (empty sequence, [MAX_SEQUENCE_LENGTH], a [FilletFeature.baseIndex]
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
     *   [MAX_SEQUENCE_LENGTH] features, or contains a [FilletFeature] whose [FilletFeature.baseIndex]
     *   does not refer to a strictly earlier position in [sequence] -- surfaces unwrapped, exactly
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
        require(sequence.features.size <= MAX_SEQUENCE_LENGTH) {
            "sequence must have at most $MAX_SEQUENCE_LENGTH features, got ${sequence.features.size}"
        }
        sequence.features.forEachIndexed { index, feature ->
            if (feature is FilletFeature) {
                require(feature.baseIndex in 0 until index) {
                    "feature[$index] baseIndex ${feature.baseIndex} must refer to an earlier " +
                        "position in the sequence (0..${index - 1})"
                }
            }
        }

        val built = ArrayList<OcctShape>(sequence.features.size)
        sequence.features.forEachIndexed { index, feature ->
            val shape =
                try {
                    when (feature) {
                        is BoxFeature -> OcctKernel.makeBox(feature.dx, feature.dy, feature.dz)
                        is ExtrudeFeature -> OcctKernel.extrudeProfile(feature.profile, feature.height)
                        is FilletFeature ->
                            OcctKernel.fillet(built[feature.baseIndex], feature.edgeIndices, feature.radius)
                    }
                } catch (e: IllegalArgumentException) {
                    built.forEach { it.close() }
                    throw wrapFailure(index, feature, built, e)
                } catch (e: OcctGeometryException) {
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
