package dev.kstep.geometry.select

/**
 * Common base of the strict selection failures for edges ([EdgeSelectionException]), faces
 * ([FaceSelectionException]) and vertices ([VertexSelectionException]). It is an
 * [IllegalArgumentException], so existing handlers keep working; scripts report it as `KSTEP-S-011`
 * (ADR-0037, D4).
 */
abstract class TopologySelectionException(
    message: String,
) : IllegalArgumentException(message)
