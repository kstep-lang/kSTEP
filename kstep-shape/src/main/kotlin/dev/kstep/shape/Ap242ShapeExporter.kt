package dev.kstep.shape

import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import dev.kstep.step21.Part21Writer
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

private val logger = KotlinLogging.logger {}

/**
 * Merges a validated `kstep-core` AP242 product structure with an OCCT-produced B-Rep solid
 * into ONE ISO 10303-21 physical file — kSTEP Geometrie Welle 4, see ADR-0009 for the full
 * design rationale (why a Part-21-level merge instead of `STEPCAFControl_Writer`/XCAF, why
 * OCCT's own placeholder product structure is discarded and replaced, the DoS/injection
 * analysis).
 */
object Ap242ShapeExporter {
    /**
     * The FILE_SCHEMA identifier OCCT's own AP242 writer emits — adopted verbatim here because
     * the merged file's geometry entities ARE the AP242 MIM entities (see ADR-0009 §7, Open
     * Question 3), so their schema identifier is carried over rather than kSTEP's own,
     * bare-object-identifier-free short form.
     */
    const val AP242_SCHEMA_IDENTIFIER: String =
        "AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF {1 0 10303 442 1 1 4 }"

    /**
     * Produces one complete ISO 10303-21 file text from [assignment]'s validated
     * `kstep-core` product structure (reachable from [ShapeAssignment.productDefinition]) and
     * its OCCT B-Rep geometry ([ShapeAssignment.shape]), connected via
     * `PRODUCT_DEFINITION_SHAPE` + `SHAPE_DEFINITION_REPRESENTATION`.
     *
     * @throws ShapeExportException if OCCT's own STEP output does not have the expected
     *   structure — see [Ap242GeometryExtraction].
     * @throws dev.kstep.geometry.OcctUnavailableException if the native OCCT bridge is not available.
     * @throws dev.kstep.geometry.OcctGeometryException if OCCT's writer itself fails.
     * @throws IllegalStateException if [ShapeAssignment.shape] is already closed.
     */
    fun export(
        header: Part21Header,
        assignment: ShapeAssignment,
    ): String = export(header, listOf(assignment.productDefinition), listOf(assignment), emptyList())

