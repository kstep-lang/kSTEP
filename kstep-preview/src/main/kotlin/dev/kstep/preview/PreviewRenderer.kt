package dev.kstep.preview

import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.ColoredMesh
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import dev.kstep.geometry.style.StepColour
import dev.kstep.render.RenderLimits
import dev.kstep.render.export.MeshExportPart
import dev.kstep.render.export.MeshExportResult
import dev.kstep.render.export.MeshExportScene
import dev.kstep.render.export.StlWriter
import dev.kstep.render.export.ThreeMfWriter
import dev.kstep.render.gltf.GlbExtras
import dev.kstep.render.gltf.GlbWriteResult
import dev.kstep.render.gltf.GlbWriter
import dev.kstep.render.mesh.MeshProjection
import dev.kstep.render.mesh.ProjectedTriangle
import dev.kstep.render.text.TextCardRenderer
import dev.kstep.script.KStepModel
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.script.KStepScriptOutcomeCodes
import dev.kstep.script.RecordedEvaluation
import dev.kstep.shape.AssemblyInstanceExpander
import dev.kstep.shape.PlacedInstance
import dev.kstep.shape.ShapeAssignment
import dev.kstep.shape.ShapeExportException
import dev.kstep.shape.toGeometryPlacement
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import javax.imageio.ImageIO

/**
 * Thrown by [PreviewRenderer] when in-memory image encoding fails -- effectively unreachable on
 * a standard JDK (a PNG `ImageWriter` is always registered), kept only so this failure mode
 * degrades into a clean, structured error instead of an unchecked `false` return being silently
 * ignored. Distinct from `PreviewIoException` (`dev.kstep.docs`/`kstep-cli`'s own file-write
 * wrapper): this is about encoding bytes in memory, never about writing them to disk --
 * [PreviewRenderer] itself performs no file I/O at all.
 */
class PreviewEncodingException(
    message: String,
) : RuntimeException(message)

private class ProducedBytes(
    val bytes: ByteArray,
    val glb: GlbWriteResult? = null,
    val meshExport: MeshExportResult? = null,
    val parts: List<RenderedPart>? = null,
    val tree: RenderedAssemblyTree? = null,
)

/** One part's range in the SOURCE mesh (before the GLB writer drops degenerate triangles). */
internal class PartSpan(
    val name: String,
    val sourceFirstTriangle: Int,
    val sourceTriangleCount: Int,
    val placement: Placement,
    val color: MeshColor,
)

/**
 * Maps source-mesh part spans onto GLB triangle indices, given the ascending source indices of the triangles the GLB
 * writer actually kept. A part whose triangles were all dropped disappears (ADR-0053).
 */
internal fun mapSpansToGlb(
    spans: List<PartSpan>,
    kept: IntArray,
): List<RenderedPart> = mapSpansToGlbIndexed(spans, kept).first

/**
 * Like [mapSpansToGlb], plus, per span, the index of its part in the resulting list (`-1` for a span that vanished);
 * the assembly tree (ADR-0054) is re-indexed with it.
 */
internal fun mapSpansToGlbIndexed(
    spans: List<PartSpan>,
    kept: IntArray,
): Pair<List<RenderedPart>, IntArray> {
    val out = ArrayList<RenderedPart>(spans.size)
    val partOfSpan = IntArray(spans.size) { -1 }
    var pointer = 0
    for ((i, span) in spans.withIndex()) {
        val end = span.sourceFirstTriangle.toLong() + span.sourceTriangleCount
        while (pointer < kept.size && kept[pointer] < span.sourceFirstTriangle) pointer++
        val glbFirst = pointer
        while (pointer < kept.size && kept[pointer] < end) pointer++
        val count = pointer - glbFirst
        if (count > 0) {
            partOfSpan[i] = out.size
            out += RenderedPart(span.name, glbFirst, count, span.placement, span.color)
        }
    }
    return out to partOfSpan
}

private fun single(
    first: ShapeAssignment,
    mesh: TriangleMesh,
    color: MeshColor,
): PartSpan = PartSpan(partDisplayName(first.productDefinition), 0, mesh.triangleCount, Placement.IDENTITY, color)

