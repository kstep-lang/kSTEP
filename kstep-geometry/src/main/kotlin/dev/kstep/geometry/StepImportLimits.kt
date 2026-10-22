package dev.kstep.geometry

/**
 * Hard limits for reading a STEP file back (ADR-0030, Welle K). The file-size and entity-count limits
 * are checked before the transfer; the part, node and face limits can only be evaluated on the
 * transferred document, so OCCT has already built the B-Reps when they fire (bounded by the file-size
 * and entity-count limits; there is no time limit). The native entries mirror the `limits` array of
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

    /** Maximum faces summed over all distinct parts. */
    const val MAX_TOTAL_FACES: Int = 250_000

    /** Names are truncated to this many UTF-16 code units. */
    const val MAX_NAME_CHARS: Int = 256

    /** Maximum part occurrences after expanding the assembly DAG (Kotlin side). */
    const val MAX_EXPANDED_OCCURRENCES: Int = 10_000

    /** Maximum assembly nesting depth during expansion (Kotlin side). */
    const val MAX_ASSEMBLY_DEPTH: Int = 32

    /** Maximum distinct unknown entity type names reported. */
    const val MAX_REPORTED_UNKNOWN_TYPES: Int = 20

    internal fun nativeLimits(): IntArray =
        intArrayOf(MAX_ENTITIES, MAX_NODES, MAX_COMPONENTS, MAX_PARTS, MAX_TOTAL_FACES, MAX_NAME_CHARS)
}
