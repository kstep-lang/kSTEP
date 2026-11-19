package dev.kstep.cli

import dev.kstep.cli.okf.ConvertFiles
import dev.kstep.cli.okf.DisplayName
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.preview.PreviewContentKind
import dev.kstep.preview.PreviewEncodingException
import dev.kstep.preview.PreviewOutcome
import dev.kstep.preview.PreviewRenderer
import dev.kstep.preview.PreviewRequest
import dev.kstep.preview.PreviewSource
import dev.kstep.preview.RenderFormat
import dev.kstep.script.KStepModel
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.script.KStepScriptOutcomeCodes
import dev.kstep.script.ScriptExportFormat
import dev.kstep.script.ScriptExportNames
import dev.kstep.script.ScriptExportTarget
import dev.kstep.step21.Part21EncodingException
import dev.kstep.step21.Part21LimitExceededException
import dev.kstep.step21.Part21WriteException
import dev.kstep.step21.Part21Writer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

// `kstep export --target/--all-targets` (ADR-0065): writes the targets a script declares in its
// `export { }` block. Everything is checked and produced in memory first; only then is any file
// written (each one atomically through ConvertFiles.writeAtomic, shared with `kstep convert`).
// Plain println/System.err like the rest of the CLI's own result rendering (see Main.kt).

internal enum class ExportTargetsErrorKind(
    val id: String,
) {
    NO_EXPORT_TARGETS("no_export_targets"),
    TARGET_NOT_DECLARED("export_target_not_declared"),
    TARGET_INVALID("export_target_invalid"),
    INVALID_REQUEST("invalid_request"),
    OUT_DIR_INVALID("out_dir_invalid"),
    TARGET_EXISTS("target_exists"),
    TARGETS_CHANGED("export_targets_changed"),
    GEOMETRY_UNAVAILABLE("geometry_unavailable"),
    MESH_EXPORT_INVALID("mesh_export_invalid"),
    MESH_EXPORT_FAILED("geometry_export_failed"),
    WRITE_FAILED("write_failed"),
}

internal sealed interface TargetSelectionResult {
    data class Ok(
        val targets: List<ScriptExportTarget>,
    ) : TargetSelectionResult

    data class Failed(
        val kind: ExportTargetsErrorKind,
        val message: String,
    ) : TargetSelectionResult
}

private val FORMAT_IDS: Map<String, ScriptExportFormat> = ScriptExportFormat.entries.associateBy { it.id }

private fun shown(name: String): String = DisplayName.escaped(name)

/**
 * Pure. Re-validates ALL declared targets (the public constructor of [ScriptExportTarget] checks nothing, so a model may
 * carry names the `export { }` builder would have refused): every name through [ScriptExportNames.validate], formats
 * and (case-insensitive) names unique, at most [ScriptExportNames.MAX_TARGETS]. One bad entry rejects the whole list
 * (ADR-0063 D5). Then selects according to [selection], in selection order.
 */
internal fun selectExportTargets(
    declared: List<ScriptExportTarget>,
    selection: ExportTargetSelection,
): TargetSelectionResult {
    if (declared.isEmpty()) {
        return TargetSelectionResult.Failed(
            ExportTargetsErrorKind.NO_EXPORT_TARGETS,
            "the script declares no export { } targets",
        )
    }
    val invalid = invalidReason(declared)
    if (invalid != null) {
        return TargetSelectionResult.Failed(
            ExportTargetsErrorKind.TARGET_INVALID,
            "the declared export targets are not valid ($invalid); nothing was written",
        )
    }
    return when (selection) {
        ExportTargetSelection.All -> TargetSelectionResult.Ok(declared)
        is ExportTargetSelection.Named -> {
            val chosen = ArrayList<ScriptExportTarget>()
            for (selector in selection.selectors) {
                val byFormat = FORMAT_IDS[selector]
                val match =
                    if (byFormat != null) {
                        declared.firstOrNull { it.format == byFormat }
                    } else {
                        declared.firstOrNull { it.fileName == selector }
                    }
                if (match == null) {
                    return TargetSelectionResult.Failed(
                        ExportTargetsErrorKind.TARGET_NOT_DECLARED,
                        "the script does not declare an export target \"${shown(selector)}\"",
                    )
                }
                if (match in chosen) {
                    return TargetSelectionResult.Failed(
                        ExportTargetsErrorKind.INVALID_REQUEST,
                        "\"${shown(selector)}\" selects a target that is already selected",
                    )
                }
                chosen += match
            }
            TargetSelectionResult.Ok(chosen)
        }
    }
}