private fun partDisplayName(pd: ProductDefinition): String =
    pd.formation.ofProduct.name
        .ifEmpty { pd.formation.ofProduct.id }
        .ifEmpty { pd.id }

private sealed interface RenderContent {
    /**
     * The UNprojected world-space mesh -- only the `RenderFormat.GLB` branch of [renderBytes]
     * uses this; SVG/PNG keep working exclusively off [triangles], the already-culled/
     * painter-ordered 2D projection.
     */
    data class Geometry(
        val mesh: TriangleMesh,
        val triangles: List<ProjectedTriangle>,
        val shapeCount: Int,
        val previewedShapeIndex: Int,
        /** Drawn instances of a placed assembly (ADR-0035); `null` for the single-shape preview. */
        val instanceCount: Int? = null,
        val distinctShapeCount: Int? = null,
        /**
         * The part colours of the script (ADR-0039), as a coloured mesh over [mesh]; `null` when no shape carries a
         * colour, in which case every output is byte-identical to the uncoloured rendering.
         */
        val colored: ColoredMesh? = null,
        /** One entry per distinct (mesh, colour) with its placements -- the input of the STL/3MF writers (ADR-0049). */
        val exportParts: List<MeshExportPart> = emptyList(),
        /** Source-triangle ranges per part, in merge order (ADR-0053). */
        val partSpans: List<PartSpan> = emptyList(),
        /** The assembly hierarchy over [partSpans] (ADR-0054); `null` for the single-shape preview. */
        val rawTree: RawAssemblyTree? = null,
    ) : RenderContent

    // Carries no lines of its own -- renderBytes computes the summary card's text fresh from
    // PreviewSummary.modelLines with the real script name/--with-step flag, so this variant is
    // just a marker distinguishing "no geometry, nothing went wrong" from Notice.
    data object Summary : RenderContent

    data class Notice(
        val lines: List<String>,
        val reason: String,
    ) : RenderContent
}

// Part colours go straight into the albedo multiplier; reduces to NEUTRAL only for white, which is exact (ADR-0039).
// Welle Y: the alpha comes along (ADR-0044 amendment); opaque colours (a = 1) take the unchanged render path.
private fun StepColour.toMeshColor() = MeshColor(r, g, b, a)

// The one exceptionClass value KStepScriptOutcome.RuntimeError carries when
// OcctKernel.makeBox/extrudeProfile/fillet throws BEFORE stepFile ever runs, on a machine
// without the native OCCT bridge (see ADR-0011's B6 finding). This is the "no model at all, just
// a RuntimeError" half of the Pflicht-Fallback; resolveContent's own catch clauses cover the
// "model exists, but its shape didn't triangulate" half.
private const val OCCT_UNAVAILABLE_EXCEPTION_CLASS = "dev.kstep.geometry.OcctUnavailableException"

/**
 * Evaluate -> detect geometry -> resolve container -> render bytes -> close shapes.
 * The single implementation of docs/adr/ADR-0011-headless-preview-rendering.adoc's
 * Container-Regel and Pflicht-Fallback, shared by `kstep render` (`kstep-cli`) and
 * `kstep asciidoc` (`kstep-docs/kstep-asciidoc`) -- see ADR-0019 for why this was extracted
 * rather than copied.
 *
 * NEVER calls `exitProcess`, never writes a file, never prints. Every `OcctShape` the produced
 * model registered is closed before this returns, on every path (success, notice, and
 * exception), via a `finally` -- unlike the pre-extraction code, no `System.exit` can skip it.
 */
