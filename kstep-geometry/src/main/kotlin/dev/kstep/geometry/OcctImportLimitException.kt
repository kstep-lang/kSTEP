package dev.kstep.geometry

/**
 * Thrown when a STEP file read back through [OcctKernel.readStep] exceeds one of the
 * [StepImportLimits]. A subtype of [OcctGeometryException] so a caller that does not care about
 * the distinction keeps working.
 */
class OcctImportLimitException(
    message: String,
) : OcctGeometryException(message)
