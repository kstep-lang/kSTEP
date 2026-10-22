package dev.kstep.sketch

import dev.kstep.geometry.ExternalGenerationSource
import dev.kstep.geometry.GenerationSource

/**
 * The corner of a sketch profile at the point called [pointName] (the `name` given to `point(...)` or
 * `rectangle(..., names = ...)`; default `p<index>`). Names a SIDE edge: the vertical edge swept from the
 * corner. Stable across parameter changes, because the name is part of the sketch definition (ADR-0033).
 */
data class SketchCorner(
    val pointName: String,
) : ExternalGenerationSource {
    override val description: String get() = "corner '$pointName'"
}

/**
 * The profile edge between the points called [fromName] and [toName] (either direction); names its START
 * and END copies. Fails as ambiguous if a line and an arc join the same two points.
 */
data class SketchEdgeOf(
    val fromName: String,
    val toName: String,
) : ExternalGenerationSource {
    override val description: String get() = "edge '$fromName'-'$toName'"
}

/** Builders for lists of sketch generation sources. */
object SketchSources {
    /** [SketchCorner] for each of [names]. */
    fun corners(vararg names: String): List<GenerationSource> = names.map { SketchCorner(it) }
}