object PreviewRenderer {
    fun render(request: PreviewRequest): PreviewOutcome {
        if (request.width < RenderLimits.MIN_DIMENSION_PX ||
            request.width > RenderLimits.MAX_DIMENSION_PX ||
            request.height < RenderLimits.MIN_DIMENSION_PX ||
            request.height > RenderLimits.MAX_DIMENSION_PX
        ) {
            return PreviewOutcome.InvalidRequest(
                "Error: --width/--height must each be between ${RenderLimits.MIN_DIMENSION_PX} and " +
                    "${RenderLimits.MAX_DIMENSION_PX}, got ${request.width}x${request.height}",
            )
        }
        if (request.width.toLong() * request.height.toLong() > RenderLimits.MAX_TOTAL_PIXELS) {
            return PreviewOutcome.InvalidRequest(
                "Error: --width * --height must not exceed ${RenderLimits.MAX_TOTAL_PIXELS} pixels, " +
                    "got ${request.width}x${request.height}",
            )
        }

        val target =
            if (request.format != RenderFormat.AUTO) {
                request.format
            } else {
                request.outPathHint?.let { RenderFormat.fromExtension(it) }
            }
        request.meshToleranceMm?.let { tol ->
            if (!(tol.isFinite() && tol > 0.0)) {
                return PreviewOutcome.InvalidRequest(
                    "Error: --tolerance must be a finite number greater than 0, got $tol",
                )
            }
            if (target?.isMeshExport != true) {
                return PreviewOutcome.InvalidRequest("Error: --tolerance is only valid with -f stl or -f 3mf")
            }
        }
        if (request.requireWatertight && target?.isMeshExport != true) {
            return PreviewOutcome.InvalidRequest("Error: --require-watertight is only valid with -f stl or -f 3mf")
        }

        val evaluation = evalSource(request.source)
        val dependencies = evaluation.dependencies
        return when (val outcome = evaluation.outcome) {
            is KStepScriptOutcome.Success -> renderSuccess(request, outcome.model, dependencies)
            is KStepScriptOutcome.RuntimeError ->
                if (outcome.exceptionClass == OCCT_UNAVAILABLE_EXCEPTION_CLASS ||
                    outcome.code == KStepScriptOutcomeCodes.SKETCH_SOLVER_UNAVAILABLE
                ) {
                    renderNoModelOcctFallback(request, outcome, dependencies)
                } else {
                    PreviewOutcome.ScriptFailed(outcome, dependencies)
                }
            else -> PreviewOutcome.ScriptFailed(outcome, dependencies)
        }
    }

    private fun evalSource(source: PreviewSource): RecordedEvaluation =
        when (source) {
            is PreviewSource.ScriptFile -> KStepScriptHost.evalRecording(source.file)
            is PreviewSource.InlineScript ->
                RecordedEvaluation(KStepScriptHost.eval(source.code, source.displayName), emptyList())
        }

    private fun renderSuccess(
        request: PreviewRequest,
        model: KStepModel,
        dependencies: List<String>,
    ): PreviewOutcome {
        try {
            val occt = OcctKernel.availability()
            val (content, warnings) =
                resolveContent(
                    model,
                    occt,
                    request.width,
                    request.height,
                    request.meshToleranceMm,
                )
            return finishRender(request, content, model, occt, warnings, dependencies)
        } finally {
            closeShapes(model)
        }
    }

    /** Closes every triangulated shape [model] carries -- safe to call with `model = null` (the
     *  "no model at all" Pflicht-Fallback never has a shape to close) and safe to call more than
     *  once, since each individual close is itself wrapped in [runCatching]. */
    private fun closeShapes(model: KStepModel?) {
        model?.shapes?.forEach { assignment -> runCatching { assignment.shape.close() } }
    }

    /** The "no model at all" half of the Pflicht-Fallback -- see [OCCT_UNAVAILABLE_EXCEPTION_CLASS]. */
    private fun renderNoModelOcctFallback(
        request: PreviewRequest,
        outcome: KStepScriptOutcome.RuntimeError,
        dependencies: List<String>,
    ): PreviewOutcome {
        val occt = OcctKernel.availability()
        val content =
            RenderContent.Notice(
                PreviewSummary.noticeLines(request.source.displayName, outcome.message, occt, model = null),
                RenderFallbackReasons.OCCT_UNAVAILABLE,
            )
        return finishRender(
            request,
            content,
            model = null,
            occt = occt,
            warnings = emptyList(),
            dependencies = dependencies,
        )
    }

