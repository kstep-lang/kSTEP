package dev.kstep.cli

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.RenderLimits
import dev.kstep.render.image.TriangleRasterizer
import dev.kstep.render.mesh.IsometricProjection
import dev.kstep.render.mesh.ProjectedTriangle
import dev.kstep.render.svg.TriangleSvgWriter
import dev.kstep.render.text.TextCardRenderer
import dev.kstep.script.KStepModel
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Orchestrates `kstep render`: evaluate -> detect geometry -> resolve the output container ->
 * render content into it -> write -> report -- see
 * docs/adr/ADR-0011-headless-preview-rendering.adoc for the full contract (the "Container-Regel",
 * the geometry-detection definition, and the content/exit-code matrix this function implements).
 */
private sealed interface RenderContent {
    data class Geometry(
        val triangles: List<ProjectedTriangle>,
        val shapeCount: Int,
        val previewedShapeIndex: Int,
    ) : RenderContent

    // Carries no lines of its own -- writeContainer computes the summary card's text fresh from
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
// without the native OCCT bridge (see ADR-0011's B6 finding: a script that builds geometry
// eagerly, like hello-box.kstep.kts, needs OCCT to construct the OcctShape at all -- not just to
// triangulate one it already has). This is the "no model at all, just a RuntimeError" half of
// the Pflicht-Fallback; resolveContent's own catch clauses cover the "model exists, but its
// shape didn't triangulate" half.
private const val OCCT_UNAVAILABLE_EXCEPTION_CLASS = "dev.kstep.geometry.OcctUnavailableException"

fun runRender(command: CliCommand.Render) {
    if (command.width < RenderLimits.MIN_DIMENSION_PX ||
        command.width > RenderLimits.MAX_DIMENSION_PX ||
        command.height < RenderLimits.MIN_DIMENSION_PX ||
        command.height > RenderLimits.MAX_DIMENSION_PX
    ) {
        println(
            "Error: --width/--height must each be between ${RenderLimits.MIN_DIMENSION_PX} and " +
                "${RenderLimits.MAX_DIMENSION_PX}, got ${command.width}x${command.height}",
        )
        exitProcess(1)
    }
    if (command.width.toLong() * command.height.toLong() > RenderLimits.MAX_TOTAL_PIXELS) {
        println(
            "Error: --width * --height must not exceed ${RenderLimits.MAX_TOTAL_PIXELS} pixels, " +
                "got ${command.width}x${command.height}",
        )
        exitProcess(1)
    }

    when (val outcome = KStepScriptHost.eval(File(command.scriptPath))) {
        is KStepScriptOutcome.Success -> renderSuccess(command, outcome.model)
        is KStepScriptOutcome.RuntimeError ->
            if (outcome.exceptionClass == OCCT_UNAVAILABLE_EXCEPTION_CLASS) {
                renderNoModelOcctFallback(command, outcome)
            } else {
                printError(command.jsonOutput, outcome)
            }
        is KStepScriptOutcome.CompilationError -> printError(command.jsonOutput, outcome)
        is KStepScriptOutcome.NoModelProduced -> printError(command.jsonOutput, outcome)
        is KStepScriptOutcome.ValidationErrors -> printError(command.jsonOutput, outcome)
    }
}

private fun renderSuccess(
    command: CliCommand.Render,
    model: KStepModel,
) {
    try {
        val occt = OcctKernel.availability()
        val content = resolveContent(model, occt, command.width, command.height)
        finishRender(command, content, model, occt)
    } finally {
        // Covers every path through finishRender that returns normally (the success path, via
        // reportSuccess) and every path where an exception propagates out of it uncaught -- both
        // properly unwind the stack and run this `finally`. It does NOT cover finishRender's own
        // `exitProcess(1)` paths (reportFailure/reportIoFailure): System.exit halts the JVM
        // without unwinding the call stack, so a `finally` further up it never runs. Those two
        // paths close the shapes themselves, immediately before calling exitProcess -- see
        // finishRender.
        closeShapes(model)
    }
}

/** Closes every triangulated shape [model] carries -- shared by [renderSuccess]'s normal-return
 *  `finally` and [finishRender]'s two `exitProcess`-bound failure paths, which cannot rely on that
 *  `finally` ever running (see [renderSuccess]'s KDoc comment on why). Safe to call with
 *  `model = null` (the "no model at all" Pflicht-Fallback, [renderNoModelOcctFallback], never has
 *  a shape to close) and safe to call more than once for the same model, since each individual
 *  close is itself wrapped in [runCatching]. */
private fun closeShapes(model: KStepModel?) {
    model?.shapes?.forEach { assignment -> runCatching { assignment.shape.close() } }
}

/** The "no model at all" half of the Pflicht-Fallback -- see [OCCT_UNAVAILABLE_EXCEPTION_CLASS]. */
private fun renderNoModelOcctFallback(
    command: CliCommand.Render,
    outcome: KStepScriptOutcome.RuntimeError,
) {
    val occt = OcctKernel.availability()
    val scriptName = File(command.scriptPath).name
    val content =
        RenderContent.Notice(
            PreviewSummary.noticeLines(scriptName, outcome.message, occt, model = null),
            RenderFallbackReasons.OCCT_UNAVAILABLE,
        )
    finishRender(command, content, model = null, occt = occt)
}

private fun finishRender(
    command: CliCommand.Render,
    content: RenderContent,
    model: KStepModel?,
    occt: OcctAvailability,
) {
    val scriptName = File(command.scriptPath).name

    if (content is RenderContent.Notice) {
        // Printed on stderr unconditionally -- even on the silent-fallback (exit 0) path -- so
        // an operator watching logs sees that a geometry render was requested but not actually
        // produced, without having to parse the rendered file itself. See
        // docs/adr/ADR-0011-headless-preview-rendering.adoc's R8/R9 test cases.
        System.err.println("kstep render: geometry preview unavailable, falling back to a text card.")
        System.err.println("  reason: ${content.reason}")
        content.lines.forEach { line -> System.err.println(line) }
    }

    if (content is RenderContent.Notice && command.requireGeometry) {
        // reportFailure ends in exitProcess(1), which never returns to renderSuccess's `finally`
        // -- close explicitly here first. See closeShapes' KDoc.
        closeShapes(model)
        reportFailure(command, occt, content)
        return
    }

    val format = resolveFormat(command, content)
    val outPath = command.outPath ?: deriveRenderOutputPath(command.scriptPath, format)
    try {
        writeContainer(
            format,
            outPath,
            content,
            scriptName,
            model,
            occt,
            command.withStep,
            command.width,
            command.height,
        )
    } catch (e: RenderIoException) {
        // Same reasoning as the requireGeometry branch above: reportIoFailure ends in
        // exitProcess(1), which never returns to renderSuccess's `finally`.
        closeShapes(model)
        reportIoFailure(command, e)
        return
    }
    reportSuccess(command, outPath, format, content, occt, model)
}

private fun resolveContent(
    model: KStepModel,
    occt: OcctAvailability,
    width: Int,
    height: Int,
): RenderContent {
    if (!model.hasGeometry) {
        return RenderContent.Summary
    }
    if (model.shapes.size > 1) {
        System.err.println(
            "kstep render: model carries ${model.shapes.size} shape(s); previewing shape 1 of " +
                "${model.shapes.size} (see 'kstep render --help' -- multi-shape scenes are a Folge-Welle).",
        )
    }
    val assignment = model.shapes.first()
    val mesh: TriangleMesh
    try {
        mesh = assignment.shape.triangulate()
    } catch (e: OcctUnavailableException) {
        return RenderContent.Notice(
            PreviewSummary.noticeLines("(script)", e.message ?: "OCCT unavailable", occt, model),
            RenderFallbackReasons.OCCT_UNAVAILABLE,
        )
    } catch (e: IllegalStateException) {
        return RenderContent.Notice(
            PreviewSummary.noticeLines(
                "(script)",
                "shape already closed by the script before rendering: ${e.message}",
                occt,
                model,
            ),
            RenderFallbackReasons.SHAPE_CLOSED_BY_SCRIPT,
        )
    } catch (e: OcctGeometryException) {
        return RenderContent.Notice(
            PreviewSummary.noticeLines("(script)", e.message ?: "triangulation failed", occt, model),
            RenderFallbackReasons.TRIANGULATION_FAILED,
        )
    } catch (e: IllegalArgumentException) {
        // OcctShape.triangulate() also documents @throws IllegalArgumentException for the native
        // MAX_TRIANGLES guard (see OcctShape.kt/OcctKernel.MAX_TRIANGLES). Not reachable via any
        // shape makeBox/extrudeProfile/fillet can build today (MAX_TRIANGLES=130_000 is chosen
        // with headroom above what those builders can produce), but triangulate()'s own contract
        // includes it, and kstep-viewer's Main.kt already guards the identical call with a broad
        // runCatching for exactly this reason -- this catch keeps the two triangulate() call
        // sites consistent and keeps this path inside ADR-0011's Pflicht-Fallback contract rather
        // than propagating a raw stack trace once a future wave lets an arbitrary/imported shape
        // reach this point.
        return RenderContent.Notice(
            PreviewSummary.noticeLines("(script)", e.message ?: "triangulation failed", occt, model),
            RenderFallbackReasons.TRIANGULATION_FAILED,
        )
    }
    val triangles = IsometricProjection.project(mesh, width.toDouble(), height.toDouble())
    return RenderContent.Geometry(triangles, model.shapes.size, previewedShapeIndex = 0)
}

private fun resolveFormat(
    command: CliCommand.Render,
    content: RenderContent,
): RenderFormat {
    if (command.format != RenderFormat.AUTO) return command.format
    command.outPath?.let { RenderFormat.fromExtension(it)?.let { fromExt -> return fromExt } }
    return if (content is RenderContent.Geometry) RenderFormat.SVG else RenderFormat.TEXT
}

/** Thrown by [writeContainer] when the actual file write fails (missing/non-creatable parent
 *  directory, `--out` pointing at an existing directory, a full disk, ...) -- caught by
 *  [finishRender] and turned into a clean `Error: ...`/`--output json` report (exit 1), instead
 *  of the raw `java.io.FileNotFoundException` stack trace all three [RenderFormat] branches used
 *  to let escape uncaught (see the Stolperfalle this fixes: SVG/TEXT never called `mkdirs()` at
 *  all, so the very same `--out` that worked for PNG crashed for every other format). */
private class RenderIoException(
    outPath: String,
    cause: IOException,
) : RuntimeException("failed to write '$outPath': ${cause.message ?: cause::class.simpleName}", cause)

private fun writeContainer(
    format: RenderFormat,
    outPath: String,
    content: RenderContent,
    scriptName: String,
    model: KStepModel?,
    occt: OcctAvailability,
    withStep: Boolean,
    width: Int,
    height: Int,
) {
    val lines =
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
    // mkdirs() applies to all three formats alike -- previously only the PNG branch created
    // missing parent directories, so the identical `--out nested/dir/preview.svg` that worked
    // for `--format png` threw an uncaught FileNotFoundException for `svg`/`text`.
    val target = File(outPath)
    try {
        target.parentFile?.mkdirs()
        // Guard against a directory target explicitly, ahead of the per-format branches below:
        // ImageIO.write(RenderedImage, String, File) calls output.delete() internally before it
        // opens the ImageOutputStream, so an existing *empty* directory at `--out` is silently
        // deleted and replaced by the PNG file (exit 0, "Wrote ..."), while SVG/TEXT already fail
        // loudly via target.writeText's FileNotFoundException on the very same input. A single
        // upfront check keeps all three formats identically strict instead of only two of them.
        if (target.isDirectory) {
            throw RenderIoException(outPath, IOException("$outPath is a directory"))
        }
        when (format) {
            RenderFormat.SVG -> {
                val svg =
                    if (content is RenderContent.Geometry) {
                        TriangleSvgWriter.render(content.triangles, width, height)
                    } else {
                        TextCardRenderer.toSvg(lines, width, height)
                    }
                target.writeText(svg)
            }
            RenderFormat.PNG -> {
                val image =
                    if (content is RenderContent.Geometry) {
                        TriangleRasterizer.render(content.triangles, width, height)
                    } else {
                        TextCardRenderer.toImage(lines, width, height)
                    }
                val wrote = ImageIO.write(image, "png", target)
                if (!wrote) {
                    throw RenderIoException(outPath, IOException("no ImageIO writer available for 'png'"))
                }
            }
            RenderFormat.TEXT -> target.writeText(lines.joinToString("\n") + "\n")
            RenderFormat.AUTO -> error("unreachable -- format must be resolved before writeContainer")
        }
    } catch (e: IOException) {
        throw RenderIoException(outPath, e)
    }
}

private fun reportSuccess(
    command: CliCommand.Render,
    outPath: String,
    format: RenderFormat,
    content: RenderContent,
    occt: OcctAvailability,
    model: KStepModel?,
) {
    if (command.jsonOutput) {
        println(successJson(outPath, format, content, occt, model).toString())
    } else {
        println("Wrote $outPath")
    }
}

private fun reportFailure(
    command: CliCommand.Render,
    occt: OcctAvailability,
    content: RenderContent.Notice,
) {
    if (command.jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", "geometry_unavailable")
                put("command", "render")
                put("fallbackReason", content.reason)
                putOcct(occt)
            }.toString(),
        )
    } else {
        // The notice text itself already went to stderr (see finishRender's unconditional
        // Notice print, which runs before this function is ever reached) -- this is just the
        // --require-geometry-specific verdict on top of it.
        System.err.println("kstep render: --require-geometry set, geometry could not be rendered (${content.reason}).")
    }
    exitProcess(1)
}

