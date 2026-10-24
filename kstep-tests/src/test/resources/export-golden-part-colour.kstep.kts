// export-golden-part-colour.kstep.kts -- 10x20x30 box with ONLY a part colour and a fixed timestamp. Byte-exact output (export-golden-part-colour.step) generated BEFORE ADR-0039 S2 (OVER_RIDING_STYLED_ITEM) as a single-style regression guard.
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
val part =
    product("PCOL-001") {
        name = "Box with part colour"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("PCOL-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("PCOL-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

stepFile(fileName = "golden-part-colour.step", timestamp = "2026-01-01T00:00:00") {
    root(definition)
    shape(definition, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.5, 0.0))
}
