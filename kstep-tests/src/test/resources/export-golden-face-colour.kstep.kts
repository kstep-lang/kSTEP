// export-golden-face-colour.kstep.kts -- 10x20x30 box with ONLY a face colour and a fixed timestamp. Byte-exact output (export-golden-face-colour.step) generated BEFORE ADR-0039 S2 (OVER_RIDING_STYLED_ITEM) as a single-style regression guard.
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
    product("FCOL-001") {
        name = "Box with face colour"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("FCOL-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("FCOL-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

stepFile(fileName = "golden-face-colour.step", timestamp = "2026-01-01T00:00:00") {
    root(definition)
    shape(definition, OcctKernel.makeBox(10.0, 20.0, 30.0))
    styleFaces(definition, Faces.atMax(Direction3.Z), colour = StepColour.rgb(0.0, 0.0, 1.0), expected = 1)
}