/** Reports a [RenderIoException] the same way [reportFailure] reports a geometry-unavailable
 *  failure -- a clean, structured message (JSON or stderr text) and exit 1 -- instead of letting
 *  the underlying `IOException`'s raw Java stack trace reach the user. Mirrors `kstep export`'s
 *  own error-reporting shape (`status`/`errorKind`/`command` fields), matching Folge-Welle R-7's
 *  documented direction for I/O robustness ("catch IOException on write and report cleanly
 *  instead of a raw stack trace") -- applied here for `render` up front rather than left as the
 *  pre-existing gap R-7 describes for `export`. */
private fun reportIoFailure(
    command: CliCommand.Render,
    error: RenderIoException,
) {
    if (command.jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", "io_error")
                put("command", "render")
                put("message", error.message)
            }.toString(),
        )
    } else {
        println("Error: ${error.message}")
    }
    exitProcess(1)
}

private fun successJson(
    outPath: String,
    format: RenderFormat,
    content: RenderContent,
    occt: OcctAvailability,
    model: KStepModel?,
): JsonObject =
    buildJsonObject {
        put("status", "success")
        put("command", "render")
        put("outPath", outPath)
        put("format", format.name.lowercase())
        put(
            "content",
            when (content) {
                is RenderContent.Geometry -> "geometry"
                is RenderContent.Summary -> "summary"
                is RenderContent.Notice -> "notice"
            },
        )
        put("fallback", content is RenderContent.Notice)
        if (content is RenderContent.Notice) put("fallbackReason", content.reason)
        putJsonObject("geometry") {
            // model is null only for the "script threw OcctUnavailableException before stepFile
            // ever ran" fallback (renderNoModelOcctFallback) -- geometry was clearly being
            // attempted in that case (that is the only way this exceptionClass is thrown), so
            // `detected` is reported as true from context rather than from a KStepModel that was
            // never produced.
            put("detected", model?.hasGeometry ?: true)
            put("shapeCount", model?.shapes?.size ?: 0)
            if (content is RenderContent.Geometry) {
                put("previewedShapeIndex", content.previewedShapeIndex)
                put("triangleCount", content.triangles.size)
            }
        }
        putOcct(occt)
        put("rootCount", model?.roots?.size ?: 0)
    }

private fun JsonObjectBuilder.putOcct(occt: OcctAvailability) {
    putJsonObject("occt") {
        when (occt) {
            is OcctAvailability.Available -> {
                put("available", true)
                put("version", occt.occtVersion)
            }
            is OcctAvailability.Unavailable -> {
                put("available", false)
                put("reason", occt.reason)
            }
        }
    }
}

private fun printError(
    jsonOutput: Boolean,
    outcome: KStepScriptOutcome,
): Unit = printExportError(jsonOutput, outcome, command = "render")
