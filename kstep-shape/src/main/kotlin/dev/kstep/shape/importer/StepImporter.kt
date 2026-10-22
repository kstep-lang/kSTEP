package dev.kstep.shape.importer

import dev.kstep.geometry.BoundingBox
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctExternalReferenceException
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctImportLimitException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.OcctStepDocument
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.Placement
import dev.kstep.geometry.StepImportLimits
import dev.kstep.geometry.XcafNode
import dev.kstep.geometry.XcafNodeKind
import dev.kstep.step21.Part21ReadMode
import dev.kstep.step21.Part21Reader
import dev.kstep.step21.Part21SimpleInstance
import dev.kstep.step21.Part21Value
import java.nio.charset.StandardCharsets

/**
 * Reads a STEP file back into a kSTEP model (ADR-0030, Welle K).
 *
 * OCCT's XCAF reader is the authority for structure, shape and placement; the typed Part 21 layer only
 * enriches the result with the product and usage identifiers (best effort, never fatal). Every failure
 * is a [StepImportResult.Failure] with a stable [StepImportErrorKind]; this object never throws for bad
 * input. A [StepImportResult.Success] owns native shapes -- close its model.
 */
object StepImporter {
    private const val LENGTH_UNIT = "mm"
    private const val PART21_ENRICHMENT_MAX_BYTES = 5_000_000

    internal const val NON_RIGID_WARNING =
        "some placements carry scaling, a reflection or numeric noise; those occurrences are not placed"

    /** A Kotlin-side structural limit (assembly depth, expanded occurrence count) was exceeded. */
    internal class LimitExceeded(
        message: String,
    ) : Exception(message)

    /** Result of [expand]: the flattened part occurrences and whether any of them has a non-rigid placement. */
    internal class Expansion(
        val occurrences: List<ImportedOccurrence>,
        val nonRigidSeen: Boolean,
    )

    /** Validates [rawPath] (see [StepInputValidation.readValidated]) and imports the file's bytes. */
    fun read(rawPath: String): StepImportResult =
        try {
            read(StepInputValidation.readValidated(rawPath))
        } catch (e: StepInputException) {
            StepImportResult.Failure(e.kind, e.message.orEmpty())
        }

    /** Imports STEP file [content] (the raw bytes). */
    fun read(content: ByteArray): StepImportResult {
        val prescan =
            try {
                StepInputValidation.prescan(content)
            } catch (e: StepInputException) {
                return StepImportResult.Failure(e.kind, e.message.orEmpty())
            }
        val availability = OcctKernel.availability()
        if (availability !is OcctAvailability.Available) {
            val reason = (availability as OcctAvailability.Unavailable).reason
            return StepImportResult.Failure(
                StepImportErrorKind.GEOMETRY_UNAVAILABLE,
                "OCCT geometry kernel is not available: ${StepInputValidation.sanitize(reason)}",
            )
        }
        val owned = mutableListOf<OcctShape>()
        return try {
            OcctKernel.readStep(content).use { doc -> build(content, prescan, doc, owned) }
        } catch (e: LimitExceeded) {
            owned.forEach { it.close() }
            StepImportResult.Failure(StepImportErrorKind.LIMIT_EXCEEDED, e.message.orEmpty())
        } catch (e: OcctImportLimitException) {
            owned.forEach { it.close() }
            StepImportResult.Failure(
                StepImportErrorKind.LIMIT_EXCEEDED,
                StepInputValidation.sanitize(e.message.orEmpty()),
            )
        } catch (e: OcctExternalReferenceException) {
            owned.forEach { it.close() }
            StepImportResult.Failure(
                StepImportErrorKind.EXTERNAL_REFERENCE_REJECTED,
                "the file references external documents; external references are not followed",
            )
        } catch (e: OcctUnavailableException) {
            owned.forEach { it.close() }
            StepImportResult.Failure(
                StepImportErrorKind.GEOMETRY_UNAVAILABLE,
                StepInputValidation.sanitize(e.message.orEmpty()),
            )
        } catch (e: OcctGeometryException) {
            owned.forEach { it.close() }
            StepImportResult.Failure(StepImportErrorKind.READ_FAILED, "OCCT could not read the file")
        } catch (e: IllegalArgumentException) {
            owned.forEach { it.close() }
            StepImportResult.Failure(StepImportErrorKind.READ_FAILED, StepInputValidation.sanitize(e.message.orEmpty()))
        } catch (e: IllegalStateException) {
            owned.forEach { it.close() }
            StepImportResult.Failure(StepImportErrorKind.READ_FAILED, StepInputValidation.sanitize(e.message.orEmpty()))
        }
    }

