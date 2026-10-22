package dev.kstep.geometry

/**
 * Thrown when a STEP file read back through [OcctKernel.readStep] contains document entities that
 * OCCT would resolve into extern-file references (ADR-0030). Detected on the parsed model before the
 * transfer, so no extern file is ever opened.
 */
class OcctExternalReferenceException(
    message: String,
) : OcctGeometryException(message)
