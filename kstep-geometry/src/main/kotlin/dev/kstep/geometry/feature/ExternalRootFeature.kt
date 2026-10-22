package dev.kstep.geometry.feature

import dev.kstep.geometry.OcctShape

/**
 * A root [Feature] whose shape is produced by code outside this module -- for example a constraint
 * sketch (`dev.kstep.sketch.SketchFeature`), which cannot live here because `kstep-geometry` must not
 * depend on the constraint solver. See `docs/adr/ADR-0032-sketch-extensions.adoc` for why this open
 * interface, and not a module dependency or a global registry, carries the extension.
 *
 * Kotlin only requires direct subtypes of a sealed interface to live in the same module and package;
 * implementations of this (non-sealed) sub-interface may live anywhere. [FeatureRebuilder] treats
 * every implementation as a root feature: it has no operands and no edge history.
 */
interface ExternalRootFeature : Feature {
    /** Short name for diagnostics: at most 64 printable ASCII characters. */
    val label: String

    /**
     * Builds a NEW shape owned by the caller. Implementations must be free of side effects, must not
     * cache or share the returned shape, and must close every intermediate [OcctShape] they create.
     */
    fun buildShape(): OcctShape
}

/**
 * Thrown by [FeatureRebuilder] when an [ExternalRootFeature] fails with an exception that is neither
 * an `IllegalArgumentException` nor an `OcctGeometryException` (for example an unavailable solver).
 * The original is the [cause]; [FeatureRebuilder] wraps this in a `FeatureRebuildException` after
 * closing every shape built so far.
 */
class ExternalFeatureException(
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)
