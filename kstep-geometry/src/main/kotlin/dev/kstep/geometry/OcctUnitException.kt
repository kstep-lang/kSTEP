package dev.kstep.geometry

/**
 * Thrown when a STEP file read back through [OcctKernel.readStep] declares a length unit that cannot be
 * resolved (for example a conversion factor of zero). Raised before OCCT transfers any geometry.
 */
class OcctUnitException(
    message: String,
) : OcctGeometryException(message)
