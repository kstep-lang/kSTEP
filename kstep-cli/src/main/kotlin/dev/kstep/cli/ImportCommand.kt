package dev.kstep.cli

import dev.kstep.geometry.BoundingBox
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import dev.kstep.geometry.XcafNodeKind
import dev.kstep.preview.MeshPreview
import dev.kstep.preview.PreviewEncodingException
import dev.kstep.preview.RenderFormat
import dev.kstep.render.RenderLimits
import dev.kstep.render.gltf.GlbExtras
import dev.kstep.shape.importer.ImportValidationOutcome
import dev.kstep.shape.importer.ImportedPlacement
import dev.kstep.shape.importer.ImportedStepModel
import dev.kstep.shape.importer.StepImportErrorKind
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImportValidation
import dev.kstep.shape.importer.StepImporter
import dev.kstep.shape.importer.StepInputException
import dev.kstep.shape.importer.StepInputValidation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.system.exitProcess

// `kstep import` (ADR-0030, Welle K). Plain println like the rest of the CLI's result rendering.
// The command is read-only: it writes nothing unless --render names an explicit output path.

private val PART_PALETTE =
    listOf(
        MeshColor(0.55, 0.70, 0.95),
        MeshColor(0.95, 0.65, 0.40),
        MeshColor(0.55, 0.85, 0.60),
        MeshColor(0.90, 0.55, 0.75),
        MeshColor(0.85, 0.80, 0.45),
        MeshColor(0.65, 0.60, 0.90),
    )

/** Resolves `kstep import` arguments; any malformed combination is `ShowUsage(1)`. */
internal fun resolveImportCommand(rest: List<String>): CliCommand {
    var path: String? = null
    var jsonOutput = false
    var validate = false
    var renderOut: String? = null
    var width = 1024
    var height = 768
    var i = 0
    while (i < rest.size) {
        when (rest[i]) {
            "--output" -> {
                val value = rest.getOrNull(i + 1) ?: return CliCommand.ShowUsage(1)
                if (value != "json") return CliCommand.ShowUsage(1)
                jsonOutput = true
                i += 2
            }
            "--validate" -> {
                validate = true
                i += 1
            }
            "--render" -> {
                val value = rest.getOrNull(i + 1) ?: return CliCommand.ShowUsage(1)
                val format = RenderFormat.fromExtension(value)
                if (format != RenderFormat.SVG && format != RenderFormat.PNG && format != RenderFormat.GLB) {
                    return CliCommand.ShowUsage(1)
                }
                renderOut = value
                i += 2
            }
            "-w", "--width" -> {
                width = rest.getOrNull(i + 1)?.toIntOrNull() ?: return CliCommand.ShowUsage(1)
                i += 2
            }
            "--height" -> {
                height = rest.getOrNull(i + 1)?.toIntOrNull() ?: return CliCommand.ShowUsage(1)
                i += 2
            }
            else -> {
                val arg = rest[i]
                if (arg.startsWith("-") || path != null) return CliCommand.ShowUsage(1)
                path = arg
                i += 1
            }
        }
    }
    val resolved = path ?: return CliCommand.ShowUsage(1)
    // The image size only applies to SVG/PNG; an out-of-range value is a usage error, caught before any import work.
    val sized = renderOut != null && RenderFormat.fromExtension(renderOut) != RenderFormat.GLB
    if (sized && !validImageSize(width, height)) return CliCommand.ShowUsage(1)
    return CliCommand.Import(resolved, jsonOutput, validate, renderOut, width, height)
}

private fun validImageSize(
    width: Int,
    height: Int,
): Boolean =
    width in RenderLimits.MIN_DIMENSION_PX..RenderLimits.MAX_DIMENSION_PX &&
        height in RenderLimits.MIN_DIMENSION_PX..RenderLimits.MAX_DIMENSION_PX &&
        width.toLong() * height.toLong() <= RenderLimits.MAX_TOTAL_PIXELS

internal class RenderReport(
    val outPath: String,
    val format: RenderFormat,
    val triangleCount: Int,
)