    private class Frame(
        val nodeIndex: Int,
        val path: List<String>,
        val chain: List<ImportedPlacement>,
        val depth: Int,
    )

    private fun build(
        content: ByteArray,
        prescan: StepPrescan,
        doc: OcctStepDocument,
        owned: MutableList<OcctShape>,
    ): StepImportResult {
        val nodes = doc.nodes
        val warnings = mutableListOf<String>()
        if (nodes.none { it.kind == XcafNodeKind.PART && it.hasShape }) {
            return StepImportResult.Failure(
                StepImportErrorKind.NO_CONTENT,
                "the file contains no geometry OCCT could read (structure-only import is not supported yet)",
            )
        }
        val enrichment = enrich(content, warnings)

        val denseIndex = denseIndices(nodes)
        val componentsByParent = nodes.filter { it.kind == XcafNodeKind.COMPONENT }.groupBy { it.parentIndex }

        val parts = mutableListOf<ImportedPart>()
        for (node in nodes.filter { it.kind == XcafNodeKind.PART }) {
            var shape: OcctShape? = null
            if (node.hasShape) {
                shape = doc.partShape(node.index)
                owned.add(shape)
            }
            parts.add(
                ImportedPart(
                    index = parts.size,
                    name = node.name,
                    productId = enrichment.productId(node.name),
                    shape = shape,
                    volume = shape?.volume,
                    topology = shape?.topology,
                    boundingBox = shape?.boundingBox,
                ),
            )
        }

        val assemblies = mutableListOf<ImportedAssembly>()
        for (node in nodes.filter { it.kind == XcafNodeKind.ASSEMBLY }) {
            val components =
                componentsByParent[node.index].orEmpty().mapNotNull { comp ->
                    val referred = comp.referredIndex?.let { nodes[it] } ?: return@mapNotNull null
                    ImportedComponent(
                        name = comp.name,
                        usageId = enrichment.usageId(comp.name),
                        childKind = referred.kind,
                        childIndex = denseIndex[referred.index],
                        local = localPlacement(comp),
                    )
                }
            assemblies.add(ImportedAssembly(assemblies.size, node.name, enrichment.productId(node.name), components))
        }

        val rootNodes = nodes.filter { it.freeRoot && it.kind != XcafNodeKind.COMPONENT }
        val roots = rootNodes.map { ImportedRoot(it.kind, denseIndex[it.index]) }

        val expansion = expand(nodes) { partIndex, global -> globalBox(parts[partIndex].shape, global, owned) }
        val occurrences = expansion.occurrences
        if (expansion.nonRigidSeen) {
            warnings.add(NON_RIGID_WARNING)
        }

        val volume = totalVolume(occurrences, parts)
        val totalBox: BoundingBox? = occurrences.mapNotNull { it.boundingBox }.reduceOrNull { a, b -> a.union(b) }

        if (doc.stats.unknownEntityCount > 0) {
            warnings.add(
                "${doc.stats.unknownEntityCount} unknown entity instances of types " +
                    "${doc.stats.unknownEntityTypes.joinToString(", ")}, ignored",
            )
        }
        if (prescan.ignoredCategories.isNotEmpty()) {
            warnings.add(
                "ignored (not imported): " +
                    prescan.ignoredCategories.entries.joinToString(", ") { "${it.key} (${it.value})" },
            )
        }

        val model =
            ImportedStepModel(
                prescan = prescan,
                stats = doc.stats,
                lengthUnit = LENGTH_UNIT,
                structureSource = StructureSource.XCAF,
                parts = parts,
                assemblies = assemblies,
                roots = roots,
                occurrences = occurrences,
                totals = ImportedTotals(occurrences.size, volume, totalBox),
                warnings = warnings,
            )
        return StepImportResult.Success(model)
    }

