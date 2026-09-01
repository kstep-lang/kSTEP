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
 * [defaultImports] make the twelve `kstep-core` AP242 builders (kSTEP M2 Welle 10), the
 * codegen-generated `dev.kstep.generated.ap242v1.*` entity types, the `dev.kstep.core`/
 * `dev.kstep.step21` runtime types, and the [stepFile] DSL entry point available without
 * explicit imports.
 *
 * Minimal script example — as of kSTEP M2 Welle 10, `product` and `productDefinition` both need
 * an explicit context built first (`frame_of_reference` is a mandatory attribute on the real
 * AP242 schema, not something `kstep-core` invents a default for; see
 * `docs/adr/ADR-0004-core-on-generated-types.adoc`):
 * ```kotlin
 * // hello.kstep.kts
 * val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
 * val prodCtx = productContext {
 *     name = "engineering"
 *     frameOfReference = appCtx
 *     disciplineType = "mechanical"
 * }.getOrThrow()
 * val defCtx = productDefinitionContext {
 *     name = "engineering"
 *     frameOfReference = appCtx
 *     lifeCycleStage = "design"
 * }.getOrThrow()
 *
 * val bracket = product("BRK-001") { name = "Bracket"; frameOfReference = setOf(prodCtx) }.getOrThrow()
 * val bracketFormation = productDefinitionFormation("BRK-001-F") { ofProduct = bracket }.getOrThrow()
 * val definition = productDefinition("BRK-001-D") {
 *     formation = bracketFormation
 *     frameOfReference = defCtx
 * }.getOrThrow()
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
        // The twelve AP242 builder functions (kSTEP M2 Welle 10 — six V1 entities plus six
        // support entities): product, personAndOrganization, approval,
        // productDefinitionFormation, productDefinition, nextAssemblyUsageOccurrence,
        // applicationContext, productContext, productDefinitionContext, approvalStatus,
        // person, organization
        "dev.kstep.core.ap242.*",
        // The codegen-generated entity types themselves (Product, Person, ProductContext, ...)
        // — needed for scripts that write an explicit type annotation on a builder result
        // (e.g. `val ctx: ProductContext = productContext { ... }.getOrThrow()`), since the
        // builder functions in dev.kstep.core.ap242 return these types but do not re-export
        // them. Added kSTEP M2 Welle 10 alongside kstep-core's move onto these generated types.
        "dev.kstep.generated.ap242v1.*",
        // Part21Header, Part21Writer, Part21Reader and their exception types
        "dev.kstep.step21.*",
        // stepFile { } / KStepModel / KStepModelBuilder
        "dev.kstep.script.*",
    )
})