private fun invalidReason(declared: List<ScriptExportTarget>): String? {
    if (declared.size > ScriptExportNames.MAX_TARGETS) return "more than ${ScriptExportNames.MAX_TARGETS} targets"
    if (declared.map { it.format }.toSet().size != declared.size) return "a format is declared twice"
    if (declared.map { it.fileName.lowercase() }.toSet().size != declared.size) return "a file name is declared twice"
    for (t in declared) {
        try {
            ScriptExportNames.validate(t.format, t.fileName)
        } catch (e: IllegalArgumentException) {
            return "\"${shown(t.fileName)}\": ${e.message}"
        }
    }
    return null
}

/** The STEP text of a model, without any printing or exiting. */
internal sealed interface StepProduction {
    data class Ok(
        val text: String,
        val geometry: GeometryReport?,
        /** Lines for stderr, printed before the file is written. */
        val stderrNotes: List<String>,
    ) : StepProduction

    data class Failed(
        val errorKind: String,
        val code: String?,
        val message: String,
        /** Non-null for a Part 21 writer failure: reported like a script runtime error. */
        val runtime: KStepScriptOutcome.RuntimeError?,
    ) : StepProduction
}

internal fun produceStepText(
    model: KStepModel,
    requireGeometry: Boolean,
): StepProduction {
    val shapeCount = model.shapes.size
    // Only probe the native bridge when geometry is actually in play.
    val occt = if (shapeCount > 0) OcctKernel.availability() else OcctAvailability.Unavailable("not queried")
    val placementCount = model.placements.size
    val plan = planExportGeometry(shapeCount, requireGeometry, occt, placementCount, model.usageStyles.size)
    return when (plan) {
        is ExportGeometryPlan.Fail -> StepProduction.Failed(plan.errorKind, plan.code, plan.message, null)
        is ExportGeometryPlan.Merge -> {
            val merged = mergeGeometry(model)
            val text = merged.getOrNull()
            if (text == null) {
                StepProduction.Failed(
                    "geometry_export_failed",
                    KStepScriptOutcomeCodes.GEOMETRY_EXPORT_FAILED,
                    merged.exceptionOrNull()?.message ?: "geometry export failed",
                    null,
                )
            } else {
                StepProduction.Ok(
                    text,
                    GeometryReport(
                        merged = true,
                        shapeCount = shapeCount,
                        occt = occt,
                        placementCount = placementCount,
                        usageCount = countPlacedUsages(text),
                    ),
                    emptyList(),
                )
            }
        }
        is ExportGeometryPlan.StructureOnly -> {
            val text =
                try {
                    Part21Writer.write(model.header, model.roots)
                } catch (e: Part21WriteException) {
                    return StepProduction.Failed("runtime_error", null, e.message.orEmpty(), e.toRuntimeError())
                } catch (e: Part21EncodingException) {
                    return StepProduction.Failed("runtime_error", null, e.message.orEmpty(), e.toRuntimeError())
                } catch (e: Part21LimitExceededException) {
                    return StepProduction.Failed("runtime_error", null, e.message.orEmpty(), e.toRuntimeError())
                }
            val notes = ArrayList<String>()
            plan.ignoredPlacementsNote?.let { notes += "kstep export: $it." }
            var geometry: GeometryReport? = null
            if (plan.unmergedReason != null) {
                notes +=
                    "kstep export: geometry NOT written: OCCT unavailable (${plan.unmergedReason}); " +
                    "the file contains the product structure only."
                geometry = GeometryReport(false, shapeCount, occt, plan.unmergedReason, placementCount)
            }
            StepProduction.Ok(text, geometry, notes)
        }
    }
}

