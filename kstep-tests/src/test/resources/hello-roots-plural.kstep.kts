// hello-roots-plural.kstep.kts -- exercises the plural roots(vararg) form with a mix of the two
// accepted shapes in a single call: an already-unwrapped entity (getOrThrow()) and a raw
// ValidationResult.Valid (non-throwing form). Regression fixture for the vararg-erasure bug
// where roots(...) always stored the raw ValidationResult wrapper instead of dispatching to the
// matching root(entity)/root(result) behavior.

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
stepFile(fileName = "hello-roots-plural.step") {
    roots(
        // Already-unwrapped entity (raw Any shape).
        nextAssemblyUsageOccurrence("NAUO-001") {
            name = "housing to bracket"
            relatingProductDefinition = housingDefinition
            relatedProductDefinition = bracketDefinition
            referenceDesignator = "RD-1"
        }.getOrThrow(),
        // Raw ValidationResult.Valid (must be unwrapped to its .value, not stored as-is).
        nextAssemblyUsageOccurrence("NAUO-002") {
            name = "housing to screw"
            relatingProductDefinition = housingDefinition
            relatedProductDefinition = screwDefinition
            referenceDesignator = "RD-2"
        },
    )
}
