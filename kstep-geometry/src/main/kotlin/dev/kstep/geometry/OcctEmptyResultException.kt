package dev.kstep.geometry

/**
 * Thrown by a boolean operation ([OcctKernel.cut], [OcctKernel.common], [OcctKernel.hole], ...)
 * whose result contains no solid volume at all -- e.g. the common of two disjoint solids, or a cut
 * that removes everything. A subtype of [OcctGeometryException] so a caller that does not care
 * about the distinction keeps working; the native bridge throws this class directly
 * (`FindClass("dev/kstep/geometry/OcctEmptyResultException")`), which is why the `(String)`
 * constructor below must exist and keep exactly this signature.
 */
class OcctEmptyResultException(
    message: String,
) : OcctGeometryException(message)