internal fun stepSuccessText(
    rootCount: Int,
    shapeCount: Int,
    geometry: GeometryReport?,
    outPath: String,
): String =
    if (geometry?.merged == true) {
        val placed = geometry.usageCount
        val placedText = if (placed > 0) " and $placed placed usage(s)" else ""
        "Exported $rootCount root(s) with $shapeCount geometric shape(s)$placedText to $outPath"
    } else {
        "Exported $rootCount root(s) to $outPath"
    }

internal class ProducedTarget(
    val target: ScriptExportTarget,
    val bytes: ByteArray,
    /** The `meshExport` object of an STL/3MF target, `null` otherwise. */
    val meshExport: JsonObject?,
)

internal sealed interface MeshProduction {
    class Ok(
        val produced: ProducedTarget,
        val warnings: List<String>,
    ) : MeshProduction

    data class Failed(
        val kind: ExportTargetsErrorKind,
        val message: String,
    ) : MeshProduction

    data class ScriptFailed(
        val outcome: KStepScriptOutcome,
    ) : MeshProduction
}

private fun renderFormatOf(format: ScriptExportFormat): RenderFormat =
    when (format) {
        ScriptExportFormat.GLB -> RenderFormat.GLB
        ScriptExportFormat.STL -> RenderFormat.STL
        ScriptExportFormat.THREE_MF -> RenderFormat.THREE_MF
        ScriptExportFormat.STEP -> error("STEP is produced by produceStepText, not by the preview renderer")
    }

/**
 * A mesh target through [PreviewRenderer], like `kstep render -f <format>` (the renderer evaluates the script itself and
 * closes the shapes it registered, so the planning model cannot be handed in). Like the desktop worker it needs real
 * geometry, never a text card inside a `.glb`/`.stl`/`.3mf`. [planned] are the targets of the planning evaluation; if
 * this evaluation declares something else the script is not deterministic and nothing may be written.
 */
internal fun produceMeshTarget(
    command: CliCommand.ExportTargets,
    target: ScriptExportTarget,
    planned: List<ScriptExportTarget>,
): MeshProduction {
    val format = renderFormatOf(target.format)
    val request =
        PreviewRequest(
            source = PreviewSource.ScriptFile(File(command.scriptPath)),
            format = format,
            width = DEFAULT_WIDTH,
            height = DEFAULT_HEIGHT,
            withStep = false,
            outPathHint = null,
            meshToleranceMm = if (format.isMeshExport) command.toleranceMm else null,
        )
    val outcome =
        try {
            PreviewRenderer.render(request)
        } catch (e: PreviewEncodingException) {
            return MeshProduction.Failed(ExportTargetsErrorKind.WRITE_FAILED, e.message ?: "encoding error")
        } catch (e: IllegalArgumentException) {
            // A hostile/invalid mesh made the STL/3MF writer fail closed (ADR-0049 D8).
            return MeshProduction.Failed(ExportTargetsErrorKind.MESH_EXPORT_INVALID, e.message ?: "invalid mesh")
        }
    return when (outcome) {
        is PreviewOutcome.InvalidRequest ->
            MeshProduction.Failed(ExportTargetsErrorKind.INVALID_REQUEST, outcome.message.removePrefix("Error: "))
        is PreviewOutcome.ScriptFailed -> MeshProduction.ScriptFailed(outcome.outcome)
        is PreviewOutcome.Rendered -> {
            val unavailable =
                outcome.contentKind != PreviewContentKind.GEOMETRY ||
                    (format.isMeshExport && outcome.meshExport == null)
            if (unavailable) {
                MeshProduction.Failed(
                    ExportTargetsErrorKind.GEOMETRY_UNAVAILABLE,
                    "no geometry for ${shown(
                        target.fileName,
                    )} (${outcome.fallbackReason ?: "no_printable_geometry"}); " +
                        "nothing was written",
                )
            } else if (outcome.exportTargets.map { it.format to it.fileName } !=
                planned.map { it.format.id to it.fileName }
            ) {
                MeshProduction.Failed(
                    ExportTargetsErrorKind.TARGETS_CHANGED,
                    "the script declared different export targets on a second evaluation; nothing was written",
                )
            } else {
                MeshProduction.Ok(
                    ProducedTarget(
                        target,
                        outcome.bytes,
                        outcome.meshExport?.let { meshExportJson(it, command.toleranceMm) },
                    ),
                    outcome.warnings,
                )
            }
        }
    }
}