    private fun finishRender(
        request: PreviewRequest,
        content: RenderContent,
        model: KStepModel?,
        occt: OcctAvailability,
        warnings: List<String>,
        dependencies: List<String>,
    ): PreviewOutcome {
        val scriptName = request.source.displayName
        val format = resolveFormat(request, content)
        val produced =
            renderBytes(format, content, scriptName, model, occt, request.withStep, request.width, request.height)
        val bytes = produced.bytes
        val glbResult = produced.glb
        val meshExport = produced.meshExport
        val parts = produced.parts
        val tree = produced.tree

        val contentKind =
            when (content) {
                is RenderContent.Geometry -> PreviewContentKind.GEOMETRY
                is RenderContent.Summary -> PreviewContentKind.SUMMARY
                is RenderContent.Notice -> PreviewContentKind.NOTICE
            }
        val geometryStats =
            if (content is RenderContent.Geometry) {
                PreviewGeometryStats(
                    shapeCount = content.shapeCount,
                    previewedShapeIndex = content.previewedShapeIndex,
                    projectedTriangleCount = content.triangles.size,
                    meshTriangleCount = content.mesh.triangleCount,
                    instanceCount = content.instanceCount ?: 1,
                    distinctShapeCount = content.distinctShapeCount ?: 1,
                )
            } else {
                null
            }
        val modelStats = model?.let { PreviewModelStats(it.roots.size, it.shapes.size, it.hasGeometry) }

        return PreviewOutcome.Rendered(
            bytes = bytes,
            format = format,
            contentKind = contentKind,
            fallbackReason = (content as? RenderContent.Notice)?.reason,
            noticeLines = (content as? RenderContent.Notice)?.lines ?: emptyList(),
            occt = occt,
            model = modelStats,
            geometry = geometryStats,
            glb = glbResult,
            warnings = warnings + (meshExport?.warnings ?: emptyList()),
            meshExport = meshExport,
            parts = parts,
            tree = tree,
            exportTargets = model?.exportTargets.orEmpty().map { RenderedExportTarget(it.format.id, it.fileName) },
            dependencies = dependencies,
        )
    }

    /** @return the resolved content plus any non-fatal warnings the caller should print (the
     *  no-structure multi-shape R-3 warning, shapes outside the assembly) -- kept out of this
     *  function's own I/O so this module stays print-free (see this file's own top-level KDoc). */
    private fun resolveContent(
        model: KStepModel,
        occt: OcctAvailability,
        width: Int,
        height: Int,
        toleranceMm: Double?,
    ): Pair<RenderContent, List<String>> {
        if (!model.hasGeometry) {
            return RenderContent.Summary to emptyList()
        }
        val warnings = mutableListOf<String>()

        fun notice(
            message: String,
            reason: String,
        ) = RenderContent.Notice(PreviewSummary.noticeLines("(script)", message, occt, model), reason) to warnings

        val expansion =
            try {
                AssemblyInstanceExpander.expand(
                    model.roots,
                    model.shapes.map { it.productDefinition },
                    model.placements,
                )
            } catch (e: ShapeExportException) {
                return notice(e.message ?: "invalid assembly placement", RenderFallbackReasons.PLACEMENT_INVALID)
            }
        val assembly = expansion.instances.isNotEmpty()
        if (!assembly && model.shapes.size > 1) {
            warnings +=
                "kstep render: model carries ${model.shapes.size} shape(s) but no assembly usage places them; " +
                "previewing shape 1 of ${model.shapes.size} -- without an assembly structure a combined " +
                "preview would overlay all shapes at the origin (see " +
                "docs/adr/ADR-0035-placement-aware-render.adoc; ADR-0011, R-3)."
        }
        val content =
            try {
                if (assembly) {
                    val unplaced = model.shapes.size - identityCount(expansion.instances)
                    if (unplaced > 0) {
                        warnings +=
                            "kstep render: $unplaced shape(s) are not part of the assembly structure and are " +
                            "not drawn (see docs/adr/ADR-0035-placement-aware-render.adoc)."
                    }
                    assemblyGeometry(model, expansion.instances, width, height, toleranceMm)
                } else {
                    val first = model.shapes.first()
                    val mesh = first.shape.triangulate(toleranceMm)
                    val colour = first.style.colour
                    val singlePart =
                        listOf(
                            MeshExportPart(
                                "${first.productDefinition.id}",
                                mesh,
                                colour?.toMeshColor() ?: MeshColor.NEUTRAL,
                                listOf(Placement.IDENTITY),
                            ),
                        )
                    if (colour == null) {
                        val triangles = MeshProjection.project(mesh, width.toDouble(), height.toDouble())
                        RenderContent.Geometry(
                            mesh,
                            triangles,
                            model.shapes.size,
                            previewedShapeIndex = 0,
                            exportParts = singlePart,
                            partSpans = listOf(single(first, mesh, MeshColor.NEUTRAL)),
                        )
                    } else {
                        val colored =
                            MeshComposition.mergeColored(
                                listOf(PlacedMesh(mesh, color = colour.toMeshColor())),
                            )
                        val triangles = MeshProjection.project(colored, width.toDouble(), height.toDouble())
                        RenderContent.Geometry(
                            colored.mesh,
                            triangles,
                            model.shapes.size,
                            previewedShapeIndex = 0,
                            colored = colored,
                            exportParts = singlePart,
                            partSpans = listOf(single(first, mesh, colour.toMeshColor())),
                        )
                    }
                }
            } catch (e: OcctUnavailableException) {
                return notice(e.message ?: "OCCT unavailable", RenderFallbackReasons.OCCT_UNAVAILABLE)
            } catch (e: ShapeExportException) {
                return notice(e.message ?: "invalid assembly placement", RenderFallbackReasons.PLACEMENT_INVALID)
            } catch (e: IllegalStateException) {
                return notice(
                    "shape already closed by the script before rendering: ${e.message}",
                    RenderFallbackReasons.SHAPE_CLOSED_BY_SCRIPT,
                )
            } catch (e: OcctGeometryException) {
                return notice(e.message ?: "triangulation failed", RenderFallbackReasons.TRIANGULATION_FAILED)
            } catch (e: IllegalArgumentException) {
                // OcctShape.triangulate() also documents @throws IllegalArgumentException for the
                // native MAX_TRIANGLES guard -- see the pre-extraction RenderCommand.kt's identical
                // catch clause for the full reasoning; preserved verbatim here.
                return notice(e.message ?: "triangulation failed", RenderFallbackReasons.TRIANGULATION_FAILED)
            }
        return content to warnings
    }

