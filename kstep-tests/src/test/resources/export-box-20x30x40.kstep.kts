// export-box-20x30x40.kstep.kts -- a 20x30x40 box with its product structure, for
// `kstep export` with geometry (ADR-0027). Fixed timestamp keeps the output reproducible.
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

stepFile(fileName = "box.step") {
    root(definition)
    shape(definition, box)
}
