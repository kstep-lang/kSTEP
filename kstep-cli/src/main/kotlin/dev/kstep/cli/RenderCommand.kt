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
import dev.kstep.preview.RenderedAssemblyTree
import dev.kstep.preview.RenderedPart
import dev.kstep.preview.RenderedTreeNode
import dev.kstep.preview.deriveRenderOutputPath
import dev.kstep.render.export.MeshExportResult
import dev.kstep.render.gltf.GlbWriteResult
import dev.kstep.script.KStepScriptOutcome
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
fun runRender(
    command: CliCommand.Render,
    includeParts: Boolean = false,
) {
    val request =
        PreviewRequest(
            source = PreviewSource.ScriptFile(File(command.scriptPath)),
            format = command.format,
            width = command.width,
            height = command.height,
            withStep = command.withStep,
            outPathHint = command.outPath,
            meshToleranceMm = command.toleranceMm,
            requireWatertight = command.requireWatertight,
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
        } catch (e: IllegalArgumentException) {
            // A hostile/invalid mesh made the STL/3MF writer fail closed (ADR-0049 D8).
            reportMeshInvalid(command, e.message ?: "invalid mesh")
            return
        }
    when (outcome) {
        is PreviewOutcome.InvalidRequest -> {
            println(outcome.message)
            exitProcess(1)
        }
        is PreviewOutcome.ScriptFailed -> printError(command.jsonOutput, outcome.outcome)
        is PreviewOutcome.Rendered -> handleRendered(command, outcome, includeParts)
    }
}

private fun handleRendered(
    command: CliCommand.Render,
    outcome: PreviewOutcome.Rendered,
    includeParts: Boolean,
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

    if (outcome.format.isMeshExport && outcome.meshExport == null) {
        // ADR-0049 D9: no printer file without geometry (never a text card inside .stl/.3mf).
        reportFailure(
            command,
            outcome.occt,
            outcome.fallbackReason ?: "no_printable_geometry",
        )
        return
    }
    outcome.meshExport?.let { export ->
        if (command.requireWatertight && !export.watertight) {
            reportNotWatertight(command, export)
            return
        }
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
    reportSuccess(command, outPath, outcome, includeParts)
}

private fun reportSuccess(
    command: CliCommand.Render,
    outPath: String,
    outcome: PreviewOutcome.Rendered,
    includeParts: Boolean,
) {
    if (command.jsonOutput) {
        println(successJson(outPath, outcome, command.toleranceMm, includeParts).toString())
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

private fun reportNotWatertight(
    command: CliCommand.Render,
    export: MeshExportResult,
) {
    if (command.jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", "mesh_not_watertight")
                put("command", "render")
                put("openEdgeCount", export.openEdgeCount)
                put("nonManifoldEdgeCount", export.nonManifoldEdgeCount)
            }.toString(),
        )
    } else {
        System.err.println(
            "kstep render: --require-watertight set, mesh is not watertight " +
                "(${export.openEdgeCount} open edge(s), ${export.nonManifoldEdgeCount} non-manifold edge(s)); no file written.",
        )
    }
    exitProcess(1)
}

private fun reportMeshInvalid(
    command: CliCommand.Render,
    message: String,
) {
    if (command.jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", "mesh_export_invalid")
                put("command", "render")
                put("message", message)
            }.toString(),
        )
    } else {
        println("Error: $message")
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

internal fun successJson(
    outPath: String,
    outcome: PreviewOutcome.Rendered,
    toleranceMm: Double?,
    includeParts: Boolean = false,
): JsonObject =
    buildJsonObject {
        put("status", "success")
        put("command", "render")
        put("outPath", outPath)
        put("format", outcome.format.id)
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
                put("instanceCount", geometry.instanceCount)
                put("distinctShapeCount", geometry.distinctShapeCount)
                put("triangleCount", geometry.projectedTriangleCount)
                put("meshTriangleCount", geometry.meshTriangleCount)
            }
        }
        putOcct(outcome.occt)
        put("rootCount", outcome.model?.rootCount ?: 0)
        outcome.glb?.let { glbResult -> putGlb(glbResult) }
        if (includeParts) {
            outcome.parts?.let { parts ->
                putParts(parts)
                if (parts.size <= DesktopPartLimits.MAX_PARTS) outcome.tree?.let { putTree(it) }
            }
            // ADR-0063: declared export names go to the desktop worker only, never to the public JSON.
            if (outcome.exportTargets.isNotEmpty()) {
                putJsonArray("exportTargets") {
                    for (target in outcome.exportTargets) {
                        addJsonObject {
                            put("format", target.format)
                            put("fileName", target.fileName)
                        }
                    }
                }
            }
        }
        outcome.meshExport?.let { export -> putMeshExport(export, toleranceMm) }
    }

