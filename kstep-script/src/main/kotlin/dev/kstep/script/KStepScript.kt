package dev.kstep.script

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/**
 * kSTEP script definition.
 *
 * Files with the extension `*.kstep.kts` are compiled and evaluated using this definition. The
 * [defaultImports] make the six `kstep-core` AP242 V1 builders, the `dev.kstep.core`/
 * `dev.kstep.step21` runtime types, and the [stepFile] DSL entry point available without
 * explicit imports.
 *
 * Minimal script example:
 * ```kotlin
 * // hello.kstep.kts
 * val bracket = product("BRK-001") { name = "Bracket" }.getOrThrow()
 * val bracketFormation = productDefinitionFormation("BRK-001-F") { ofProduct = bracket }.getOrThrow()
 * val definition = productDefinition("BRK-001-D") { formation = bracketFormation }.getOrThrow()
 *
 * stepFile(fileName = "hello.step") {
 *     root(definition)
 * }
 * ```
 */
@KotlinScript(
    displayName = "kSTEP Script",
    fileExtension = "kstep.kts",
    compilationConfiguration = KStepScriptCompilationConfiguration::class,
)
abstract class KStepScript

/**
 * Compilation configuration for `*.kstep.kts` scripts.
 *
 * Uses [dependenciesFromCurrentContext] with `wholeClasspath = true` so the full classpath of
 * the calling JVM (which includes `kstep-core`, `kstep-step21`, and this module) is available
 * inside scripts without explicit dependency declarations. This is the *trusted, in-process*
 * path only — see [KStepScriptHost]'s KDoc for why no curated/sandboxed classpath is used here.
 */
object KStepScriptCompilationConfiguration : ScriptCompilationConfiguration({
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    defaultImports(
        // ValidationResult, getOrThrow, isValid, DslViolation, DslViolationCodes
        "dev.kstep.core.*",
        // The six V1 builder functions: product, personAndOrganization, approval,
        // productDefinitionFormation, productDefinition, nextAssemblyUsageOccurrence
        "dev.kstep.core.ap242.*",
        // Part21Header, Part21Writer, Part21Reader and their exception types
        "dev.kstep.step21.*",
        // stepFile { } / KStepModel / KStepModelBuilder
        "dev.kstep.script.*",
    )
})
