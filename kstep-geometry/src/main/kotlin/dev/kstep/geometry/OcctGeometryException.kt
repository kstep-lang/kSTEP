package dev.kstep.geometry

/**
 * Thrown when a native OCCT operation (box construction, STEP export, ...) fails inside OCCT
 * itself, after the native bridge was already confirmed available -- as opposed to
 * [OcctUnavailableException], which means the bridge could not be loaded at all.
 */
class OcctGeometryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
