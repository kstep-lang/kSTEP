package dev.kstep.script

import dev.kstep.core.DslViolation
import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.select.FaceSelector
import dev.kstep.geometry.style.FaceStyle
import dev.kstep.geometry.style.LayerNames
import dev.kstep.geometry.style.ShapeStyle
import dev.kstep.geometry.style.StepColour
import dev.kstep.geometry.style.StyleErrorKind
import dev.kstep.geometry.style.StyleException
import dev.kstep.shape.RigidPlacement
import dev.kstep.shape.Rotation
import dev.kstep.shape.ShapeAssignment
import dev.kstep.shape.UsagePlacement
import dev.kstep.shape.Vec3
import dev.kstep.step21.Part21Header

/**
 * The single artifact a `*.kstep.kts` script produces: a Part-21 [header] plus its [roots]
 * object set, ready for `Part21Writer.write(header, roots)` — unless one or more entities
 * registered via [KStepModelBuilder.root] failed their own WHERE-rule/mandatory-reference
 * validation, in which case [violations] carries every collected [DslViolation] instead and
 * [roots] only ever contains the entities that *did* validate successfully.
 *
 * [shapes] is the separate, additive geometry channel introduced in kSTEP's
 * headless-preview-rendering wave (see docs/adr/ADR-0011-headless-preview-rendering.adoc): a
 * [dev.kstep.shape.ShapeAssignment] registered via [KStepModelBuilder.shape] never becomes a
 * `roots` entry on its own (only an explicit `root(...)` call does that — see [hasGeometry]'s
 * KDoc for why that distinction matters), so `kstep export`'s `Part21Writer.write` behavior is
 * completely unchanged by a script that also calls `shape(...)`.
 *
 * A script's last expression must evaluate to this type (built via the top-level [stepFile]
 * DSL entry point) — see [KStepScriptHost] for how the host extracts it, defaults a blank
 * [Part21Header.timestamp], and what happens when the last expression is something else.
 */
data class KStepModel(
    val header: Part21Header,
    val roots: List<Any>,
    val violations: List<DslViolation>,
    val shapes: List<ShapeAssignment> = emptyList(),
    /**
     * Component placements registered via [KStepModelBuilder.place] (ADR-0028). Like [shapes], a
     * separate additive channel: it never becomes a `roots` entry and is only consulted when
     * `kstep export` merges geometry.
     */
    val placements: List<UsagePlacement> = emptyList(),
) {
    val isValid: Boolean get() = violations.isEmpty()

    /**
     * `true` iff this model registered at least one [ShapeAssignment] via
     * [KStepModelBuilder.shape] — the definition `kstep render` uses to decide whether a script
     * "contains geometry" (see docs/adr/ADR-0011-headless-preview-rendering.adoc). Deliberately
     * NOT satisfied by a [ShapeAssignment] reachable only via `roots` (which cannot happen today
     * anyway — no `kstep-core` AP242 entity type wraps geometry) nor by the mere presence of
     * `kstep-geometry` on the classpath.
     */
    val hasGeometry: Boolean get() = shapes.isNotEmpty()
}

/**
 * Builder receiver for [stepFile]. Two ways to register a root, both supported deliberately
 * (kSTEP-ADR-0001's acceptance criterion #3 discussion, see the M2 scripting-wave plan):
 *
 * - [root] with an already-unwrapped entity — the concise `getOrThrow()` pattern. A validation
 *   failure throws *inside the script body*, before this builder or [stepFile] ever runs, so
 *   [KStepScriptHost]'s runtime-exception handling is what surfaces it (as a
 *   [KStepScriptOutcome.ValidationErrors], via its `getOrThrow()`-marker-message recognition —
 *   see that class's KDoc), not [KStepModel.violations].
 * - [root] with a raw [ValidationResult] — the preferred, non-throwing form for LLM/JSON
 *   consumption: every violation across every registered root is aggregated into
 *   [KStepModel.violations] instead of the script aborting at the first bad entity.
 */
class KStepModelBuilder internal constructor() {
    private val validRoots = mutableListOf<Any>()
    private val collectedViolations = mutableListOf<DslViolation>()
    private val registeredShapes = mutableListOf<ShapeAssignment>()
    private val registeredPlacements = mutableListOf<UsagePlacement>()
    private val styles = java.util.IdentityHashMap<ProductDefinition, StyleAccumulator>()

    // Colours and layers of one part (ADR-0039), collected in any call order and applied in build().
    private class StyleAccumulator {
        var colour: StepColour? = null
        var layer: String? = null
        val faces = java.util.TreeMap<Int, Pair<StepColour?, String?>>()
    }

    /** Registers an already-validated entity (typically the result of a builder's `getOrThrow()`). */
    fun root(entity: Any) {
        validRoots += entity
    }