    /**
     * Sum of the part volumes over all occurrences, or `null` if there is no occurrence, an occurrence is
     * not rigidly placed, or a part has no volume (a total over unplaced or shapeless parts would mislead).
     */
    internal fun totalVolume(
        occurrences: List<ImportedOccurrence>,
        parts: List<ImportedPart>,
    ): Double? =
        occurrences
            .takeIf { occ -> occ.isNotEmpty() && occ.all { it.global.rigid && parts[it.partIndex].volume != null } }
            ?.sumOf { parts[it.partIndex].volume!! }

    /** Dense per-kind index of every PART / ASSEMBLY node (-1 for components). */
    private fun denseIndices(nodes: List<XcafNode>): IntArray {
        val denseIndex = IntArray(nodes.size) { -1 }
        var partCount = 0
        var assemblyCount = 0
        for (node in nodes) {
            when (node.kind) {
                XcafNodeKind.PART -> denseIndex[node.index] = partCount++
                XcafNodeKind.ASSEMBLY -> denseIndex[node.index] = assemblyCount++
                XcafNodeKind.COMPONENT -> Unit
            }
        }
        return denseIndex
    }

    /**
     * Iterative DFS expansion of the assembly DAG from the free roots into part occurrences, bounded in
     * depth and size. Independent of OCCT: [boxOf] supplies the global bounding box of a part occurrence.
     *
     * @throws LimitExceeded if the nesting exceeds [maxDepth] (a cycle shows up this way) or more than
     *   [maxOccurrences] part occurrences result.
     */
    internal fun expand(
        nodes: List<XcafNode>,
        maxDepth: Int = StepImportLimits.MAX_ASSEMBLY_DEPTH,
        maxOccurrences: Int = StepImportLimits.MAX_EXPANDED_OCCURRENCES,
        boxOf: (partIndex: Int, global: ImportedPlacement) -> BoundingBox?,
    ): Expansion {
        val denseIndex = denseIndices(nodes)
        val componentsByParent = nodes.filter { it.kind == XcafNodeKind.COMPONENT }.groupBy { it.parentIndex }
        val rootNodes = nodes.filter { it.freeRoot && it.kind != XcafNodeKind.COMPONENT }
        val occurrences = mutableListOf<ImportedOccurrence>()
        val stack = ArrayDeque<Frame>()
        for (root in rootNodes.asReversed()) stack.addFirst(Frame(root.index, emptyList(), emptyList(), 0))
        var nonRigidSeen = false
        while (stack.isNotEmpty()) {
            val frame = stack.removeFirst()
            if (frame.depth > maxDepth) {
                throw LimitExceeded("assembly nesting deeper than $maxDepth levels (cycle or deep nesting)")
            }
            val node = nodes[frame.nodeIndex]
            when (node.kind) {
                XcafNodeKind.PART -> {
                    if (occurrences.size >= maxOccurrences) {
                        throw LimitExceeded("more than $maxOccurrences part occurrences after expansion")
                    }
                    val global = composeGlobal(frame.chain)
                    if (!global.rigid) nonRigidSeen = true
                    val partIndex = denseIndex[node.index]
                    occurrences.add(
                        ImportedOccurrence(
                            path = frame.path,
                            partIndex = partIndex,
                            global = global,
                            boundingBox = boxOf(partIndex, global),
                        ),
                    )
                }
                XcafNodeKind.ASSEMBLY -> {
                    val comps = componentsByParent[node.index].orEmpty()
                    for (comp in comps.asReversed()) {
                        val referred = comp.referredIndex ?: continue
                        stack.addFirst(
                            Frame(
                                referred,
                                frame.path + comp.name,
                                frame.chain + localPlacement(comp),
                                frame.depth + 1,
                            ),
                        )
                    }
                }
                XcafNodeKind.COMPONENT -> Unit
            }
        }
        return Expansion(occurrences, nonRigidSeen)
    }

