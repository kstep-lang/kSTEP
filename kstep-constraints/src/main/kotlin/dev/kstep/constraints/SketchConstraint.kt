package dev.kstep.constraints

/**
 * A 2D geometric constraint for [PlaneGcsSolver.solve]. Every implementation addresses points
 * exclusively through indices into the accompanying `points` list -- this wave deliberately
 * introduces no line/entity concept of its own (see `docs/adr/ADR-0007-planegcs-additional-
 * constraint-types.adoc`'s "Decision"): a constraint that PlaneGCS itself models over a `GCS::Line`
 * (e.g. `addConstraintParallel`) is out of scope until a later wave actually needs that concept.
 *
 * `sealed` here is the load-bearing property: [PlaneGcsSolver]'s internal encoding functions
 * (`kind()`, `pointIndices()`, `parameter()`) and its validation switch all use an exhaustive
 * `when` over this hierarchy, so the Kotlin compiler refuses to compile if a future constraint type
 * is added to this file without also being wired into every one of those places.
 */
sealed interface SketchConstraint
