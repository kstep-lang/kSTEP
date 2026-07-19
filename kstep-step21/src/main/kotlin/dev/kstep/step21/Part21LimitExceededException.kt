package dev.kstep.step21

/**
 * Thrown when a DoS/resource guard trips: input source length, instance count, value-nesting
 * depth, reference-chain/topological-sort depth, or the writer's reachability-walk depth. This
 * module is not a general-purpose STEP file importer for arbitrary real-world files — only for
 * kSTEP's own, small, six-entity V1 product-structure graphs — so these caps are generous
 * relative to V1's true shapes but still bounded.
 */
class Part21LimitExceededException(
    message: String,
) : RuntimeException(message)
