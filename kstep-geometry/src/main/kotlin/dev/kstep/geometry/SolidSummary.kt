package dev.kstep.geometry

/** Volume and face count of one solid of a (possibly multi-body) shape; see [OcctKernel.solidSummaries]. */
data class SolidSummary(
    val volume: Double,
    val faces: Int,
)
