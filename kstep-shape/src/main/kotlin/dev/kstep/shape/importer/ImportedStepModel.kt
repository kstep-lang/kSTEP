package dev.kstep.shape.importer

import dev.kstep.geometry.BoundingBox
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.Placement
import dev.kstep.geometry.RgbColor
import dev.kstep.geometry.ShapeTopology
import dev.kstep.geometry.StepLengthUnitInfo
import dev.kstep.geometry.StepReadStats
import dev.kstep.geometry.XcafNodeKind

/** Where the product structure of an [ImportedStepModel] came from. */
enum class StructureSource { XCAF, PART21 }

/**
 * A rigid placement read from a STEP file.
 *
 * @property placement the placement as a [Placement], or `null` if the transform carries scaling,
 *   a reflection or numeric noise beyond 1e-9 (then [rigid] is false and no geometry is moved).
 * @property matrix row-major 3x4 `[r00 r01 r02 tx, ...]`; identity-shaped when the source had none.
 */
class ImportedPlacement(
    val placement: Placement?,
    val matrix: DoubleArray,
    val rigid: Boolean,
)

/**
 * One distinct part. [index] is its position in [ImportedStepModel.parts]; [shape] is the part's own
 * B-Rep in part coordinates (owned by the model), `null` if the file carries no geometry for it.
 */
class ImportedPart(
    val index: Int,
    val name: String,
    val productId: String?,
    val shape: OcctShape?,
    val volume: Double?,
    val topology: ShapeTopology?,
    val boundingBox: BoundingBox?,
    /** Per-solid volume and face count when the part has more than one solid, else `null` (ADR-0032). */
    val bodies: List<ImportedBody>? = null,
    /** Colour of the part (ADR-0038), or `null`. A part with a single coloured solid reports that colour. */
    val color: RgbColor? = null,
    /** Layer names of the part, sorted and without duplicates. */
    val layers: List<String> = emptyList(),
    /** Faces with their own colour or layer, sorted by [ImportedFaceStyle.faceIndex]. */
    val faceStyles: List<ImportedFaceStyle> = emptyList(),
    /** Count of faces per surface kind (`plane`, `cylinder`, `bsplineSurface`, ...), `null` if not computed. */
    val surfaceTypes: Map<String, Int>? = null,
)

/** One body (solid) of a multi-body [ImportedPart]. */
class ImportedBody(
    val volume: Double,
    val faces: Int,
    val color: RgbColor? = null,
    val layers: List<String> = emptyList(),
)

/**
 * Colour and layers of one face. [faceIndex] is 0-based in OCCT's face enumeration of the part, i.e.
 * `OcctShape.faces[faceIndex]`.
 */
class ImportedFaceStyle(
    val faceIndex: Int,
    val color: RgbColor?,
    val layers: List<String>,
)

/** Totals of the colour and layer data of a file (ADR-0038). */
class ImportPresentation(
    val coloredTargets: Int,
    val layerAssignments: Int,
    val layerNames: List<String>,
    val unattached: Int,
    val instanceStylesIgnored: Int,
    val truncated: Int,
)

/**
 * A usage of a part or sub-assembly inside an assembly. [childIndex] indexes
 * [ImportedStepModel.parts] when [childKind] is [XcafNodeKind.PART], else
 * [ImportedStepModel.assemblies].
 */
class ImportedComponent(
    val name: String,
    val usageId: String?,
    val childKind: XcafNodeKind,
    val childIndex: Int,
    val local: ImportedPlacement,
)

class ImportedAssembly(
    val index: Int,
    val name: String,
    val productId: String?,
    val components: List<ImportedComponent>,
)

class ImportedRoot(
    val kind: XcafNodeKind,
    val index: Int,
)

/** One placed part instance after expanding the assembly DAG. [path] holds the component names, outermost first. */
class ImportedOccurrence(
    val path: List<String>,
    val partIndex: Int,
    val global: ImportedPlacement,
    val boundingBox: BoundingBox?,
)

class ImportedTotals(
    val occurrenceCount: Int,
    val volume: Double?,
    val boundingBox: BoundingBox?,
)

/**
 * The result of reading a STEP file back: structure, per-part geometry and placed occurrences (ADR-0030).
 * Owns every part [OcctShape]; [close] releases them all.
 */
class ImportedStepModel(
    val prescan: StepPrescan,
    val stats: StepReadStats,
    val lengthUnit: String,
    val structureSource: StructureSource,
    val parts: List<ImportedPart>,
    val assemblies: List<ImportedAssembly>,
    val roots: List<ImportedRoot>,
    val occurrences: List<ImportedOccurrence>,
    val totals: ImportedTotals,
    val warnings: List<String>,
    /** The length unit the file declares; the geometry itself is always in [lengthUnit]. */
    val sourceLengthUnit: StepLengthUnitInfo? = null,
    val presentation: ImportPresentation? = null,
) : AutoCloseable {
    override fun close() {
        parts.forEach { it.shape?.close() }
    }
}

/** Outcome of [StepImporter.read]. A [Success] owns native resources; close its [model]. */
sealed interface StepImportResult {
    class Success(
        val model: ImportedStepModel,
    ) : StepImportResult

    data class Failure(
        val kind: StepImportErrorKind,
        val message: String,
    ) : StepImportResult
}
