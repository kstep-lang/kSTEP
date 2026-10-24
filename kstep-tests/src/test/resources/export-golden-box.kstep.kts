// export-golden-box.kstep.kts -- single 20x30x40 box with a fixed timestamp. Byte-exact output (export-golden-box.step) generated BEFORE ADR-0039 as an unstyled regression guard (Welle S).
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
    product("BOX-001") {
        name = "Box"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOX-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOX-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()
val box = OcctKernel.makeBox(20.0, 30.0, 40.0)

stepFile(fileName = "golden-box.step", timestamp = "2026-01-01T00:00:00") {
    root(definition)
    shape(definition, box)
}
