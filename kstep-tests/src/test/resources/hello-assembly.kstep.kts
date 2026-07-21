// hello-assembly.kstep.kts -- exports a 3-part assembly (housing containing a bracket and a
// screw) to a STEP Part 21 file. No imports needed: product/productDefinition/
// productDefinitionFormation/nextAssemblyUsageOccurrence/stepFile are all provided by the
// kSTEP script definition's defaultImports (dev.kstep.script.KStepScriptCompilationConfiguration).
//
// Run: kstep export hello-assembly.kstep.kts  ->  writes hello-assembly.step

val bracket =
    product("BRK-001") {
        name = "Bracket"
        description = "Mounting bracket"
    }.getOrThrow()
val bracketFormation = productDefinitionFormation("BRK-001-F") { ofProduct = bracket }.getOrThrow()
val bracketDefinition = productDefinition("BRK-001-D") { formation = bracketFormation }.getOrThrow()

val screw =
    product("SCR-001") {
        name = "Screw"
        description = "M4x10 fastener"
    }.getOrThrow()
val screwFormation = productDefinitionFormation("SCR-001-F") { ofProduct = screw }.getOrThrow()
val screwDefinition = productDefinition("SCR-001-D") { formation = screwFormation }.getOrThrow()

val housing =
    product("HSG-001") {
        name = "Housing"
        description = "Enclosure housing"
    }.getOrThrow()
val housingFormation = productDefinitionFormation("HSG-001-F") { ofProduct = housing }.getOrThrow()
val housingDefinition = productDefinition("HSG-001-D") { formation = housingFormation }.getOrThrow()

// Last expression: the KStepModel the host exports.
stepFile(fileName = "hello-assembly.step") {
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
