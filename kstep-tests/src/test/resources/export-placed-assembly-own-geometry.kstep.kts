// export-placed-assembly-own-geometry.kstep.kts -- like export-assembly-geometry.kstep.kts (the
// housing carries a box AND contains the bracket), but with the bracket placed (ADR-0028). Whether
// a reader keeps the housing's own shape next to its component is documented in ADR-0028, 'Verification'.
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

val usage =
    nextAssemblyUsageOccurrence("NAUO-001") {
        name = "housing to bracket"
        relatingProductDefinition = housingDefinition
        relatedProductDefinition = bracketDefinition
        referenceDesignator = "RD-1"
    }.getOrThrow()

stepFile(fileName = "assembly-own-geometry.step") {
    root(usage)
    shape(housingDefinition, OcctKernel.makeBox(50.0, 40.0, 30.0))
    shape(bracketDefinition, OcctKernel.makeBox(10.0, 20.0, 5.0))
    place(usage, translation = vec(50.0, 0.0, 0.0))
}