fun runImport(command: CliCommand.Import) {
    val bytes =
        try {
            StepInputValidation.readValidated(command.path)
        } catch (e: StepInputException) {
            reportImportError(command.jsonOutput, e.kind, e.message.orEmpty())
        }
    val result = StepImporter.read(bytes)
    val model =
        when (result) {
            is StepImportResult.Failure -> reportImportError(command.jsonOutput, result.kind, result.message)
            is StepImportResult.Success -> result.model
        }
    try {
        val validation = if (command.validate) StepImportValidation.validate(bytes) else null
        val render =
            if (command.renderOut != null) {
                renderImported(command, model) ?: return
            } else {
                null
            }
        val validationFailed =
            validation != null && !(validation is ImportValidationOutcome.Validated && validation.clean)
        if (command.jsonOutput) {
            println(importJson(command, model, validation, render, validationFailed).toString())
        } else {
            println(importText(command, model, validation, render))
            for (warning in model.warnings) System.err.println("kstep import: $warning")
        }
        if (validationFailed) {
            model.close()
            exitProcess(1)
        }
    } finally {
        model.close()
    }
}

/** Returns the render report, or `null` after having reported the failure and set the exit path. */
private fun renderImported(
    command: CliCommand.Import,
    model: ImportedStepModel,
): RenderReport? {
    val outPath = checkNotNull(command.renderOut)
    val format = checkNotNull(RenderFormat.fromExtension(outPath))
    val out = Path.of(outPath)
    try {
        val sameAsInput =
            Files.exists(out) && Files.exists(Path.of(command.path)) && Files.isSameFile(out, Path.of(command.path))
        if (sameAsInput || out.toAbsolutePath().normalize() == Path.of(command.path).toAbsolutePath().normalize()) {
            model.close()
            reportImportError(
                command.jsonOutput,
                StepImportErrorKind.INVALID_PATH,
                "--render must not name the input file",
            )
        }
        if (Files.isSymbolicLink(out)) {
            model.close()
            reportImportError(
                command.jsonOutput,
                StepImportErrorKind.INVALID_PATH,
                "--render must not be a symbolic link",
            )
        }
    } catch (e: IOException) {
        model.close()
        reportImportError(command.jsonOutput, StepImportErrorKind.IO_ERROR, "cannot check the --render path")
    }
    val placed = mutableListOf<PlacedMesh>()
    val meshByPart = HashMap<Int, TriangleMesh>()
    try {
        for (occurrence in model.occurrences) {
            val part = model.parts[occurrence.partIndex]
            val shape = part.shape ?: continue
            val placement = occurrence.global.placement ?: continue
            val mesh = meshByPart.getOrPut(part.index) { shape.triangulate() }
            placed.add(PlacedMesh(mesh, placement, PART_PALETTE[part.index % PART_PALETTE.size]))
        }
        if (placed.isEmpty()) {
            model.close()
            reportImportError(command.jsonOutput, StepImportErrorKind.NO_CONTENT, "no placed geometry to render")
        }
        val colored = MeshComposition.mergeColored(placed)
        val rendered =
            MeshPreview.render(
                colored,
                format,
                command.width,
                command.height,
                GlbExtras(scriptName = File(command.path).name),
            )
        File(outPath).writeBytes(rendered.bytes)
        return RenderReport(outPath, format, colored.mesh.triangleCount)
    } catch (e: IllegalArgumentException) {
        model.close()
        reportImportError(
            command.jsonOutput,
            StepImportErrorKind.LIMIT_EXCEEDED,
            "render: ${e.message.orEmpty().take(200).filter { it.code >= 0x20 }}",
        )
    } catch (e: OcctGeometryException) {
        model.close()
        reportImportError(command.jsonOutput, StepImportErrorKind.READ_FAILED, "render: triangulation failed")
    } catch (e: PreviewEncodingException) {
        model.close()
        reportImportError(command.jsonOutput, StepImportErrorKind.IO_ERROR, "render: ${e.message}")
    } catch (e: IOException) {
        model.close()
        reportImportError(command.jsonOutput, StepImportErrorKind.IO_ERROR, "cannot write the --render output")
    }
}

private fun reportImportError(
    jsonOutput: Boolean,
    kind: StepImportErrorKind,
    message: String,
): Nothing {
    if (jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("command", "import")
                put("errorKind", kind.jsonKind)
                put("code", kind.code)
                put("message", message)
            }.toString(),
        )
    } else {
        println("Error: $message")
    }
    exitProcess(1)
}

