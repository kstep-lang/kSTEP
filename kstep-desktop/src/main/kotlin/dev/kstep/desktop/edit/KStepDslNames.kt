package dev.kstep.desktop.edit

/**
 * Names of the kSTEP script DSL that the editor highlights (ADR-0052). A hand-kept list: a test checks that every name
 * is still declared in the script, shape and geometry sources. Generating it from the EXPRESS schema is a follow-up.
 */
object KStepDslNames {
    val names: Set<String> = setOf("stepFile", "roots", "root", "shape", "place", "style", "vec", "OcctKernel")
}
