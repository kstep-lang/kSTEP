package dev.kstep.script

import dev.kstep.core.DslViolation
import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.select.EdgeSelector
import dev.kstep.geometry.select.FaceSelector
import dev.kstep.geometry.style.EdgeStyle
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
import dev.kstep.shape.UsageStyle
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
     * 1-based script line of the call that registered each entry of [shapes] (ADR-0091); empty or as long as
     * [shapes]. Access only through `getOrNull`: an entry is `null` when the call site is unknown.
     */
    val shapeSourceLines: List<Int?> = emptyList(),
    /**
     * Component placements registered via [KStepModelBuilder.place] (ADR-0028). Like [shapes], a
     * separate additive channel: it never becomes a `roots` entry and is only consulted when
     * `kstep export` merges geometry.
     */
    val placements: List<UsagePlacement> = emptyList(),
    /** Like [shapeSourceLines], parallel to [placements] (ADR-0091); empty or as long as [placements]. */
    val placementSourceLines: List<Int?> = emptyList(),
    /**
     * Instance colours registered via [KStepModelBuilder.styleInstance] (ADR-0044): one colour for one placed occurrence.
     * A separate additive channel like [placements]; only `kstep export` consults it when geometry is merged.
     */
    val usageStyles: List<UsageStyle> = emptyList(),
    /**
     * File names declared by the script's `export { }` block (ADR-0063). Pure declaration: the desktop app suggests
     * them in its export dialog, nothing is written by the script.
     */
    val exportTargets: List<ScriptExportTarget> = emptyList(),
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
    private val registeredUsageStyles = mutableListOf<UsageStyle>()
    private val shapeLines = mutableListOf<Int?>()
    private val placementLines = mutableListOf<Int?>()

    // Read once at construction, inside the host's evaluation on the calling thread (ADR-0091); null elsewhere.
    private val scriptSourceName: String? = KStepScriptHost.currentSourceName()
    private var declaredExportTargets: List<ScriptExportTarget>? = null
    private val styles = java.util.IdentityHashMap<ProductDefinition, StyleAccumulator>()

    // Colours and layers of one part (ADR-0039), collected in any call order and applied in build().
    private class StyleAccumulator {
        var colour: StepColour? = null
        var layer: String? = null
        val faces = java.util.TreeMap<Int, Pair<StepColour?, String?>>()
        val edges = java.util.TreeMap<Int, StepColour>()
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
        shapeLines += ScriptCallSite.line(scriptSourceName)
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
        shapeLines += ScriptCallSite.line(scriptSourceName)
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

    /**
     * Gives the edges of the shape of [productDefinition] that match [edges] an (opaque) colour (ADR-0042). The
     * selector resolves geometrically, like for [styleFaces]; an empty result or a count different from [expected]
     * fails with `KSTEP-S-011` listing the candidates. An edge can get a colour once (`KSTEP-S-014`); a transparent
     * colour is refused with `KSTEP-S-012` because the STEP curve style has no transparency.
     */
    fun styleEdges(
        productDefinition: ProductDefinition,
        edges: EdgeSelector,
        colour: StepColour,
        expected: Int? = null,
    ) {
        if (!colour.isOpaque) {
            throw StyleException(
                StyleErrorKind.INVALID_COLOUR,
                "styleEdges: edge colours cannot be transparent (CURVE_STYLE has no transparency), alpha was ${colour.a}",
            )
        }
        val assignment =
            registeredShapes.lastOrNull { it.productDefinition === productDefinition }
                ?: throw StyleException(
                    StyleErrorKind.TARGET,
                    "styleEdges: product definition '${productDefinition.id}' has no shape yet; call shape(...) first",
                )
        val indices = OcctKernel.selectEdges(assignment.shape, edges, expected)
        val acc = styles.getOrPut(productDefinition) { StyleAccumulator() }
        for (index in indices) {
            if (acc.edges.containsKey(index)) throw conflict("edge $index already has a colour")
            acc.edges[index] = colour
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
        for (edge in style.edges) {
            val acc = styles.getOrPut(productDefinition) { StyleAccumulator() }
            if (acc.edges.containsKey(edge.edgeIndex)) throw conflict("edge ${edge.edgeIndex} already has a colour")
            acc.edges[edge.edgeIndex] = edge.colour
        }
    }

    /**
     * Gives one placed occurrence, the component of [usage], its own [colour] in the exported STEP file (ADR-0044). The
     * colour belongs to the usage, like [place]: one part used twice can be red once and blue once, whatever colour the part
     * itself has. The colour may be translucent (Welle Y, alpha < 1; an alpha outside 0..1 or not finite is refused by
     * [StepColour] with `KSTEP-S-012`); a second colour for the same usage is `KSTEP-S-014`. The
     * usage must be registered via `root(...)` and its child must have a shape of its own or carry shapes below it,
     * otherwise the export fails closed. The child may be a sub-assembly (ADR-0046): the colour then applies to every
     * part below that occurrence, and the colour of a part or of an inner occurrence wins over it (ADR-0046 D5). It takes
     * effect in `kstep export`; `kstep render` draws it as well (ADR-0045), translucent where alpha < 1.
     */
    fun styleInstance(
        usage: NextAssemblyUsageOccurrence,
        colour: StepColour,
    ) {
        if (registeredUsageStyles.any { it.usage === usage }) {
            throw conflict("assembly usage '${usage.id}' already has an instance colour")
        }
        registeredUsageStyles += UsageStyle(usage, colour)
    }

    /** [styleInstance] for a raw [ValidationResult]: an invalid usage contributes its violations instead of aborting the script. */
    fun styleInstance(
        usage: ValidationResult<NextAssemblyUsageOccurrence>,
        colour: StepColour,
    ) {
        when (usage) {
            is ValidationResult.Valid -> styleInstance(usage.value, colour)
            is ValidationResult.Invalid -> collectedViolations += usage.violations
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
        placementLines += ScriptCallSite.line(scriptSourceName)
    }

    /** [place] with a ready-made [RigidPlacement]. */
    fun place(
        usage: NextAssemblyUsageOccurrence,
        placement: RigidPlacement,
    ) {
        registeredPlacements += UsagePlacement(usage, placement)
        placementLines += ScriptCallSite.line(scriptSourceName)
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

    /**
     * Declares the file names the desktop export dialog suggests (ADR-0063). At most one call per script, at least one
     * and at most four targets, each format once; an invalid name aborts the script with a clear message.
     */
    fun export(block: ExportBlockBuilder.() -> Unit) {
        require(declaredExportTargets == null) { "export { } may be declared only once" }
        declaredExportTargets = ExportBlockBuilder().apply(block).build()
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
                        edges = acc.edges.map { (index, c) -> EdgeStyle(index, c) },
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
            shapeSourceLines = shapeLines.toList(),
            placements = registeredPlacements.toList(),
            placementSourceLines = placementLines.toList(),
            usageStyles = registeredUsageStyles.toList(),
            exportTargets = declaredExportTargets.orEmpty(),
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
