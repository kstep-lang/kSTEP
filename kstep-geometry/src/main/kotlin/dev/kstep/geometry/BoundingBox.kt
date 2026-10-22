package dev.kstep.geometry

/** An axis-aligned bounding box in model units (millimetres for imported STEP data). */
data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    /** The smallest box containing both. */
    fun union(other: BoundingBox): BoundingBox =
        BoundingBox(
            minOf(minX, other.minX),
            minOf(minY, other.minY),
            minOf(minZ, other.minZ),
            maxOf(maxX, other.maxX),
            maxOf(maxY, other.maxY),
            maxOf(maxZ, other.maxZ),
        )
}