    /**
     * Multi-root, multi-shape variant (ADR-0027): emits ALL of [roots] (so assembly structure --
     * `NEXT_ASSEMBLY_USAGE_OCCURRENCE` roots -- is preserved, unlike the single-assignment
     * overload, which only emits its one product definition) and binds each of [assignments] to
     * its product definition via `PRODUCT_DEFINITION_SHAPE` + `SHAPE_DEFINITION_REPRESENTATION`.
     *
     * Assembly usages whose child carries geometry (directly or through descendants) additionally
     * get a `CONTEXT_DEPENDENT_SHAPE_REPRESENTATION` chain (ADR-0028), without which OCCT/FreeCAD
     * drop the child from the assembly. Without [placements] every such usage is placed at the
     * identity; [placements] position the component relative to its parent
     * (`pParent = R * pChild + t`). Usages whose subtree carries no geometry, and exports without any
     * assembly usage, are written exactly as before ADR-0028.
     *
     * @param placements at most one per usage; the usage must be a root with a geometric child
     *
     * @throws ShapeExportException if [assignments] is empty or exceeds [MAX_SHAPES], two
     *   assignments share one product definition, an assignment's product definition is not
     *   reachable from [roots], or OCCT's STEP output has an unexpected structure.
     * @throws dev.kstep.geometry.OcctUnavailableException if the native OCCT bridge is not available.
     * @throws dev.kstep.geometry.OcctGeometryException if OCCT's writer itself fails.
     * @throws IllegalStateException if an assignment's shape is already closed.
     */
    fun export(
        header: Part21Header,
        roots: List<Any>,
        assignments: List<ShapeAssignment>,
        placements: List<UsagePlacement> = emptyList(),
    ): String {
        if (assignments.isEmpty()) throw ShapeExportException("at least one shape assignment is required")
        if (assignments.size > MAX_SHAPES) {
            throw ShapeExportException("too many shape assignments: ${assignments.size} (limit $MAX_SHAPES)")
        }
        val seen = java.util.IdentityHashMap<Any, Boolean>()
        for (a in assignments) {
            if (seen.put(a.productDefinition, true) != null) {
                throw ShapeExportException(
                    "more than one shape is bound to product definition '${a.productDefinition.id}'; " +
                        "exactly one shape per product definition is supported",
                )
            }
        }

        val structure = Part21Writer.emit(roots, startId = 1)
        val ids =
            assignments.map { a ->
                try {
                    structure.idOf(a.productDefinition)
                } catch (e: NoSuchElementException) {
                    throw ShapeExportException(
                        "a shape is bound to product definition '${a.productDefinition.id}' " +
                            "that is not reachable from the exported roots",
                        e,
                    )
                }
            }

        val plan = AssemblyGeometryGraph.plan(roots, assignments.map { it.productDefinition }, placements)

        var offset = structure.nextId - 1
        var totalInstances = 0
        val geometryAll = mutableListOf<Part21EntityInstance>()
        // PD (identity) -> id of its already-renumbered shape representation
        val representationOf = java.util.IdentityHashMap<Any, Int>()
        val bridge = mutableListOf<Part21EntityInstance>()
        for ((index, assignment) in assignments.withIndex()) {
            val occtText = writeOcctStepText(assignment)
            val occtDoc = Part21Reader.readRawDocument(occtText, Part21ReadMode.TOLERANT)
            val geometry = Ap242GeometryExtraction.extract(occtDoc)
            totalInstances += geometry.instances.size
            if (totalInstances > MAX_TOTAL_GEOMETRY_INSTANCES) {
                throw ShapeExportException(
                    "combined geometry exceeds the instance limit of $MAX_TOTAL_GEOMETRY_INSTANCES",
                )
            }

            // The two Part21Document algebra operations ADR-0009 introduces this merge for.
            // Wrapping the geometry subgraph in a throwaway Part21Document keeps this the SAME
            // renumbering path Part21DocumentMergeTest exercises directly.
            val renumbered: List<Part21EntityInstance> =
                Part21Document(header, geometry.instances).renumbered(offset).instances
            val rootRepresentationId = geometry.rootRepresentationId + offset
            val nextFreeId = addExactId(renumbered.maxOf { it.id }, 1)
            val productDefinitionShapeId = nextFreeId
            val shapeDefinitionRepresentationId = addExactId(nextFreeId, 1)
            geometryAll += renumbered
            representationOf[assignment.productDefinition] = rootRepresentationId
            bridge +=
                Ap242ShapeBridgeEntities.productDefinitionShape(
                    productDefinitionShapeId,
                    assignment.shapeName,
                    ids[index],
                )
            bridge +=
                Ap242ShapeBridgeEntities.shapeDefinitionRepresentation(
                    shapeDefinitionRepresentationId,
                    productDefinitionShapeId,
                    rootRepresentationId,
                )
            offset = shapeDefinitionRepresentationId
        }

        if (plan.usages.isNotEmpty()) {
            val geometryById = geometryAll.associateBy { it.id }
            val result =
                Ap242PlacementBridge.build(
                    plan = plan,
                    startId = addExactId(offset, 1),
                    shapedRepresentationId = { representationOf[it] },
                    representation = { geometryById.getValue(it) },
                    contextId = {
                        sharedRepresentationContextId(
                            geometryById,
                            representationOf.getValue(assignments.first().productDefinition),
                        )
                    },
                    productDefinitionId = { structure.idOf(it) },
                    usageId = { structure.idOf(it) },
                )
            for (i in geometryAll.indices) {
                result.replaced[geometryAll[i].id]?.let { geometryAll[i] = it }
            }
            bridge += result.added
            offset = result.added.maxOf { it.id }
        }

        // applicationProtocolDefinition + productRelatedProductCategory hang off the application
        // context / product, not off a shape: emitted exactly once (first assignment's product).
        val first = assignments.first()
        val applicationProtocolDefinitionId = addExactId(offset, 1)
        val productRelatedProductCategoryId = addExactId(offset, 2)
        bridge +=
            Ap242ShapeBridgeEntities.applicationProtocolDefinition(
                applicationProtocolDefinitionId,
                structure.idOf(first.productDefinition.frameOfReference.frameOfReference),
            )
        bridge +=
            Ap242ShapeBridgeEntities.productRelatedProductCategory(
                productRelatedProductCategoryId,
                structure.idOf(first.productDefinition.formation.ofProduct),
            )

        // Part21Document.concat's own duplicate-#N guard (Part21WriteException) is real
        // protection against a future change to the offset arithmetic above.
        val structureDoc = Part21Document(header, structure.instances)
        val merged = Part21Document.concat(structureDoc, geometryAll, bridge)
        val document = merged.copy(header = header.copy(schemaIdentifiers = listOf(AP242_SCHEMA_IDENTIFIER)))
        return document.render()
    }