private fun JsonObjectBuilder.putMeshExport(
    export: MeshExportResult,
    toleranceMm: Double?,
) {
    put("meshExport", meshExportJson(export, toleranceMm))
}

/** The `meshExport` object of a STL/3MF result; shared by `kstep render` and `kstep export --target` (ADR-0065). */
internal fun meshExportJson(
    export: MeshExportResult,
    toleranceMm: Double?,
): JsonObject =
    buildJsonObject {
        put("format", export.format)
        put("unit", "mm")
        put("triangleCount", export.triangleCount)
        put("vertexCount", export.vertexCount)
        put("droppedTriangleCount", export.droppedTriangleCount)
        put("byteLength", export.byteLength)
        put("partCount", export.partCount)
        put("buildItemCount", export.buildItemCount)
        put("watertight", export.watertight)
        put("openEdgeCount", export.openEdgeCount)
        put("nonManifoldEdgeCount", export.nonManifoldEdgeCount)
        put("colorsDropped", export.colorsDropped)
        put("toleranceMm", toleranceMm)
        putJsonArray("warnings") { export.warnings.forEach { add(it) } }
    }

/** Desktop-worker only (ADR-0053): the part list of the written GLB; never part of the public `kstep render` JSON. */
internal fun JsonObjectBuilder.putParts(parts: List<RenderedPart>) {
    if (parts.size > DesktopPartLimits.MAX_PARTS) {
        put("parts", JsonNull)
        putJsonObject("partsOmitted") {
            put("reason", "too_many_parts")
            put("count", parts.size)
        }
        return
    }
    putJsonArray("parts") {
        for (part in parts) {
            addJsonObject {
                put("name", truncateName(part.name))
                put("firstTriangle", part.firstTriangle)
                put("triangleCount", part.triangleCount)
                putJsonArray("placement") { part.placement.rowMajor3x4().forEach { add(it) } }
                val c = part.color
                put("color", "#%02X%02X%02X".format(channel(c.r), channel(c.g), channel(c.b)))
            }
        }
    }
}

/**
 * Desktop-worker only (ADR-0054): the assembly hierarchy over the part list, pre-order. Over the limits the tree is
 * left out (`"tree": null` plus `treeOmitted`); the flat part list stays usable.
 */
internal fun JsonObjectBuilder.putTree(tree: RenderedAssemblyTree) {
    val nodes = tree.nodes
    val reason =
        when {
            nodes.size > DesktopPartLimits.MAX_TREE_NODES -> "too_many_nodes"
            treeDepth(nodes) > DesktopPartLimits.MAX_TREE_DEPTH -> "too_deep"
            else -> null
        }
    if (reason != null) {
        put("tree", JsonNull)
        putJsonObject("treeOmitted") {
            put("reason", reason)
            put("count", nodes.size)
        }
        return
    }
    putJsonObject("tree") {
        putJsonArray("nodes") {
            for (node in nodes) {
                addJsonObject {
                    put("name", truncateName(node.name))
                    put("parent", node.parent)
                    put("part", node.part)
                }
            }
        }
    }
}

private fun treeDepth(nodes: List<RenderedTreeNode>): Int {
    val depth = IntArray(nodes.size)
    var max = 0
    for ((i, node) in nodes.withIndex()) {
        depth[i] = if (node.parent < 0) 0 else depth[node.parent] + 1
        if (depth[i] > max) max = depth[i]
    }
    return max
}

private fun channel(v: Double): Int = Math.round(v * 255.0).toInt().coerceIn(0, 255)

private fun truncateName(name: String): String {
    if (name.length <= DesktopPartLimits.MAX_NAME_CHARS) return name
    var end = DesktopPartLimits.MAX_NAME_CHARS
    if (Character.isHighSurrogate(name[end - 1])) end--
    return name.substring(0, end)
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

/** Limits shared by the desktop worker (writer) and the desktop app (validator) for the part list (ADR-0053). */
object DesktopPartLimits {
    const val MAX_PARTS: Int = 4096
    const val MAX_NAME_CHARS: Int = 256

    /** Assembly tree (ADR-0054): groups come on top of the parts, so the node limit is twice the part limit. */
    const val MAX_TREE_NODES: Int = 8192
    const val MAX_TREE_DEPTH: Int = 64
}
