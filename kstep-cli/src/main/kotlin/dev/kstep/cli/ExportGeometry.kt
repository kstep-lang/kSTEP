package dev.kstep.cli

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.script.KStepModel
import dev.kstep.script.KStepScriptOutcomeCodes
import dev.kstep.shape.Ap242ShapeExporter
import dev.kstep.shape.ShapeExportException
import dev.kstep.step21.Part21EncodingException
import dev.kstep.step21.Part21LimitExceededException
import dev.kstep.step21.Part21WriteException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.system.exitProcess

// `kstep export` with geometry (ADR-0027). The decision logic is a pure function so it can be unit
// tested without OCCT; the actual merge lives in Ap242ShapeExporter.

/** What `kstep export` does about the script's `shape(...)` registrations. */
internal sealed interface ExportGeometryPlan {
    /** Write the product structure only (`Part21Writer.write`, byte-identical to pre-ADR-0027). */
    data class StructureOnly(
        /** Non-null when shapes were registered but could not be merged (stderr warning text). */
        val unmergedReason: String? = null,
        /** Non-null when `place(...)` calls were made but no geometry is merged, so they have no effect. */
        val ignoredPlacementsNote: String? = null,
    ) : ExportGeometryPlan

    /** Merge every registered shape via [Ap242ShapeExporter]. */
    data object Merge : ExportGeometryPlan

    /** Fail closed (exit 1, nothing written). */
    data class Fail(
        val errorKind: String,
        val code: String,
        val message: String,
    ) : ExportGeometryPlan
}

internal fun planExportGeometry(
    shapeCount: Int,
    requireGeometry: Boolean,
    occt: OcctAvailability,
    placementCount: Int = 0,
): ExportGeometryPlan =
    when {
        shapeCount == 0 && requireGeometry ->
            ExportGeometryPlan.Fail(
                "geometry_required",
                KStepScriptOutcomeCodes.GEOMETRY_REQUIRED,
                "--require-geometry set, but the script registers no shape(...). Nothing was written.",
            )
        shapeCount == 0 ->
            ExportGeometryPlan.StructureOnly(
                ignoredPlacementsNote =
                    placementCount
                        .takeIf {
                            it > 0
                        }?.let { "$it placement(s) ignored: the script registers no shape(...)" },
            )
        occt is OcctAvailability.Available -> ExportGeometryPlan.Merge
        requireGeometry ->
            ExportGeometryPlan.Fail(
                "geometry_unavailable",
                KStepScriptOutcomeCodes.GEOMETRY_UNAVAILABLE,
                "--require-geometry set, but OCCT is unavailable (${(occt as OcctAvailability.Unavailable).reason}). " +
                    "Nothing was written.",
            )
        else ->
            ExportGeometryPlan.StructureOnly(
                (occt as OcctAvailability.Unavailable).reason,
                placementCount.takeIf { it > 0 }?.let {
                    "$it placement(s) ignored: no geometry was merged (OCCT unavailable)"
                },
            )
    }

/** Result of the geometry step, for the success report. */
internal data class GeometryReport(
    val merged: Boolean,
    val shapeCount: Int,
    val occt: OcctAvailability,
    val unmergedReason: String? = null,
    /** Number of `place(...)` registrations of the model. */
    val placementCount: Int = 0,
    /** Number of assembly usages that received a placement chain (CDSR) in the merged file. */
    val usageCount: Int = 0,
)

/**
 * Merges the model's shapes into one Part-21 text. Failures of the merge itself (as opposed to
 * "OCCT is not available", which is decided by [planExportGeometry]) are NOT silently downgraded
 * to a structure-only file: they are returned as an error message (fail closed).
 */
internal fun mergeGeometry(model: KStepModel): Result<String> =
    try {
        Result.success(Ap242ShapeExporter.export(model.header, model.roots, model.shapes, model.placements))
    } catch (e: ShapeExportException) {
        Result.failure(GeometryExportFailure(e.message ?: "geometry export failed"))
    } catch (e: OcctGeometryException) {
        Result.failure(GeometryExportFailure("OCCT failed to produce STEP geometry (${e::class.simpleName})"))
    } catch (e: OcctUnavailableException) {
        Result.failure(GeometryExportFailure("OCCT became unavailable during export (${e::class.simpleName})"))
    } catch (e: IllegalStateException) {
        Result.failure(
            GeometryExportFailure(
                "a registered shape is no longer usable, probably already closed " +
                    "(do not wrap shapes passed to shape(...) in use { }): ${e::class.simpleName}",
            ),
        )
    } catch (e: Part21WriteException) {
        Result.failure(GeometryExportFailure(e.message ?: "Part 21 write failed"))
    } catch (e: Part21EncodingException) {
        Result.failure(GeometryExportFailure(e.message ?: "Part 21 encoding failed"))
    } catch (e: Part21LimitExceededException) {
        Result.failure(GeometryExportFailure(e.message ?: "Part 21 limit exceeded"))
    }

/** Number of placed assembly usages in a merged file (one `CONTEXT_DEPENDENT_SHAPE_REPRESENTATION` instance head each; anchored at line start so string literals cannot inflate it). */
internal fun countPlacedUsages(text: String): Int =
    Regex("^#\\d+\\s*=\\s*CONTEXT_DEPENDENT_SHAPE_REPRESENTATION\\(", RegexOption.MULTILINE).findAll(text).count()

internal class GeometryExportFailure(
    message: String,
) : RuntimeException(message)

/** Releases every native shape handle; safe to call repeatedly. Must run before any `exitProcess`. */
internal fun closeShapes(model: KStepModel) {
    model.shapes.forEach { runCatching { it.shape.close() } }
}

internal fun geometryJson(report: GeometryReport): JsonElement =
    buildJsonObject {
        put("merged", report.merged)
        put("shapeCount", report.shapeCount)
        put("placementCount", report.placementCount)
        put("usageCount", report.usageCount)
        putJsonObject("occt") {
            when (val occt = report.occt) {
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
        report.unmergedReason?.let { put("reason", it) }
    }

internal fun printGeometryExportError(
    jsonOutput: Boolean,
    errorKind: String,
    code: String,
    message: String,
): Nothing {
    if (jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", errorKind)
                put("code", code)
                put("message", message)
                put("command", "export")
            }.toString(),
        )
    } else {
        println("Geometry export failed: $message")
    }
    exitProcess(1)
}