    // The geometric representation context OCCT wrote for the first shape: reused for synthesized
    // representations of parts that have no geometry of their own (all OCCT contexts are millimetres).
    private fun sharedRepresentationContextId(
        geometryById: Map<Int, Part21EntityInstance>,
        representationId: Int,
    ): Int {
        val rep = geometryById[representationId] as? Part21SimpleInstance
        val context = rep?.args?.getOrNull(2) as? Part21Value.Ref
        return context?.id
            ?: throw ShapeExportException(
                "cannot find the representation context of representation #$representationId",
            )
    }

    // [Math.addExact]'s overflow check, surfaced as ShapeExportException: without it, an id near
    // Int.MAX_VALUE (only reachable from a pathological/adversarial OCCT geometry subgraph or an
    // already-huge kstep-core structure, but not provably unreachable) would silently wrap to a
    // negative bridge-entity id here, which Part21Renderer now rejects — but only much later, at
    // render time, with a message that looks like a renderer bug rather than an id-allocation bug
    // in this merge. See ADR-0009's "injection across the export boundary" / instance-id integrity.
    private fun addExactId(
        id: Int,
        delta: Int,
    ): Int =
        try {
            Math.addExact(id, delta)
        } catch (e: ArithmeticException) {
            throw ShapeExportException(
                "bridge-entity id allocation overflowed a 32-bit Int while adding $delta to geometry id #$id",
                e,
            )
        }

    /** Upper bound on shapes per export (DoS guard, ADR-0027). */
    const val MAX_SHAPES: Int = 256

    /** Upper bound on the summed geometry instance count over all shapes (DoS guard, ADR-0027). */
    const val MAX_TOTAL_GEOMETRY_INSTANCES: Int = 5_000_000

    /** Multi-shape [exportToFile]; see the multi-root [export]. */
    fun exportToFile(
        header: Part21Header,
        roots: List<Any>,
        assignments: List<ShapeAssignment>,
        target: Path,
        placements: List<UsagePlacement> = emptyList(),
    ): Path {
        val text = export(header, roots, assignments, placements)
        val absolute = target.toAbsolutePath().normalize()
        Files.createDirectories(absolute.parent)
        Files.writeString(absolute, text)
        return absolute
    }

    /** [export] plus writing the result to [target]. Returns the absolute, normalized path actually written. */
    fun exportToFile(
        header: Part21Header,
        assignment: ShapeAssignment,
        target: Path,
    ): Path {
        val text = export(header, assignment)
        val absolute = target.toAbsolutePath().normalize()
        Files.createDirectories(absolute.parent)
        Files.writeString(absolute, text)
        return absolute
    }

    // Owner-only-permissions, unpredictable-named temp directory (Files.createTempDirectory),
    // never a fixed shared-/tmp path — a fixed name would be a symlink/TOCTOU target for the
    // path OcctShape.writeStepFile is about to (over)write. Always removed in `finally`, whether
    // the native write succeeds or throws.
    private fun writeOcctStepText(assignment: ShapeAssignment): String {
        val tempDir = Files.createTempDirectory("kstep-shape-")
        try {
            val stepFile = tempDir.resolve("occt.step")
            assignment.shape.writeStepFile(stepFile)
            return Files.readString(stepFile)
        } finally {
            deleteRecursively(tempDir)
        }
    }

    private fun deleteRecursively(root: Path) {
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): java.nio.file.FileVisitResult {
                        Files.deleteIfExists(file)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): java.nio.file.FileVisitResult {
                        Files.deleteIfExists(dir)
                        return java.nio.file.FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (e: IOException) {
            // Best-effort cleanup only -- a leftover temp dir under the JVM's own temp root is
            // not a correctness issue for the export itself, so this never escalates to a
            // thrown exception that would mask the real export result/failure.
            logger.warn(e) { "Failed to fully delete temporary directory $root" }
        }
    }
}
