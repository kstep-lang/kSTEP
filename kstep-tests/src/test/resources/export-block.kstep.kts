// export-block.kstep.kts -- release smoke fixture for `kstep export --all-targets` (ADR-0065): the 3-part
// assembly of hello-assembly.kstep.kts plus an export { } block declaring one STEP target (STEP only, the
// release zip ships no native bridge). No imports needed: applicationContext/productContext/
// productDefinitionContext/product/productDefinition/productDefinitionFormation/
// nextAssemblyUsageOccurrence/stepFile are all provided by the kSTEP script definition's
// defaultImports (dev.kstep.script.KStepScriptCompilationConfiguration).
//
// Run: kstep export export-block.kstep.kts --all-targets  ->  writes export-block-smoke.step

// Shared context, built once and reused by every product/product_definition below: the real
// AP242 schema requires both of these (SET [1:?] OF product_context on product, a single
// product_definition_context on product_definition) -- kstep-core deliberately does not invent
// a placeholder instance behind the script's back, see docs/adr/ADR-0004.
val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
val prodCtx =
    productContext {
        name = "engineering"
        frameOfReference = appCtx
        disciplineType = "mechanical"
    }.getOrThrow()
val defCtx =
    productDefinitionContext {
        name = "engineering"
        frameOfReference = appCtx
        lifeCycleStage = "design"
    }.getOrThrow()

val bracket =
    product("BRK-001") {
        name = "Bracket"
        description = "Mounting bracket"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val bracketFormation = productDefinitionFormation("BRK-001-F") { ofProduct = bracket }.getOrThrow()
val bracketDefinition =
    productDefinition("BRK-001-D") {
        formation = bracketFormation
        frameOfReference = defCtx
    }.getOrThrow()

val screw =
    product("SCR-001") {
        name = "Screw"
        description = "M4x10 fastener"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val screwFormation = productDefinitionFormation("SCR-001-F") { ofProduct = screw }.getOrThrow()
val screwDefinition =
    productDefinition("SCR-001-D") {
        formation = screwFormation
        frameOfReference = defCtx
    }.getOrThrow()

val housing =
    product("HSG-001") {
        name = "Housing"
        description = "Enclosure housing"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val housingFormation = productDefinitionFormation("HSG-001-F") { ofProduct = housing }.getOrThrow()
val housingDefinition =
    productDefinition("HSG-001-D") {
        formation = housingFormation
        frameOfReference = defCtx
    }.getOrThrow()

// Last expression: the KStepModel the host exports.
stepFile(fileName = "hello-assembly.step") {
    export { step("export-block-smoke.step") }
    root(
        nextAssemblyUsageOccurrence("NAUO-001") {
            name = "housing to bracket"
            relatingProductDefinition = housingDefinition
            relatedProductDefinition = bracketDefinition
            referenceDesignator = "RD-1"
        }.getOrThrow(),
    )
    root(
        nextAssemblyUsageOccurrence("NAUO-002") {
            name = "housing to screw"
            relatingProductDefinition = housingDefinition
            relatedProductDefinition = screwDefinition
            referenceDesignator = "RD-2"
        }.getOrThrow(),
    )
}
