package dev.kstep.geometry.feature

import dev.kstep.geometry.OcctShape

/**
 * Thrown by [FeatureRebuilder.rebuild] when replaying a [FeatureSequence] fails -- either a
 * structural problem [FeatureRebuilder] itself validates (empty sequence, sequence too long, an
 * invalid [FilletFeature.baseIndex]) or a failure surfaced by the underlying
 * [dev.kstep.geometry.OcctKernel] call for [feature] (an `IllegalArgumentException` or
 * [dev.kstep.geometry.OcctGeometryException], both wrapped here uniformly -- see
 * [FeatureRebuilder.rebuild]'s KDoc for why the two are not kept distinct the way `OcctKernel`
 * itself keeps them distinct).
 *
 * @property featureIndex 0-based position of the failing [Feature] within the [FeatureSequence]
 *   that was being rebuilt.
 * @property feature the failing [Feature] itself, for a caller that wants to inspect or retry with
 *   an adjusted value without re-deriving it from [featureIndex].
 * @property closedShapes every [OcctShape] [FeatureRebuilder.rebuild] had successfully built for
 *   this sequence before [feature] failed, in construction order (so `closedShapes.size ==
 *   featureIndex`). Each one is ALREADY CLOSED -- `rebuild()` closes them itself, via
 *   `built.forEach { it.close() }`, before this exception is even constructed, exactly as
 *   documented on [FeatureRebuilder.rebuild]'s own KDoc ("no [OcctShape] is ever left open and
 *   unreachable by the caller"). This property exists so that guarantee is independently
 *   observable -- not just documented -- by a caller (or a test, see `FeatureRebuildTest`'s T-4/
 *   T-5 cases) that wants to confirm closure actually happened, e.g. by asserting that accessing
 *   [OcctShape.volume] on one of these throws `IllegalStateException`. They are otherwise dead:
 *   calling anything on them besides the inherited, idempotent [OcctShape.close] throws.
 */
class FeatureRebuildException(
    val featureIndex: Int,
    val feature: Feature,
    val closedShapes: List<OcctShape>,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