// ---- text report ----------------------------------------------------------------------------

private fun num(value: Double): String {
    val rounded = if (abs(value) < 5e-10) 0.0 else value
    val text = String.format(Locale.ROOT, "%.3f", rounded).trimEnd('0').trimEnd('.')
    return if (text == "-0" || text.isEmpty()) "0" else text
}

private fun fixed3(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

private fun box(b: BoundingBox?): String =
    if (b == null) {
        "n/a"
    } else {
        "[${num(b.minX)},${num(b.minY)},${num(b.minZ)}]..[${num(b.maxX)},${num(b.maxY)},${num(b.maxZ)}]"
    }

private fun placementText(p: ImportedPlacement): String {
    if (!p.rigid) return "non-rigid (not placed)"
    val m = p.matrix
    val t = "t=(${num(m[3])},${num(m[7])},${num(m[11])})"
    return listOfNotNull(t, rotationLabel(m)).joinToString(" ")
}

/** `Rz90`-style label for a pure rotation about one coordinate axis, `R` for a general rotation, null for none. */
private fun rotationLabel(m: DoubleArray): String? {
    val eps = 1e-9
    val identity =
        abs(m[0] - 1) < eps &&
            abs(m[5] - 1) < eps &&
            abs(m[10] - 1) < eps &&
            abs(m[1]) < eps &&
            abs(m[2]) < eps &&
            abs(m[4]) < eps &&
            abs(m[6]) < eps &&
            abs(m[8]) < eps &&
            abs(m[9]) < eps
    if (identity) return null

    fun deg(rad: Double) = num(Math.toDegrees(rad))
    if (abs(m[10] - 1) < eps && abs(m[2]) < eps && abs(m[6]) < eps && abs(m[8]) < eps && abs(m[9]) < eps) {
        return "Rz${deg(atan2(m[4], m[0]))}"
    }
    if (abs(m[0] - 1) < eps && abs(m[1]) < eps && abs(m[2]) < eps && abs(m[4]) < eps && abs(m[8]) < eps) {
        return "Rx${deg(atan2(m[9], m[5]))}"
    }
    if (abs(m[5] - 1) < eps && abs(m[1]) < eps && abs(m[4]) < eps && abs(m[6]) < eps && abs(m[9]) < eps) {
        return "Ry${deg(atan2(m[2], m[0]))}"
    }
    return "R"
}

internal fun importText(
    command: CliCommand.Import,
    model: ImportedStepModel,
    validation: ImportValidationOutcome?,
    render: RenderReport?,
): String {
    val sb = StringBuilder()
    val schema =
        model.prescan.schemas
            .firstOrNull()
            ?.substringBefore(' ') ?: "unknown"
    val occt = (OcctKernel.availability() as? OcctAvailability.Available)?.occtVersion ?: "unavailable"
    sb.appendLine(
        "Imported ${File(command.path).name} (schema $schema, OCCT $occt, unit ${model.lengthUnit}, " +
            "${model.stats.entityCount} entities)",
    )
    sb.appendLine(
        "Structure (source: ${model.structureSource.name}): ${model.assemblies.size} assemblies, " +
            "${model.parts.size} parts, ${model.occurrences.size} occurrences",
    )

    fun idSuffix(id: String?) = if (id == null) "" else " [$id]"
    for (root in model.roots) {
        if (root.kind == XcafNodeKind.ASSEMBLY) {
            val a = model.assemblies[root.index]
            sb.appendLine("  ${a.name}${idSuffix(a.productId)} (assembly)")
        } else {
            val p = model.parts[root.index]
            sb.appendLine("  ${p.name}${idSuffix(p.productId)} (part)")
        }
    }
    for (a in model.assemblies) {
        if (model.roots.none { it.kind == XcafNodeKind.ASSEMBLY && it.index == a.index }) {
            sb.appendLine("  ${a.name}${idSuffix(a.productId)} (sub-assembly)")
        }
        for (c in a.components) {
            val childName =
                if (c.childKind ==
                    XcafNodeKind.PART
                ) {
                    model.parts[c.childIndex].name
                } else {
                    model.assemblies[c.childIndex].name
                }
            val childId =
                if (c.childKind ==
                    XcafNodeKind.PART
                ) {
                    model.parts[c.childIndex].productId
                } else {
                    model.assemblies[c.childIndex].productId
                }
            sb.appendLine("      ${c.name} -> $childName${idSuffix(childId)}  at ${placementText(c.local)}")
        }
    }
    sb.appendLine("Parts:")
    for (p in model.parts) {
        val volume = p.volume?.let { "${fixed3(it)} mm3" } ?: "n/a"
        sb.appendLine("  ${p.name}  volume $volume  faces ${p.topology?.faces ?: 0}  bbox ${box(p.boundingBox)}")
    }
    sb.appendLine("Occurrences:")
    for (o in model.occurrences) {
        val label = if (o.path.isEmpty()) "(root)" else o.path.joinToString("/")
        sb.appendLine("  $label  ${model.parts[o.partIndex].name}  bbox ${box(o.boundingBox)}")
    }
    val volume = model.totals.volume?.let { "volume ${fixed3(it)} mm3, " } ?: ""
    sb.append("Total: ${model.totals.occurrenceCount} occurrences, ${volume}bbox ${box(model.totals.boundingBox)}")
    if (validation != null) {
        sb.append('\n')
        sb.append(
            when (validation) {
                is ImportValidationOutcome.Validated ->
                    if (validation.clean) {
                        "Validation: ${validation.reconstructed} AP242 V1 instances checked, no findings"
                    } else {
                        "Validation: ${validation.violations} violations, ${validation.skipped} skipped, " +
                            "${validation.modelFindings} model findings"
                    }
                is ImportValidationOutcome.Unavailable -> "Validation: no verdict available (${validation.reason})"
            },
        )
    }
    if (render != null) {
        sb.append("\nRendered ${render.triangleCount} triangles to ${render.outPath}")
    }
    return sb.toString()
}

// ---- JSON report ----------------------------------------------------------------------------

private fun jsonNum(value: Double?): JsonElement =
    if (value == null ||
        !value.isFinite()
    ) {
        JsonNull
    } else {
        JsonPrimitive(value)
    }

private fun jsonBox(b: BoundingBox?): JsonElement =
    if (b == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("min", JsonArray(listOf(jsonNum(b.minX), jsonNum(b.minY), jsonNum(b.minZ))))
            put("max", JsonArray(listOf(jsonNum(b.maxX), jsonNum(b.maxY), jsonNum(b.maxZ))))
        }
    }

