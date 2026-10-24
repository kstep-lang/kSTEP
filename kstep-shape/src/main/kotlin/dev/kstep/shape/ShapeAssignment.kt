package dev.kstep.shape

import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.style.ShapeStyle

/**
 * Genuinely one AP242 product definition, coupled to genuinely one OCCT solid.
 *
 * One shape per product definition (ADR-0009). Several assignments may be exported together
 * (ADR-0027), one part per assignment, each in its own coordinate system; the placement of a part
 * relative to its parent assembly is a property of the assembly usage, see [UsagePlacement] (ADR-0028).
 *
 * Ownership: [Ap242ShapeExporter] does NOT close [shape] — the caller retains ownership,
 * exactly like the documented rule for `OcctKernel.fillet`'s input shape. [productDefinition]
 * must already be a fully-validated instance (built via `dev.kstep.core.ap242.productDefinition`
 * and unwrapped with `dev.kstep.core.getOrThrow` or otherwise checked) — [Ap242ShapeExporter]
 * performs no `kstep-core` validation of its own, only structural checks of OCCT's output.
 */
data class ShapeAssignment(
    val productDefinition: ProductDefinition,
    val shape: OcctShape,
    /** The `name` of the emitted `PRODUCT_DEFINITION_SHAPE`. An empty string is the usual value. */
    val shapeName: String = "",
    /**
     * Colour and layers of the part and of some of its faces (ADR-0039). An annotation on the result: no shape
     * operation, no effect on the feature history. [ShapeStyle.NONE] keeps the export byte-identical to earlier versions.
     */
    val style: ShapeStyle = ShapeStyle.NONE,
)
