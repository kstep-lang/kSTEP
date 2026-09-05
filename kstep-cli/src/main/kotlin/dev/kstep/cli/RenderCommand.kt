package dev.kstep.cli

import dev.kstep.geometry.OcctAvailability
import dev.kstep.preview.PreviewContentKind
import dev.kstep.preview.PreviewEncodingException
import dev.kstep.preview.PreviewIoException
import dev.kstep.preview.PreviewOutcome
import dev.kstep.preview.PreviewRenderer
import dev.kstep.preview.PreviewRequest
import dev.kstep.preview.PreviewSource
import dev.kstep.preview.PreviewWriter
import dev.kstep.preview.deriveRenderOutputPath
import dev.kstep.render.gltf.GlbWriteResult
import dev.kstep.script.KStepScriptOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.system.exitProcess

/**
 * Thin CLI adapter for `kstep render`: builds a [PreviewRequest] from [CliCommand.Render], calls
 * [PreviewRenderer.render] (the shared implementation of ADR-0011's Container-Regel/
 * Pflicht-Fallback, extracted in ADR-0019 so `kstep asciidoc` can reuse it), writes the result via
 * [PreviewWriter], and renders the text/JSON report -- exactly the reports this command produced
 * before the extraction, byte-for-byte (see `CliRenderIntegrationTest`, which is unchanged by
 * this wave).
 */
fun runRender(command: CliCommand.Render) {
    val request =
        PreviewRequest(
            source = PreviewSource.ScriptFile(File(command.scriptPath)),
            format = command.format,
            width = command.width,
            height = command.height,
            withStep = command.withStep,
            outPathHint = command.outPath,
        )
    // ImageIO.write has no registered PNG writer on some stripped-down jlink runtime images (see
    // PreviewEncodingException's own KDoc) -- caught here so that failure mode degrades into the
    // same clean, structured error every other PreviewRenderer/PreviewWriter failure produces,
    // rather than an uncaught RuntimeException reaching main() as a raw Java stack trace. See
    // reportIoFailure's KDoc, which already documented this contract.
    val outcome =
        try {
            PreviewRenderer.render(request)
        } catch (e: PreviewEncodingException) {
            reportIoFailure(command, e.message ?: "unknown encoding error")
            return
        }
    when (outcome) {
        is PreviewOutcome.InvalidRequest -> {
            println(outcome.message)
            exitProcess(1)
        }
        is PreviewOutcome.ScriptFailed -> printError(command.jsonOutput, outcome.outcome)
        is PreviewOutcome.Rendered -> handleRendered(command, outcome)
    }
}

private fun handleRendered(
    command: CliCommand.Render,
    outcome: PreviewOutcome.Rendered,
) {
    if (outcome.contentKind == PreviewContentKind.NOTICE) {
        // Printed on stderr unconditionally -- even on the silent-fallback (exit 0) path -- so
        // an operator watching logs sees that a geometry render was requested but not actually
        // produced, without having to parse the rendered file itself. See
        // docs/adr/ADR-0011-headless-preview-rendering.adoc's R8/R9 test cases.
        outcome.warnings.forEach { System.err.println(it) }
        System.err.println("kstep render: geometry preview unavailable, falling back to a text card.")
        System.err.println("  reason: ${outcome.fallbackReason}")
        outcome.noticeLines.forEach { line -> System.err.println(line) }
    } else {
        outcome.warnings.forEach { System.err.println(it) }
    }

    if (outcome.contentKind == PreviewContentKind.NOTICE && command.requireGeometry) {
        reportFailure(command, outcome.occt, outcome.fallbackReason ?: "unknown")
        return
    }

    val outPath = command.outPath ?: deriveRenderOutputPath(command.scriptPath, outcome.format)
    try {
        PreviewWriter.write(outPath, outcome.bytes)
    } catch (e: PreviewIoException) {
        reportIoFailure(command, e.message ?: "unknown I/O error")
        return
    }
    reportSuccess(command, outPath, outcome)
}

private fun reportSuccess(
    command: CliCommand.Render,
    outPath: String,
    outcome: PreviewOutcome.Rendered,
) {
    if (command.jsonOutput) {
        println(successJson(outPath, outcome).toString())
    } else {
        println("Wrote $outPath")
    }
}

private fun reportFailure(
    command: CliCommand.Render,
    occt: OcctAvailability,
    fallbackReason: String,
) {
    if (command.jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", "geometry_unavailable")
                put("command", "render")
                put("fallbackReason", fallbackReason)
                putOcct(occt)
            }.toString(),
        )
    } else {
        // The notice text itself already went to stderr (see handleRendered's unconditional
        // Notice print, which runs before this function is ever reached) -- this is just the
        // --require-geometry-specific verdict on top of it.
        System.err.println("kstep render: --require-geometry set, geometry could not be rendered ($fallbackReason).")
    }
    exitProcess(1)
}

/** Reports a [PreviewIoException] (or a [dev.kstep.preview.PreviewEncodingException]'s message)
 *  the same way [reportFailure] reports a geometry-unavailable failure -- a clean, structured
 *  message (JSON or stderr text) and exit 1 -- instead of letting the underlying `IOException`'s
 *  raw Java stack trace reach the user. Mirrors `kstep export`'s own error-reporting shape
 *  (`status`/`errorKind`/`command` fields). */
private fun reportIoFailure(
    command: CliCommand.Render,
    message: String,
) {
    if (command.jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", "io_error")
                put("command", "render")
                put("message", message)
            }.toString(),
        )
    } else {
        println("Error: $message")
    }
    exitProcess(1)
}

private fun successJson(
    outPath: String,
    outcome: PreviewOutcome.Rendered,
): JsonObject =
    buildJsonObject {
        put("status", "success")
        put("command", "render")
        put("outPath", outPath)
        put("format", outcome.format.name.lowercase())
        put(
            "content",
            when (outcome.contentKind) {
                PreviewContentKind.GEOMETRY -> "geometry"
                PreviewContentKind.SUMMARY -> "summary"
                PreviewContentKind.NOTICE -> "notice"
            },
        )
        put("fallback", outcome.contentKind == PreviewContentKind.NOTICE)
        if (outcome.contentKind == PreviewContentKind.NOTICE) put("fallbackReason", outcome.fallbackReason)
        putJsonObject("geometry") {
            // model is null only for the "script threw OcctUnavailableException before stepFile
            // ever ran" fallback -- geometry was clearly being attempted in that case (that is
            // the only way this exceptionClass is thrown), so `detected` is reported as true from
            // context rather than from a model that was never produced.
            put("detected", outcome.model?.hasGeometry ?: true)
            put("shapeCount", outcome.model?.shapeCount ?: 0)
            outcome.geometry?.let { geometry ->
                put("previewedShapeIndex", geometry.previewedShapeIndex)
                put("triangleCount", geometry.projectedTriangleCount)
                put("meshTriangleCount", geometry.meshTriangleCount)
            }
        }
        putOcct(outcome.occt)
        put("rootCount", outcome.model?.rootCount ?: 0)
        outcome.glb?.let { glbResult -> putGlb(glbResult) }
    }

private fun JsonObjectBuilder.putGlb(glbResult: GlbWriteResult) {
    putJsonObject("glb") {
        put("triangleCount", glbResult.triangleCount)
        put("vertexCount", glbResult.vertexCount)
        put("droppedTriangleCount", glbResult.droppedTriangleCount)
        put("byteLength", glbResult.byteLength)
    }
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