private const val DEFAULT_WIDTH = 1024
private const val DEFAULT_HEIGHT = 768
private const val TEMP_PREFIX = ".kstep-export-"

internal class WrittenTarget(
    val produced: ProducedTarget,
    val path: Path,
    val replaced: Boolean,
)

/** Pure: the `--output json` document of a successful run. */
internal fun exportTargetsReportJson(
    outDir: Path,
    rootCount: Int,
    shapeCount: Int,
    written: List<WrittenTarget>,
    validation: JsonElement?,
    geometry: JsonElement?,
): JsonObject =
    buildJsonObject {
        put("status", "success")
        put("command", "export")
        put("outDir", outDir.toString())
        put("rootCount", rootCount)
        put("shapeCount", shapeCount)
        putJsonArray("targets") {
            for (w in written) {
                add(
                    buildJsonObject {
                        put("format", w.produced.target.format.id)
                        put("fileName", w.produced.target.fileName)
                        put("outPath", w.path.toString())
                        put("byteLength", w.produced.bytes.size)
                        put("replaced", w.replaced)
                        w.produced.meshExport?.let { put("meshExport", it) }
                    },
                )
            }
        }
        if (validation != null) put("validation", validation)
        if (geometry != null) put("geometry", geometry)
    }

private fun report(
    json: Boolean,
    kind: ExportTargetsErrorKind,
    message: String,
    written: List<String> = emptyList(),
    code: String? = null,
): Nothing {
    if (json) {
        println(
            buildJsonObject {
                put("status", "error")
                put("command", "export")
                put("errorKind", kind.id)
                if (code != null) put("code", code)
                put("message", message)
                if (written.isNotEmpty()) putJsonArray("written") { written.forEach { add(it) } }
            }.toString(),
        )
    } else {
        System.err.println("kstep export: $message")
        if (written.isNotEmpty()) System.err.println("kstep export: already written: ${written.joinToString(", ")}")
    }
    exitProcess(1)
}