    /**
     * Registers a [ShapeAssignment] on [KStepModel.shapes] — the geometry channel `kstep render`
     * reads (see [KStepModel.hasGeometry]). Deliberately does NOT also add [assignment] (or its
     * [ShapeAssignment.shape]) to `roots`: `Part21Writer.write` has no entity type for geometry
     * today, so a script that wants both a Part-21 export AND a rendered preview must call both
     * `root(...)` (for its AP242 product structure) and `shape(...)` (for the preview) — see
     * `kstep export`'s stderr warning when `shapes` is non-empty, added alongside this in the
     * same wave.
     */
    fun shape(assignment: ShapeAssignment) {
        registeredShapes += assignment.copy(style = ShapeStyle.NONE)
        applyStyle(assignment.productDefinition, assignment.style)
    }

    /** Convenience overload: builds a [ShapeAssignment] from its two required parts inline,
     *  matching [ShapeAssignment]'s own constructor shape. */
    fun shape(
        productDefinition: ProductDefinition,
        shape: OcctShape,
        shapeName: String = "",
        colour: StepColour? = null,
        layer: String? = null,
    ) {
        registeredShapes += ShapeAssignment(productDefinition, shape, shapeName)
        if (colour != null || layer != null) style(productDefinition, colour, layer)
    }

    /**
     * Gives the part of [productDefinition] a [colour] and/or a [layer] in the exported STEP file (ADR-0039). An
     * annotation on the result, not a shape operation. May be called before or after [shape]; the product definition
     * must have a shape by the end of the `stepFile` block. A second colour or a second layer for the same part is an
     * error (`KSTEP-S-014`).
     */
    fun style(
        productDefinition: ProductDefinition,
        colour: StepColour? = null,
        layer: String? = null,
    ) {
        layer?.let { LayerNames.require(it) }
        val acc = styles.getOrPut(productDefinition) { StyleAccumulator() }
        if (colour != null) {
            if (acc.colour != null) throw conflict("part '${productDefinition.id}' already has a colour")
            acc.colour = colour
        }
        if (layer != null) {
            if (acc.layer != null) throw conflict("part '${productDefinition.id}' already has a layer")
            acc.layer = layer
        }
    }

    /**
     * Gives the faces [faces] resolves to a [colour] and/or a [layer] (ADR-0039). The selector is resolved right away
     * against the shape already registered for [productDefinition] and never guesses: no match or a count different
     * from [expected] fails with `KSTEP-S-011` and the candidate list. Because the script is evaluated again on every
     * rebuild, `Faces.atMax(Direction3.Z)` keeps meaning the top face when a dimension changes. A face can get a colour
     * and a layer once each (`KSTEP-S-014`).
     */
    fun styleFaces(
        productDefinition: ProductDefinition,
        faces: FaceSelector,
        colour: StepColour? = null,
        layer: String? = null,
        expected: Int? = null,
    ) {
        if (colour == null && layer == null) {
            throw StyleException(StyleErrorKind.CONFLICT, "styleFaces needs a colour or a layer")
        }
        layer?.let { LayerNames.require(it) }
        val assignment =
            registeredShapes.lastOrNull { it.productDefinition === productDefinition }
                ?: throw StyleException(
                    StyleErrorKind.TARGET,
                    "styleFaces: product definition '${productDefinition.id}' has no shape yet; call shape(...) first",
                )
        val indices = OcctKernel.selectFaces(assignment.shape, faces, expected)
        val acc = styles.getOrPut(productDefinition) { StyleAccumulator() }
        for (index in indices) {
            val (oldColour, oldLayer) = acc.faces[index] ?: (null to null)
            if (colour != null && oldColour != null) throw conflict("face $index already has a colour")
            if (layer != null && oldLayer != null) throw conflict("face $index already has a layer")
            acc.faces[index] = (colour ?: oldColour) to (layer ?: oldLayer)
        }
    }

    private fun conflict(message: String) = StyleException(StyleErrorKind.CONFLICT, message)

    private fun applyStyle(
        productDefinition: ProductDefinition,
        style: ShapeStyle,
    ) {
        if (style.isEmpty) return
        style(productDefinition, style.colour, style.layer)
        for (face in style.faces) {
            val acc = styles.getOrPut(productDefinition) { StyleAccumulator() }
            val (oldColour, oldLayer) = acc.faces[face.faceIndex] ?: (null to null)
            if (face.colour != null && oldColour != null) throw conflict("face ${face.faceIndex} already has a colour")
            if (face.layer != null && oldLayer != null) throw conflict("face ${face.faceIndex} already has a layer")
            acc.faces[face.faceIndex] = (face.colour ?: oldColour) to (face.layer ?: oldLayer)
        }
    }

