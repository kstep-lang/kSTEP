package dev.kstep.step21

/**
 * Thrown when the raw `#N` reference graph contains a cycle. Genuinely impossible for the six
 * real V1 entity shapes (they form a strict, acyclic reference DAG — leaves never point back
 * "up"), but a hostile or hand-corrupted Part-21 file can still claim one, so this is detected
 * structurally before any per-type reconstruction is attempted.
 */
class Part21CycleException(
    message: String,
) : RuntimeException(message)