    private fun identityCount(instances: List<PlacedInstance>): Int {
        val seen = IdentityHashMap<ProductDefinition, Boolean>()
        for (i in instances) seen[i.productDefinition] = true
        return seen.size
    }

    /**
     * Builds the merged world-space geometry of a placed assembly (ADR-0035): every distinct shaped
     * definition is triangulated exactly once, then placed once per instance. The running triangle
     * total is checked after each triangulation, BEFORE [MeshComposition.merge] allocates anything.
     */
    private fun assemblyGeometry(
        model: KStepModel,
        instances: List<PlacedInstance>,
        width: Int,
        height: Int,
        toleranceMm: Double?,
    ): RenderContent.Geometry {
        val shapeOf = IdentityHashMap<ProductDefinition, ShapeAssignment>()
        for (assignment in model.shapes) {
            if (shapeOf.put(assignment.productDefinition, assignment) != null) {
                throw ShapeExportException(
                    "more than one shape is bound to product definition '${assignment.productDefinition.id}'; " +
                        "exactly one shape per product definition is supported -- combine several bodies with " +
                        "OcctKernel.compound(...) (ADR-0036)",
                )
            }
        }
        // First-appearance order, so triangulation (and any failure) is deterministic.
        val instanceCountOf = IdentityHashMap<ProductDefinition, Int>()
        val distinctInOrder = mutableListOf<ProductDefinition>()
        for (instance in instances) {
            val pd = instance.productDefinition
            val previous = instanceCountOf.put(pd, (instanceCountOf[pd] ?: 0) + 1)
            if (previous == null) distinctInOrder += pd
        }
        val meshOf = IdentityHashMap<ProductDefinition, TriangleMesh>()
        var total = 0L
        for (pd in distinctInOrder) {
            val count = instanceCountOf.getValue(pd)
            val mesh = shapeOf.getValue(pd).shape.triangulate(toleranceMm)
            meshOf[pd] = mesh
            total += mesh.triangleCount.toLong() * count
            require(total <= OcctKernel.MAX_TRIANGLES) {
                "placed assembly exceeds the limit of ${OcctKernel.MAX_TRIANGLES} triangles " +
                    "(at least $total over ${instances.size} instance(s))"
            }
        }
        // Instance colours (ADR-0045): keyed by usage identity (NAUO ids are not guaranteed unique); they win over the
        // part colour, alpha included (the winning source supplies its whole colour, Welle Y).
        val styleOf = IdentityHashMap<NextAssemblyUsageOccurrence, StepColour>()
        for (style in model.usageStyles) styleOf[style.usage] = style.colour

        // own occurrence colour > colour of the part > colours of outer sub-assembly occurrences, innermost first
        // (ADR-0046 D5, the order OCCT's own presentation logic measures)
        fun outerColour(instance: PlacedInstance): StepColour? {
            for (i in instance.usagePath.size - 2 downTo 0) styleOf[instance.usagePath[i]]?.let { return it }
            return null
        }

        fun colourOf(instance: PlacedInstance): StepColour? =
            instance.usage?.let { styleOf[it] }
                ?: shapeOf.getValue(instance.productDefinition).style.colour
                ?: outerColour(instance)
        val anyColour = shapeOf.values.any { it.style.colour != null } || instances.any { colourOf(it) != null }
        val parts =
            instances.map { instance ->
                PlacedMesh(
                    meshOf.getValue(instance.productDefinition),
                    instance.placement.toGeometryPlacement(),
                    colourOf(instance)?.toMeshColor() ?: MeshColor.NEUTRAL,
                )
            }
        val colored = if (anyColour) MeshComposition.mergeColored(parts) else null
        val merged = colored?.mesh ?: MeshComposition.merge(parts)
        // Export parts (ADR-0049): one per distinct (mesh identity, colour), first-seen order, all placements.
        val exportOrder = mutableListOf<Triple<ProductDefinition, MeshColor, MutableList<Placement>>>()
        for ((i, instance) in instances.withIndex()) {
            val part = parts[i]
            val existing =
                exportOrder.firstOrNull {
                    it.first === instance.productDefinition && it.second == part.color
                }
            if (existing != null) {
                existing.third += part.placement
            } else {
                exportOrder += Triple(instance.productDefinition, part.color, mutableListOf(part.placement))
            }
        }
        val exportParts =
            exportOrder.map { (pd, colour, placements) ->
                MeshExportPart("${pd.id}", meshOf.getValue(pd), colour, placements)
            }
        val spans = ArrayList<PartSpan>(instances.size)
        var cursor = 0
        for ((i, instance) in instances.withIndex()) {
            val count = parts[i].mesh.triangleCount
            spans +=
                PartSpan(
                    partDisplayName(instance.productDefinition),
                    cursor,
                    count,
                    parts[i].placement,
                    parts[i].color,
                )
            cursor += count
        }
        val chains =
            instances.mapIndexed { i, instance ->
                val path = instance.usagePath
                val rootPd =
                    (path.firstOrNull()?.relatingProductDefinition as? ProductDefinition)
                        ?: instance.productDefinition
                InstanceChain(
                    rootKey = rootPd,
                    rootName = partDisplayName(rootPd),
                    steps =
                        path.map { usage ->
                            val related = usage.relatedProductDefinition as? ProductDefinition
                            ChainStep(usage, related?.let { partDisplayName(it) } ?: usage.name.ifEmpty { usage.id })
                        },
                    leafName = spans[i].name,
                    leafSpan = i,
                )
            }
        val triangles =
            if (colored != null) {
                MeshProjection.project(colored, width.toDouble(), height.toDouble())
            } else {
                MeshProjection.project(merged, width.toDouble(), height.toDouble())
            }
        return RenderContent.Geometry(
            merged,
            triangles,
            model.shapes.size,
            previewedShapeIndex = 0,
            instanceCount = instances.size,
            distinctShapeCount = instanceCountOf.size,
            colored = colored,
            exportParts = exportParts,
            partSpans = spans,
            rawTree = buildAssemblyTree(chains),
        )
    }

