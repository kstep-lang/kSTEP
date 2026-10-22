// export-assembly-geometry.kstep.kts -- a two-part assembly (housing contains a bracket) where
// the housing and the bracket each carry a box, for `kstep export` (ADR-0027). The usage is not
// placed explicitly, so since ADR-0028 it is placed at the identity: both boxes overlap at the
// origin. See export-placed-assembly-own-geometry.kstep.kts for the placed variant.
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

val housing = product("HSG-001") { name = "Housing"; frameOfReference = setOf(prodCtx) }.getOrThrow()
val housingFormation = productDefinitionFormation("HSG-001-F") { ofProduct = housing }.getOrThrow()
val housingDefinition =
    productDefinition("HSG-001-D") { formation = housingFormation; frameOfReference = defCtx }.getOrThrow()

val bracket = product("BRK-001") { name = "Bracket"; frameOfReference = setOf(prodCtx) }.getOrThrow()
val bracketFormation = productDefinitionFormation("BRK-001-F") { ofProduct = bracket }.getOrThrow()
val bracketDefinition =
    productDefinition("BRK-001-D") { formation = bracketFormation; frameOfReference = defCtx }.getOrThrow()

val housingBox = OcctKernel.makeBox(50.0, 40.0, 30.0)
val bracketBox = OcctKernel.makeBox(10.0, 20.0, 5.0)

stepFile(fileName = "assembly.step") {
    root(
        nextAssemblyUsageOccurrence("NAUO-001") {
            name = "housing to bracket"
            relatingProductDefinition = housingDefinition
            relatedProductDefinition = bracketDefinition
            referenceDesignator = "RD-1"
        }.getOrThrow(),
    )
    shape(housingDefinition, housingBox)
    shape(bracketDefinition, bracketBox)
}
