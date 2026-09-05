package dev.kstep.preview

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.RenderLimits
import dev.kstep.render.gltf.GlbExtras
import dev.kstep.render.gltf.GlbWriteResult
import dev.kstep.render.gltf.GlbWriter
import dev.kstep.render.image.TriangleRasterizer
import dev.kstep.render.mesh.MeshProjection
import dev.kstep.render.mesh.ProjectedTriangle
import dev.kstep.render.svg.TriangleSvgWriter
import dev.kstep.render.text.TextCardRenderer
import dev.kstep.script.KStepModel
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import java.io.ByteArrayOutputStream
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

        return when (val outcome = evalSource(request.source)) {
            is KStepScriptOutcome.Success -> renderSuccess(request, outcome.model)
            is KStepScriptOutcome.RuntimeError ->
                if (outcome.exceptionClass == OCCT_UNAVAILABLE_EXCEPTION_CLASS) {
                    renderNoModelOcctFallback(request, outcome)
                } else {
                    PreviewOutcome.ScriptFailed(outcome)
                }
            else -> PreviewOutcome.ScriptFailed(outcome)
        }
    }

    private fun evalSource(source: PreviewSource): KStepScriptOutcome =
        when (source) {
            is PreviewSource.ScriptFile -> KStepScriptHost.eval(source.file)
            is PreviewSource.InlineScript -> KStepScriptHost.eval(source.code, source.displayName)
        }

    private fun renderSuccess(
        request: PreviewRequest,
        model: KStepModel,
    ): PreviewOutcome {
        try {
            val occt = OcctKernel.availability()
            val (content, warnings) = resolveContent(model, occt, request.width, request.height)
            return finishRender(request, content, model, occt, warnings)
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
    ): PreviewOutcome {
        val occt = OcctKernel.availability()
        val content =
            RenderContent.Notice(
                PreviewSummary.noticeLines(request.source.displayName, outcome.message, occt, model = null),
                RenderFallbackReasons.OCCT_UNAVAILABLE,
            )
        return finishRender(request, content, model = null, occt = occt, warnings = emptyList())
    }

    private fun finishRender(
        request: PreviewRequest,
        content: RenderContent,
        model: KStepModel?,
        occt: OcctAvailability,
        warnings: List<String>,
    ): PreviewOutcome {
        val scriptName = request.source.displayName
        val format = resolveFormat(request, content)
        val (bytes, glbResult) =
            renderBytes(format, content, scriptName, model, occt, request.withStep, request.width, request.height)

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
            warnings = warnings,
        )
    }

    /** @return the resolved content plus any non-fatal warnings the caller should print (the
     *  multi-shape R-3 warning) -- kept out of this function's own I/O so this module stays
     *  print-free (see this file's own top-level KDoc). */
    private fun resolveContent(
        model: KStepModel,
        occt: OcctAvailability,
        width: Int,
        height: Int,
    ): Pair<RenderContent, List<String>> {
        if (!model.hasGeometry) {
            return RenderContent.Summary to emptyList()
        }
        val warnings = mutableListOf<String>()
        if (model.shapes.size > 1) {
            warnings +=
                "kstep render: model carries ${model.shapes.size} shape(s); previewing shape 1 of " +
                "${model.shapes.size} -- ShapeAssignment carries no placement transform yet, so a " +
                "combined preview would overlay all shapes at the origin (see " +
                "docs/adr/ADR-0011-headless-preview-rendering.adoc, R-3)."
        }
        val assignment = model.shapes.first()
        val mesh: TriangleMesh
        try {
            mesh = assignment.shape.triangulate()
        } catch (e: OcctUnavailableException) {
            return RenderContent.Notice(
                PreviewSummary.noticeLines("(script)", e.message ?: "OCCT unavailable", occt, model),
                RenderFallbackReasons.OCCT_UNAVAILABLE,
            ) to warnings
        } catch (e: IllegalStateException) {
            return RenderContent.Notice(
                PreviewSummary.noticeLines(
                    "(script)",
                    "shape already closed by the script before rendering: ${e.message}",
                    occt,
                    model,
                ),
                RenderFallbackReasons.SHAPE_CLOSED_BY_SCRIPT,
            ) to warnings
        } catch (e: OcctGeometryException) {
            return RenderContent.Notice(
                PreviewSummary.noticeLines("(script)", e.message ?: "triangulation failed", occt, model),
                RenderFallbackReasons.TRIANGULATION_FAILED,
            ) to warnings
        } catch (e: IllegalArgumentException) {
            // OcctShape.triangulate() also documents @throws IllegalArgumentException for the
            // native MAX_TRIANGLES guard -- see the pre-extraction RenderCommand.kt's identical
            // catch clause for the full reasoning; preserved verbatim here.
            return RenderContent.Notice(
                PreviewSummary.noticeLines("(script)", e.message ?: "triangulation failed", occt, model),
                RenderFallbackReasons.TRIANGULATION_FAILED,
            ) to warnings
        }
        val triangles = MeshProjection.project(mesh, width.toDouble(), height.toDouble())
        return RenderContent.Geometry(mesh, triangles, model.shapes.size, previewedShapeIndex = 0) to warnings
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
    ): Pair<ByteArray, GlbWriteResult?> {
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
                        contentLines = listOf("Geometry", "  previewed shape index ${content.previewedShapeIndex}"),
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
                val svg =
                    if (content is RenderContent.Geometry) {
                        TriangleSvgWriter.render(content.triangles, width, height)
                    } else {
                        TextCardRenderer.toSvg(lines, width, height)
                    }
                svg.toByteArray(Charsets.UTF_8) to null
            }
            RenderFormat.PNG -> {
                val image =
                    if (content is RenderContent.Geometry) {
                        TriangleRasterizer.render(content.triangles, width, height)
                    } else {
                        TextCardRenderer.toImage(lines, width, height)
                    }
                val out = ByteArrayOutputStream()
                val wrote = ImageIO.write(image, "png", out)
                if (!wrote) {
                    throw PreviewEncodingException("no ImageIO writer available for 'png'")
                }
                out.toByteArray() to null
            }
            RenderFormat.TEXT -> {
                (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8) to null
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
                        GlbWriter.write(content.mesh, extras)
                    } else {
                        GlbWriter.writeEmptyScene(extras)
                    }
                result.bytes to result
            }
            RenderFormat.AUTO -> error("unreachable -- format must be resolved before renderBytes")
        }
    }
}
