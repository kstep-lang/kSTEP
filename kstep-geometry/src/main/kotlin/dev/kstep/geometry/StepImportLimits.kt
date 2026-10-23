package dev.kstep.geometry

/**
 * Hard limits for reading a STEP file back (ADR-0030, Welle K). The file-size and entity-count limits
 * are checked before the transfer; the part, node and face limits can only be evaluated on the
 * transferred document, so OCCT has already built the B-Reps when they fire (bounded by the file-size
 * and entity-count limits; the library has no time or memory limit, only the `kstep import` CLI command runs the
 * import in a killable worker subprocess with a wall-clock and heap bound, see ADR-0038). The native entries mirror the `limits` array of
 * [dev.kstep.geometry.occt.OcctBridge.nativeReadStepXcaf] (hand-synchronized pair, same pattern as
 * ADR-0007/ADR-0029); the expansion limits are Kotlin-side only.
 */
object StepImportLimits {
    /** Maximum input size in bytes (checked before and while reading the file). */
    const val MAX_FILE_BYTES: Long = 50_000_000

    /** Maximum STEP entity instances, checked natively before the XCAF transfer. */
    const val MAX_ENTITIES: Int = 1_000_000

    /** Maximum XCAF nodes (parts + assemblies + components). */
    const val MAX_NODES: Int = 10_000

    /** Maximum assembly components (NAUOs). */
    const val MAX_COMPONENTS: Int = 10_000

    /** Maximum distinct parts. */
    const val MAX_PARTS: Int = 2_000

    /**
     * Maximum faces summed over all distinct parts. The default value is also, hand-synchronized as
     * `kMaxShellFaceReferences`, the fixed bound of the summed shell face-list lengths checked before the transfer.
     */
    const val MAX_TOTAL_FACES: Int = 250_000

    /** Names are truncated to this many UTF-16 code units. */
    const val MAX_NAME_CHARS: Int = 256

    /**
     * Maximum part occurrences after expanding the assembly DAG: during expansion (Kotlin side) and, hand-synchronized
     * as `kMaxExpandedOccurrences`, as a path-multiplicity count on the typed usage graph before the transfer
     * (ADR-0038), so a diamond chain is rejected before OCCT expands it.
     */
    const val MAX_EXPANDED_OCCURRENCES: Int = 10_000

    /**
     * Maximum assembly nesting depth: during expansion (Kotlin side) and, hand-synchronized as `kMaxAssemblyDepth`
     * in kstep_occt_bridge.cpp, in the typed pre-transfer check of the native reader (ADR-0038).
     */
    const val MAX_ASSEMBLY_DEPTH: Int = 32

    /**
     * Maximum PRESENTATION_LAYER_ASSIGNMENT instances, counted natively before the transfer (hand-synchronized
     * as `kMaxLayerAssignments` in kstep_occt_bridge.cpp): OCCT's layer reader is quadratic in this count.
     */
    const val MAX_LAYER_ASSIGNMENTS: Int = 4_096

    /**
     * Maximum colour definition entities (COLOUR_RGB and siblings), counted natively before the transfer
     * (hand-synchronized as `kMaxColourDefinitions`): OCCT's colour reader is quadratic in distinct colours.
     */
    const val MAX_COLOUR_DEFINITIONS: Int = 10_000

    /**
     * Maximum SURFACE_STYLE_TRANSPARENT entities (hand-synchronized as `kMaxTransparencyDefinitions`): the colour table
     * is keyed by RGBA, so distinct colour x transparency pairs make the colour reader quadratic as well.
     */
    const val MAX_TRANSPARENCY_DEFINITIONS: Int = 10_000

    /**
     * Maximum SURFACE_STYLE_RENDERING_WITH_PROPERTIES entities (hand-synchronized as `kMaxRenderingPropertyRecords`):
     * each one yields at most one distinct RGBA colour-table entry.
     */
    const val MAX_RENDERING_PROPERTY_RECORDS: Int = 10_000

    /**
     * Maximum STYLED_ITEM / OVER_RIDING_STYLED_ITEM entities (hand-synchronized as `kMaxStyledItems`): every styled item
     * triggers one linear scan of the colour table in the colour reader.
     */
    const val MAX_STYLED_ITEMS: Int = 200_000

    /**
     * Maximum of styled items x (colour definitions + rendering records) (hand-synchronized as
     * `kMaxStyledItemColourWork`): bounds the total colour-table scan work of the colour reader.
     */
    const val MAX_STYLED_ITEM_COLOUR_WORK: Long = 100_000_000L

    /**
     * Maximum length of the style list of a STYLED_ITEM, PRESENTATION_STYLE_ASSIGNMENT or SURFACE_SIDE_STYLE, of the
     * fill-style list of a FILL_AREA_STYLE and of the property list of a SURFACE_STYLE_RENDERING_WITH_PROPERTIES
     * (hand-synchronized as `kMaxStyleListLength`): the colour reader walks the whole list once per styled item, and a
     * list can repeat one reference cheaply. Real files use 1-3 entries.
     */
    const val MAX_STYLE_LIST_LENGTH: Int = 64

    /**
     * Maximum weighted expansion cost of all styled items, summed natively before the transfer (hand-synchronized as
     * `kMaxStyleExpansionWork`). The colour reader walks styled item -> presentation style assignments -> side-style
     * elements -> fill styles as nested loops, so capped lists still multiply (64^4 per styled item). One styled item
     * costs sum over PSA (sum over styles (sum over side-style elements (max(1, fill styles or properties)))); real
     * files cost 1-3 per styled item. See ADR-0038 for the measured budget.
     */
    const val MAX_STYLE_EXPANSION_WORK: Long = 10_000_000L

    /**
     * Maximum depth of an OVER_RIDING_STYLED_ITEM chain, checked natively before the transfer (hand-synchronized as
     * `kMaxOverrideDepth`); a cycle is rejected as well. OCCT follows the chain recursively (ADR-0038).
     */
    const val MAX_OVERRIDE_DEPTH: Int = 16

    /**
     * Maximum MAPPED_ITEM expansions (path multiplicity of the representation -> REPRESENTATION_MAP graph), checked
     * natively before the transfer (hand-synchronized as `kMaxMappedItemExpansions`); the depth of that graph is bounded
     * by [MAX_ASSEMBLY_DEPTH] and a cycle is rejected (ADR-0038).
     */
    const val MAX_MAPPED_ITEM_EXPANSIONS: Long = 10_000L

    /** Maximum distinct unknown entity type names reported. */
    const val MAX_REPORTED_UNKNOWN_TYPES: Int = 20

    internal fun nativeLimits(): IntArray =
        intArrayOf(MAX_ENTITIES, MAX_NODES, MAX_COMPONENTS, MAX_PARTS, MAX_TOTAL_FACES, MAX_NAME_CHARS)
}