private fun jsonPlacement(p: ImportedPlacement): JsonObject =
    buildJsonObject {
        put("rigid", p.rigid)
        val m = p.matrix
        if (p.rigid) {
            put("translation", JsonArray(listOf(jsonNum(m[3]), jsonNum(m[7]), jsonNum(m[11]))))
            put(
                "rotation",
                JsonArray(
                    listOf(
                        JsonArray(listOf(jsonNum(m[0]), jsonNum(m[1]), jsonNum(m[2]))),
                        JsonArray(listOf(jsonNum(m[4]), jsonNum(m[5]), jsonNum(m[6]))),
                        JsonArray(listOf(jsonNum(m[8]), jsonNum(m[9]), jsonNum(m[10]))),
                    ),
                ),
            )
        } else {
            put("matrix", JsonArray(m.map { jsonNum(it) }))
        }
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: String?,
) {
    put(key, if (value == null) JsonNull else JsonPrimitive(value))
}

internal fun importJson(
    command: CliCommand.Import,
    model: ImportedStepModel,
    validation: ImportValidationOutcome?,
    render: RenderReport?,
    validationFailed: Boolean,
): JsonObject {
    val availability = OcctKernel.availability()
    return buildJsonObject {
        put("status", if (validationFailed) "validation_failed" else "success")
        put("command", "import")
        put("file", command.path)
        put(
            "header",
            buildJsonObject {
                put("schemas", buildJsonArray { model.prescan.schemas.forEach { add(JsonPrimitive(it)) } })
                putNullable("fileName", model.prescan.headerFileName)
                putNullable("originatingSystem", model.prescan.originatingSystem)
            },
        )
        put(
            "occt",
            buildJsonObject {
                put("available", availability is OcctAvailability.Available)
                if (availability is OcctAvailability.Available) put("version", availability.occtVersion)
            },
        )
        put("lengthUnit", model.lengthUnit)
        put("entityCount", model.stats.entityCount)
        put("structureSource", model.structureSource.name.lowercase())
        put(
            "unknownEntities",
            buildJsonObject {
                put("count", model.stats.unknownEntityCount)
                put("types", buildJsonArray { model.stats.unknownEntityTypes.forEach { add(JsonPrimitive(it)) } })
            },
        )
        put(
            "ignored",
            buildJsonObject {
                put("colours", model.prescan.ignoredCategories["colours"] ?: 0)
                put("layers", model.prescan.ignoredCategories["layers"] ?: 0)
                put("pmi", model.prescan.ignoredCategories["pmi"] ?: 0)
            },
        )
        put(
            "parts",
            buildJsonArray {
                for (p in model.parts) {
                    add(
                        buildJsonObject {
                            put("index", p.index)
                            put("name", p.name)
                            putNullable("productId", p.productId)
                            put("volume", jsonNum(p.volume))
                            put(
                                "topology",
                                p.topology?.let {
                                    buildJsonObject {
                                        put("solids", it.solids)
                                        put("shells", it.shells)
                                        put("faces", it.faces)
                                        put("edges", it.edges)
                                        put("vertices", it.vertices)
                                    }
                                } ?: JsonNull,
                            )
                            put("boundingBox", jsonBox(p.boundingBox))
                        },
                    )
                }
            },
        )
        put(
            "assemblies",
            buildJsonArray {
                for (a in model.assemblies) {
                    add(
                        buildJsonObject {
                            put("index", a.index)
                            put("name", a.name)
                            putNullable("productId", a.productId)
                            put(
                                "components",
                                buildJsonArray {
                                    for (c in a.components) {
                                        add(
                                            buildJsonObject {
                                                put("name", c.name)
                                                putNullable("usageId", c.usageId)
                                                put(
                                                    "child",
                                                    buildJsonObject {
                                                        put(
                                                            "kind",
                                                            if (c.childKind ==
                                                                XcafNodeKind.PART
                                                            ) {
                                                                "part"
                                                            } else {
                                                                "assembly"
                                                            },
                                                        )
                                                        put("index", c.childIndex)
                                                    },
                                                )
                                                put("placement", jsonPlacement(c.local))
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        put(
            "roots",
            buildJsonArray {
                for (r in model.roots) {
                    add(
                        buildJsonObject {
                            put("kind", if (r.kind == XcafNodeKind.PART) "part" else "assembly")
                            put("index", r.index)
                        },
                    )
                }
            },
        )
        put(
            "occurrences",
            buildJsonArray {
                for (o in model.occurrences) {
                    add(
                        buildJsonObject {
                            put("path", buildJsonArray { o.path.forEach { add(JsonPrimitive(it)) } })
                            put("part", o.partIndex)
                            put("placement", jsonPlacement(o.global))
                            put("boundingBox", jsonBox(o.boundingBox))
                        },
                    )
                }
            },
        )
        put(
            "totals",
            buildJsonObject {
                put("occurrenceCount", model.totals.occurrenceCount)
                put("volume", jsonNum(model.totals.volume))
                put("boundingBox", jsonBox(model.totals.boundingBox))
            },
        )
        if (validation != null) {
            put(
                "validation",
                when (validation) {
                    is ImportValidationOutcome.Validated ->
                        buildJsonObject {
                            put("available", true)
                            put("ok", validation.clean)
                            put("reconstructed", validation.reconstructed)
                            put("opaque", validation.opaque)
                            put("violations", validation.violations)
                            put("skipped", validation.skipped)
                            put("modelFindings", validation.modelFindings)
                            put("suppressed", validation.suppressed)
                        }
                    is ImportValidationOutcome.Unavailable ->
                        buildJsonObject {
                            put("available", false)
                            put("ok", false)
                            put("reason", validation.reason)
                        }
                },
            )
        }
        if (render != null) {
            put(
                "render",
                buildJsonObject {
                    put("outPath", render.outPath)
                    put("format", render.format.name.lowercase())
                    put("triangleCount", render.triangleCount)
                },
            )
        }
        put("warnings", buildJsonArray { model.warnings.forEach { add(JsonPrimitive(it)) } })
    }
}
