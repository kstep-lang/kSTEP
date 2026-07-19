package dev.kstep.core

/**
 * The outcome of building a kSTEP DSL entity instance: either the valid, fully-constructed
 * value, or the complete list of structural and WHERE-rule violations found while building
 * it. A validation failure is expected, recoverable, structured data — building an entity
 * never throws for it, only [DslViolation]s in an [Invalid] list.
 */
sealed interface ValidationResult<out T> {
    data class Valid<T>(
        val value: T,
    ) : ValidationResult<T>

    data class Invalid(
        val violations: List<DslViolation>,
    ) : ValidationResult<Nothing>
}

fun <T> ValidationResult<T>.isValid(): Boolean = this is ValidationResult.Valid

fun <T> ValidationResult<T>.getOrThrow(): T =
    when (this) {
        is ValidationResult.Valid -> value
        is ValidationResult.Invalid ->
            error("kSTEP DSL validation failed: ${violations.joinToString("; ") { it.message }}")
    }
