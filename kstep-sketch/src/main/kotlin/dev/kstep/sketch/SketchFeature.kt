package dev.kstep.sketch

import dev.kstep.geometry.GenerationSource
import dev.kstep.geometry.NamedShape
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.feature.ExternalRootFeature

/** Named numeric parameters handed to a [SketchDefinition]; unknown names fail loudly. */
class SketchParameters internal constructor(
    private val values: Map<String, Double>,
) {
    /** The value of parameter [name]. */
    operator fun get(name: String): Double =
        values[name]
            ?: throw SketchException(SketchErrorKind.INVALID_INPUT, "unknown sketch parameter '$name'")

    /** All parameter names. */
    val names: Set<String> get() = values.keys
}

/** The body of a parameterised sketch; must be free of side effects (it runs on every rebuild). */
typealias SketchDefinition = SketchBuilder.(SketchParameters) -> Unit

/** What a [SketchFeature] makes of its profile. */
sealed interface SketchOperation {
    /** Extrude along +Z by [height]. */
    data class Extrude(
        val height: Double,
    ) : SketchOperation

    /** Revolve about [axis] by [angleDegrees]. */
    data class Revolve(
        val angleDegrees: Double = 360.0,
        val axis: RevolveAxis = RevolveAxis.Y,
    ) : SketchOperation
}

/**
 * A constraint sketch as a step of a feature history: replaying the sequence re-solves the sketch from its
 * (changed) [parameters], so a dimension change flows through the whole history. See
 * `docs/adr/ADR-0032-sketch-extensions.adoc`.
 *
 * The [definition] must be pure: it runs on every [buildShape] call, with the current parameters, and is
 * compared by identity in [equals]. [parameters] are validated: at most 64, names of up to 64 printable
 * ASCII characters, finite values.
 */
class SketchFeature(
    val parameters: Map<String, Double>,
    val operation: SketchOperation,
    val requireFullyConstrained: Boolean = true,
    override val label: String = "sketch",
    private val definition: SketchDefinition,
) : ExternalRootFeature {
    init {
        if (parameters.size > MAX_PARAMETERS) {
            throw SketchException(
                SketchErrorKind.LIMIT_EXCEEDED,
                "a sketch feature may have at most $MAX_PARAMETERS parameters",
            )
        }
        parameters.forEach { (name, value) ->
            if (name.isEmpty() || name.length > MAX_NAME || name.any { it.code !in 0x20..0x7E }) {
                throw SketchException(
                    SketchErrorKind.INVALID_INPUT,
                    "a parameter name must be 1..$MAX_NAME printable ASCII characters",
                )
            }
            if (!value.isFinite()) {
                throw SketchException(SketchErrorKind.INVALID_INPUT, "parameter '$name' must be finite, got $value")
            }
        }
        if (label.length > MAX_NAME || label.any { it.code !in 0x20..0x7E }) {
            throw SketchException(
                SketchErrorKind.INVALID_INPUT,
                "a label must be at most $MAX_NAME printable ASCII characters",
            )
        }
    }

    /** A copy with parameter [name] set to [value]; [name] must already exist. */
    fun withParameter(
        name: String,
        value: Double,
    ): SketchFeature {
        if (name !in parameters) {
            throw SketchException(SketchErrorKind.INVALID_INPUT, "unknown sketch parameter '$name'")
        }
        return SketchFeature(parameters + (name to value), operation, requireFullyConstrained, label, definition)
    }

    /** A copy with a different [operation]. */
    fun withOperation(op: SketchOperation): SketchFeature =
        SketchFeature(parameters, op, requireFullyConstrained, label, definition)

    /** Solves the sketch with the current parameters. */
    fun solve(): SolvedSketch {
        val params = SketchParameters(parameters)
        val builder = SketchBuilder()
        definition(builder, params)
        return builder.build().solve(requireFullyConstrained)
    }

    override fun buildShape(): OcctShape {
        val solved = solve()
        return when (val op = operation) {
            is SketchOperation.Extrude -> solved.extrude(op.height)
            is SketchOperation.Revolve -> solved.revolve(op.angleDegrees, op.axis)
        }
    }

    override val supportsGeneratedNaming: Boolean get() = true

    /**
     * Builds the shape and reports the edges generated from [sources] ([SketchCorner] / [SketchEdgeOf] by point
     * name). The names come from the sketch definition, so they survive parameter changes. See
     * [SolvedSketch.extrudeNamed] for the errors.
     */
    override fun buildNamedShape(sources: List<GenerationSource>): NamedShape {
        val solved = solve()
        return when (val op = operation) {
            is SketchOperation.Extrude -> solved.extrudeNamed(op.height, sources)
            is SketchOperation.Revolve -> solved.revolveNamed(op.angleDegrees, op.axis, sources)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SketchFeature &&
            parameters == other.parameters &&
            operation == other.operation &&
            requireFullyConstrained == other.requireFullyConstrained &&
            label == other.label &&
            definition === other.definition

    override fun hashCode(): Int =
        listOf(parameters, operation, requireFullyConstrained, label, System.identityHashCode(definition)).hashCode()

    override fun toString(): String = "SketchFeature(label=$label, parameters=$parameters, operation=$operation)"

    private companion object {
        const val MAX_PARAMETERS = 64
        const val MAX_NAME = 64
    }
}

/** Builds a [SketchFeature]; see there. */
fun sketchFeature(
    parameters: Map<String, Double>,
    operation: SketchOperation,
    requireFullyConstrained: Boolean = true,
    label: String = "sketch",
    definition: SketchDefinition,
): SketchFeature = SketchFeature(parameters, operation, requireFullyConstrained, label, definition)
