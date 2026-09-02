package dev.kstep.shape

import dev.kstep.step21.Part21Document
import dev.kstep.step21.Part21EntityInstance
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
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
    ): String {
        val occtText = writeOcctStepText(assignment)
        val occtDoc = Part21Reader.readRawDocument(occtText, Part21ReadMode.TOLERANT)
        val geometry = Ap242GeometryExtraction.extract(occtDoc)

        val structure = Part21Writer.emit(listOf(assignment.productDefinition), startId = 1)
        val offset = structure.nextId - 1

        // The two Part21Document algebra operations ADR-0009 introduces this merge for — see
        // that ADR's "kstep-step21: one renderer, a Part21Document algebra" section. Wrapping
        // the geometry subgraph in a throwaway Part21Document (its header is discarded, only
        // renumbered's instance/reference shift matters here) keeps this the SAME renumbering
        // path Part21DocumentMergeTest exercises directly, instead of a second, independently
        // maintained instance-list-map that could silently drift from it.
        val geometryRenumbered: List<Part21EntityInstance> =
            Part21Document(header, geometry.instances).renumbered(offset).instances
        val rootRepresentationId = geometry.rootRepresentationId + offset

        val nextFreeId = addExactId(geometryRenumbered.maxOf { it.id }, 1)
        val productDefinitionShapeId = nextFreeId
        val shapeDefinitionRepresentationId = addExactId(nextFreeId, 1)
        val applicationProtocolDefinitionId = addExactId(nextFreeId, 2)
        val productRelatedProductCategoryId = addExactId(nextFreeId, 3)

        val applicationContext = assignment.productDefinition.frameOfReference.frameOfReference
        val product = assignment.productDefinition.formation.ofProduct

        val bridge: List<Part21SimpleInstance> =
            listOf(
                Ap242ShapeBridgeEntities.productDefinitionShape(
                    productDefinitionShapeId,
                    assignment.shapeName,
                    structure.idOf(assignment.productDefinition),
                ),
                Ap242ShapeBridgeEntities.shapeDefinitionRepresentation(
                    shapeDefinitionRepresentationId,
                    productDefinitionShapeId,
                    rootRepresentationId,
                ),
                Ap242ShapeBridgeEntities.applicationProtocolDefinition(
                    applicationProtocolDefinitionId,
                    structure.idOf(applicationContext),
                ),
                Ap242ShapeBridgeEntities.productRelatedProductCategory(
                    productRelatedProductCategoryId,
                    structure.idOf(product),
                ),
            )

        // Part21Document.concat's own duplicate-#N guard (Part21WriteException) is real
        // protection here, not just documentation: it is the only thing standing between a
        // future change to the offset arithmetic above (or a third merge participant) and a
        // silently-written file with two instances sharing one #N, since kSTEP's own product
        // structure and OCCT's renumbered geometry are otherwise merged by plain concatenation.
        val structureDoc = Part21Document(header, structure.instances)
        val merged = Part21Document.concat(structureDoc, geometryRenumbered, bridge)
        val document = merged.copy(header = header.copy(schemaIdentifiers = listOf(AP242_SCHEMA_IDENTIFIER)))
        return document.render()
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
