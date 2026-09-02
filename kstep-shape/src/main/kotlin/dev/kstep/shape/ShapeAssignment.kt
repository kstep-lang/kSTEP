package dev.kstep.shape

import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.OcctShape

/**
 * Genuinely one AP242 product definition, coupled to genuinely one OCCT solid.
 *
 * Deliberately 1:1 in this wave (kSTEP Geometrie Welle 4, see ADR-0009): multiple
 * representations per product definition and assembly geometry (placing several
 * [ShapeAssignment]s relative to one another via `NEXT_ASSEMBLY_USAGE_OCCURRENCE` +
 * `ITEM_DEFINED_TRANSFORMATION`) are Folge-Wellen, not this one.
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
)