    private fun localPlacement(node: XcafNode): ImportedPlacement {
        val placement =
            if (node.rigid) {
                try {
                    Placement.fromRowMajor3x4(node.localMatrix)
                } catch (e: IllegalArgumentException) {
                    null
                }
            } else {
                null
            }
        return ImportedPlacement(placement, node.localMatrix, placement != null)
    }

    /**
     * Global placement of a part reached through components `L1` (outermost) ... `Ln` (innermost):
     * a point is moved by `Ln` first, then outwards, i.e. `Ln.then(L(n-1))...then(L1)`.
     */
    private fun composeGlobal(chain: List<ImportedPlacement>): ImportedPlacement {
        if (chain.isEmpty()) return IDENTITY_PLACEMENT
        if (chain.any { it.placement == null }) return ImportedPlacement(null, IDENTITY_PLACEMENT.matrix, false)
        var result = chain.last().placement!!
        for (i in chain.size - 2 downTo 0) result = result.then(chain[i].placement!!)
        return ImportedPlacement(result, result.rowMajor3x4(), true)
    }

    private val IDENTITY_PLACEMENT =
        ImportedPlacement(
            Placement.IDENTITY,
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0),
            true,
        )

    private fun globalBox(
        shape: OcctShape?,
        global: ImportedPlacement,
        owned: MutableList<OcctShape>,
    ): BoundingBox? {
        if (shape == null || global.placement == null) return null
        val moved = OcctKernel.transformed(shape, global.placement)
        return try {
            moved.boundingBox
        } finally {
            moved.close()
        }
    }

    private class Enrichment(
        private val productIds: Map<String, String>,
        private val usageIds: Map<String, String>,
    ) {
        fun productId(name: String): String? = productIds[name]

        fun usageId(name: String): String? = usageIds[name]
    }

    /**
     * Best-effort identifier enrichment from the typed Part 21 layer: a product/usage id is attached only when
     * the XCAF name matches exactly one PRODUCT / NEXT_ASSEMBLY_USAGE_OCCURRENCE name. Large or foreign files
     * (the Part 21 reader caps at 5 MB / 10 000 instances) simply skip it, with a warning.
     */
    private fun enrich(
        content: ByteArray,
        warnings: MutableList<String>,
    ): Enrichment {
        if (content.size > PART21_ENRICHMENT_MAX_BYTES) {
            warnings.add("product identifiers not read: file exceeds the Part 21 reader size limit")
            return Enrichment(emptyMap(), emptyMap())
        }
        return try {
            val doc =
                Part21Reader.readRawDocument(
                    String(content, StandardCharsets.ISO_8859_1),
                    Part21ReadMode.TOLERANT,
                )
            val products = mutableMapOf<String, MutableList<String>>()
            val usages = mutableMapOf<String, MutableList<String>>()
            for (instance in doc.instances) {
                if (instance !is Part21SimpleInstance) continue
                val target =
                    when {
                        instance.entityName.equals("PRODUCT", ignoreCase = true) -> products
                        instance.entityName.equals("NEXT_ASSEMBLY_USAGE_OCCURRENCE", ignoreCase = true) -> usages
                        else -> continue
                    }
                val id = (instance.args.getOrNull(0) as? Part21Value.Str)?.text
                val name = (instance.args.getOrNull(1) as? Part21Value.Str)?.text
                if (id != null && name != null) target.getOrPut(name) { mutableListOf() }.add(id)
            }
            Enrichment(
                products.filterValues { it.size == 1 }.mapValues { StepInputValidation.sanitize(it.value[0]) },
                usages.filterValues { it.size == 1 }.mapValues { StepInputValidation.sanitize(it.value[0]) },
            )
        } catch (e: Exception) {
            warnings.add("product identifiers not read: ${e.javaClass.simpleName}")
            Enrichment(emptyMap(), emptyMap())
        }
    }
}