/** Prints and exits 1 on any error; nothing is written before every selected target is checked and produced. */
internal fun runExportTargets(command: CliCommand.ExportTargets) {
    val json = command.jsonOutput
    val outcome = KStepScriptHost.eval(File(command.scriptPath))
    val model =
        when (outcome) {
            is KStepScriptOutcome.Success -> outcome.model
            else -> printExportError(json, outcome, "export")
        }

    // Shapes are released on every path; exitProcess skips `finally`, so every failure closes explicitly first.
    fun fail(
        kind: ExportTargetsErrorKind,
        message: String,
        code: String? = null,
    ): Nothing {
        closeShapes(model)
        report(json, kind, message, code = code)
    }

    val targets =
        when (val selected = selectExportTargets(model.exportTargets, command.selection)) {
            is TargetSelectionResult.Ok -> selected.targets
            is TargetSelectionResult.Failed -> fail(selected.kind, selected.message)
        }

    command.toleranceMm?.let { tol ->
        if (!(tol.isFinite() && tol > 0.0)) {
            fail(ExportTargetsErrorKind.INVALID_REQUEST, "--tolerance must be a finite number greater than 0, got $tol")
        }
        if (targets.none { it.format == ScriptExportFormat.STL || it.format == ScriptExportFormat.THREE_MF }) {
            fail(ExportTargetsErrorKind.INVALID_REQUEST, "--tolerance applies only to stl and 3mf targets")
        }
    }

    val outDirPath =
        try {
            if (command.outDir !=
                null
            ) {
                Paths.get(command.outDir)
            } else {
                Paths.get(command.scriptPath).toAbsolutePath().parent
            }
        } catch (_: InvalidPathException) {
            fail(ExportTargetsErrorKind.OUT_DIR_INVALID, "the output folder is not a valid path")
        } ?: fail(ExportTargetsErrorKind.OUT_DIR_INVALID, "the output folder cannot be determined")
    val outReal =
        when (val dir = ConvertFiles.resolveOutDir(outDirPath)) {
            is ConvertFiles.DirResult.Ok -> dir.real
            is ConvertFiles.DirResult.Failed -> fail(ExportTargetsErrorKind.OUT_DIR_INVALID, dir.message)
        }
    val scriptReal =
        try {
            Paths.get(command.scriptPath).toRealPath()
        } catch (_: java.io.IOException) {
            Paths.get(command.scriptPath).toAbsolutePath()
        } catch (_: InvalidPathException) {
            Paths.get(command.scriptPath).toAbsolutePath()
        }

    val exists = HashSet<String>()
    for (target in targets) {
        when (val check = ConvertFiles.checkTarget(outReal, target.fileName, scriptReal)) {
            is ConvertFiles.TargetCheck.Invalid ->
                fail(ExportTargetsErrorKind.TARGET_INVALID, "${shown(target.fileName)}: ${check.message}")
            is ConvertFiles.TargetCheck.Exists -> exists += target.fileName
            is ConvertFiles.TargetCheck.Free -> Unit
        }
    }
    if (exists.isNotEmpty() && !command.force) {
        fail(
            ExportTargetsErrorKind.TARGET_EXISTS,
            "${exists.joinToString(
                ", ",
            ) { shown(it) }} already exists; use --force to replace it. Nothing was written.",
        )
    }

    val rootCount = model.roots.size
    val shapeCount = model.shapes.size
    val validation =
        if (command.validate) validateForExport(model, json) { closeShapes(model) } else null

    val stepTarget = targets.firstOrNull { it.format == ScriptExportFormat.STEP }
    var stepProduction: StepProduction.Ok? = null
    if (stepTarget != null) {
        when (val step = produceStepText(model, command.requireGeometry)) {
            is StepProduction.Ok -> stepProduction = step
            is StepProduction.Failed -> {
                closeShapes(model)
                val runtime = step.runtime
                if (runtime != null) printExportError(json, runtime, "export")
                printGeometryExportError(json, step.errorKind, step.code.orEmpty(), step.message)
            }
        }
    }
    closeShapes(model)

    val produced = ArrayList<ProducedTarget>()
    for (target in targets) {
        if (target.format == ScriptExportFormat.STEP) {
            produced += ProducedTarget(target, stepProduction!!.text.toByteArray(Charsets.UTF_8), null)
            continue
        }
        when (val mesh = produceMeshTarget(command, target, model.exportTargets)) {
            is MeshProduction.Ok -> {
                mesh.warnings.forEach { System.err.println("kstep export: $it") }
                produced += mesh.produced
            }
            is MeshProduction.Failed -> report(json, mesh.kind, mesh.message)
            is MeshProduction.ScriptFailed -> printExportError(json, mesh.outcome, "export")
        }
    }
    stepProduction?.stderrNotes?.forEach { System.err.println(it) }

    val written = ArrayList<WrittenTarget>()
    for (p in produced) {
        val name = p.target.fileName
        val replaced = name in exists
        when (val r = ConvertFiles.writeAtomic(outReal, name, p.bytes, command.force, TEMP_PREFIX)) {
            ConvertFiles.WriteResult.Written -> written += WrittenTarget(p, outReal.resolve(name), replaced)
            ConvertFiles.WriteResult.AlreadyExists ->
                report(
                    json,
                    ExportTargetsErrorKind.TARGET_EXISTS,
                    "${shown(name)} appeared meanwhile; use --force to replace it",
                    written.map { shown(it.produced.target.fileName) },
                )
            is ConvertFiles.WriteResult.Failed ->
                report(
                    json,
                    ExportTargetsErrorKind.WRITE_FAILED,
                    "${shown(name)}: ${r.message}",
                    written.map { shown(it.produced.target.fileName) },
                )
        }
    }

    if (json) {
        println(
            exportTargetsReportJson(
                outReal,
                rootCount,
                shapeCount,
                written,
                validation?.let { validationSummaryJson(it) },
                stepProduction?.geometry?.let { geometryJson(it) },
            ).toString(),
        )
    } else {
        for (w in written) {
            if (w.produced.target.format == ScriptExportFormat.STEP) {
                println(stepSuccessText(rootCount, shapeCount, stepProduction?.geometry, w.path.toString()))
            } else {
                println("Wrote ${w.path}")
            }
        }
        if (validation != null) println(validationSummaryText(validation))
    }
}
