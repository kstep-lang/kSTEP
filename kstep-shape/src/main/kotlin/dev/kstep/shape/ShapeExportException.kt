package dev.kstep.shape

/**
 * Thrown by [Ap242ShapeExporter] when OCCT's own STEP output does not have the structure this
 * module's merge logic requires — see [Ap242GeometryExtraction]'s KDoc for the specific checks. This is always a "kSTEP's assumption about OCCT's output shape was
 * wrong" signal, never a validation failure of kSTEP's own product-structure data (that surfaces
 * through `dev.kstep.core.ValidationResult` as usual, before [Ap242ShapeExporter.export] is ever
 * called — see [ShapeAssignment]'s KDoc).
 */
class ShapeExportException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