    private fun resolveFormat(
        request: PreviewRequest,
        content: RenderContent,
    ): RenderFormat {
        if (request.format != RenderFormat.AUTO) return request.format
        request.outPathHint?.let { RenderFormat.fromExtension(it)?.let { fromExt -> return fromExt } }
        return if (content is RenderContent.Geometry) RenderFormat.SVG else RenderFormat.TEXT
    }

    /** @return the rendered bytes, plus the [GlbWriteResult] when [format] is [RenderFormat.GLB]
     *  (`null` for every other format, which has nothing analogous to report). */
    private fun renderBytes(
        format: RenderFormat,
        content: RenderContent,
        scriptName: String,
        model: KStepModel?,
        occt: OcctAvailability,
        withStep: Boolean,
        width: Int,
        height: Int,
    ): ProducedBytes {
        // Lazy for the same reason the pre-extraction writeContainer computed it lazily: a
        // `-f glb --with-step` render on real geometry never reads this at all (the GLB branch
        // sets summaryLines = emptyList() in that case) -- computing it eagerly would pay for a
        // full Part21Writer.write serialization of the whole model just to discard it.
        val lines by lazy {
            when (content) {
                is RenderContent.Geometry ->
                    PreviewSummary.modelLines(
                        scriptName,
                        requireNotNull(model) { "RenderContent.Geometry always carries a real model" },
                        occt,
                        withStep,
                        contentLines =
                            listOf(
                                "Geometry",
                                if (content.instanceCount != null) {
                                    "  assembly: ${content.instanceCount} instance(s) of " +
                                        "${content.distinctShapeCount} part(s)"
                                } else {
                                    "  previewed shape index ${content.previewedShapeIndex}"
                                },
                            ),
                    )
                is RenderContent.Summary ->
                    PreviewSummary.modelLines(
                        scriptName,
                        requireNotNull(model) { "RenderContent.Summary always carries a real model" },
                        occt,
                        withStep,
                    )
                is RenderContent.Notice -> content.lines
            }
        }
        return when (format) {
            RenderFormat.SVG -> {
                val svgBytes =
                    if (content is RenderContent.Geometry) {
                        MeshPreview.svgBytes(content.triangles, width, height)
                    } else {
                        TextCardRenderer.toSvg(lines, width, height).toByteArray(Charsets.UTF_8)
                    }
                ProducedBytes(svgBytes)
            }
            RenderFormat.PNG -> {
                if (content is RenderContent.Geometry) {
                    ProducedBytes(MeshPreview.pngBytes(content.triangles, width, height))
                } else {
                    val out = ByteArrayOutputStream()
                    val wrote = ImageIO.write(TextCardRenderer.toImage(lines, width, height), "png", out)
                    if (!wrote) {
                        throw PreviewEncodingException("no ImageIO writer available for 'png'")
                    }
                    ProducedBytes(out.toByteArray())
                }
            }
            RenderFormat.TEXT -> {
                ProducedBytes((lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            }
            RenderFormat.GLB -> {
                // A full-text summary in `extras` only for the geometry-free case -- mirrors how
                // SVG/PNG paint the text card only when there is no triangle mesh to draw.
                val extras =
                    GlbExtras(
                        scriptName = scriptName,
                        summaryLines = if (content is RenderContent.Geometry) emptyList() else lines,
                    )
                val result =
                    if (content is RenderContent.Geometry) {
                        content.colored?.let { GlbWriter.write(it, extras) } ?: GlbWriter.write(content.mesh, extras)
                    } else {
                        GlbWriter.writeEmptyScene(extras)
                    }
                val mapped =
                    if (content is RenderContent.Geometry) {
                        mapSpansToGlbIndexed(content.partSpans, result.keptSourceTriangles)
                    } else {
                        null
                    }
                val tree = mapped?.let { (content as RenderContent.Geometry).rawTree?.reindexed(it.second) }
                ProducedBytes(result.bytes, glb = result, parts = mapped?.first, tree = tree)
            }
            RenderFormat.STL, RenderFormat.THREE_MF -> {
                // ADR-0049 D9: a printer file is NEVER a text card or an empty model -- no geometry means no bytes.
                if (content is RenderContent.Geometry) {
                    val scene = MeshExportScene(scriptName, content.exportParts)
                    val result =
                        if (format == RenderFormat.STL) StlWriter.write(scene) else ThreeMfWriter.write(scene)
                    ProducedBytes(result.bytes, meshExport = result)
                } else {
                    ProducedBytes(ByteArray(0))
                }
            }
            RenderFormat.AUTO -> error("unreachable -- format must be resolved before renderBytes")
        }
    }
}