    /**
     * Positions the component of [usage] relative to its parent assembly: `pParent = R * pChild + t`
     * (translation in millimetres; [rotation] via `Rotation.about(axis, degrees)` or
     * `Rotation.fromAxes`). The placement belongs to the *usage*, not to the part, so one part can be
     * used several times (a screw) at different places. It only takes effect in `kstep export` when
     * geometry is merged, and the usage must be registered via `root(...)` and have a child that
     * carries geometry -- otherwise the export fails closed. A usage without `place(...)` whose
     * child has geometry is placed at the identity.
     *
     * A placement is rigid: no mirroring and no scaling (see ADR-0036). To mirror or scale a body use
     * `mirror(...)` / `scale(...)` on the shape instead.
     */
    fun place(
        usage: NextAssemblyUsageOccurrence,
        translation: Vec3 = Vec3.ZERO,
        rotation: Rotation = Rotation.IDENTITY,
    ) {
        registeredPlacements += UsagePlacement(usage, RigidPlacement(translation, rotation))
    }

    /** [place] with a ready-made [RigidPlacement]. */
    fun place(
        usage: NextAssemblyUsageOccurrence,
        placement: RigidPlacement,
    ) {
        registeredPlacements += UsagePlacement(usage, placement)
    }

    /** [place] for a raw [ValidationResult]: an invalid usage contributes its violations instead of aborting the script. */
    fun place(
        usage: ValidationResult<NextAssemblyUsageOccurrence>,
        translation: Vec3 = Vec3.ZERO,
        rotation: Rotation = Rotation.IDENTITY,
    ) {
        when (usage) {
            is ValidationResult.Valid -> place(usage.value, translation, rotation)
            is ValidationResult.Invalid -> collectedViolations += usage.violations
        }
    }

    /** Registers a raw [ValidationResult]: an [ValidationResult.Invalid] contributes its
     * violations to [KStepModel.violations] instead of aborting the script.
     */
    fun <T : Any> root(result: ValidationResult<T>) {
        when (result) {
            is ValidationResult.Valid -> validRoots += result.value
            is ValidationResult.Invalid -> collectedViolations += result.violations
        }
    }

    /**
     * Registers several roots at once — see the two [root] overloads for the accepted shapes.
     *
     * A `vararg` parameter has a single static element type, so it cannot forward each argument
     * to the matching [root] overload via ordinary overload resolution — every element would be
     * seen as `Any`, always selecting [root]'s single-entity overload even for a raw
     * [ValidationResult] argument (which would then be stored, unwrapped, as a "root", silently
     * defeating validation instead of aggregating into [KStepModel.violations]). Each element is
     * therefore inspected at runtime and handled exactly as the matching [root] overload would.
     */
    fun roots(vararg entities: Any) {
        entities.forEach { entity ->
            when {
                entity is ValidationResult.Valid<*> ->
                    validRoots +=
                        requireNotNull(entity.value) {
                            "roots(...): a ValidationResult.Valid root must not wrap a null value"
                        }
                entity is ValidationResult.Invalid -> collectedViolations += entity.violations
                else -> validRoots += entity
            }
        }
    }

    private fun styledShapes(): List<ShapeAssignment> {
        for (productDefinition in styles.keys) {
            if (registeredShapes.none { it.productDefinition === productDefinition }) {
                throw StyleException(
                    StyleErrorKind.TARGET,
                    "style(...) for product definition '${productDefinition.id}', which has no shape",
                )
            }
        }
        return registeredShapes.map { a ->
            val acc = styles[a.productDefinition] ?: return@map a
            a.copy(
                style =
                    ShapeStyle(
                        colour = acc.colour,
                        layer = acc.layer,
                        faces = acc.faces.map { (index, v) -> FaceStyle(index, v.first, v.second) },
                    ),
            )
        }
    }

    internal fun build(header: Part21Header): KStepModel =
        KStepModel(
            header = header,
            roots = validRoots.toList(),
            violations = collectedViolations.toList(),
            shapes = styledShapes(),
            placements = registeredPlacements.toList(),
        )
}

/**
 * Top-level DSL entry point for a `*.kstep.kts` script: builds the single [KStepModel] the
 * script exports, with the Part-21 header fields supplied inline — the CLI has no other
 * channel to receive them from.
 *
 * [timestamp] may be left blank: [KStepScriptHost] substitutes the current time before handing
 * a successfully-validated model to `Part21Writer.write`, which keeps this function itself
 * pure/deterministic (and its own unit tests reproducible) rather than baking a wall-clock read
 * into the DSL surface.
 */
fun stepFile(
    fileName: String,
    schema: String = "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF",
    timestamp: String = "",
    description: List<String> = emptyList(),
    author: List<String> = emptyList(),
    organization: List<String> = emptyList(),
    block: KStepModelBuilder.() -> Unit,
): KStepModel {
    val builder = KStepModelBuilder().apply(block)
    val header =
        Part21Header(
            fileName = fileName,
            timestamp = timestamp,
            schemaIdentifiers = listOf(schema),
            description = description,
            author = author,
            organization = organization,
        )
    return builder.build(header)
}
