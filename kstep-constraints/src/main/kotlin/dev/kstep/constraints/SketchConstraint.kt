package dev.kstep.constraints

/**
 * A 2D geometric constraint for [PlaneGcsSolver.solve]. Every implementation addresses points
 * exclusively through indices into the accompanying `points` list -- this module introduces no
 * line/entity concept of its own on the Kotlin side (see `docs/adr/ADR-0007-planegcs-additional-
 * constraint-types.adoc`'s "Decision" and `docs/adr/ADR-0014-planegcs-parallel-and-perpendicular
 * .adoc`'s "Decision"). This holds even for [ParallelConstraint], the one constraint PlaneGCS
 * itself models over a `GCS::Line` (`addConstraintParallel(Line&, Line&)`, no `Point`-quadruple
 * overload exists): [ParallelConstraint]'s KDoc explains why that `Line` requirement is fully
 * absorbable as a stack-local, immediately-discarded adapter inside the native bridge, verified
 * against the vendored source rather than assumed. A genuine `SketchLine` *entity* type (as
 * opposed to this index-pair encoding) remains out of scope until C-Welle 3, where it arrives
 * together with `Circle`/`Arc` and their own associated constraints.
 *
 * `sealed` here is the load-bearing property: [PlaneGcsSolver]'s internal encoding functions
 * (`kind()`, `pointIndices()`, `parameter()`) and its validation switch all use an exhaustive
 * `when` over this hierarchy, so the Kotlin compiler refuses to compile if a future constraint type
 * is added to this file without also being wired into every one of those places.
 */
sealed interface SketchConstraint
