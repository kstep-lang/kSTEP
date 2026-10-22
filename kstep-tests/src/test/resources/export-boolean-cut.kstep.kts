// export-boolean-cut.kstep.kts -- Cut of two overlapping 20x20x10 boxes (Welle J, ADR-0029),
// exported with its product structure via `kstep export`. Expected: volume/faces 3000, 8 faces.
// The operands are intermediates left to the OcctShape Cleaner; only `shape` is registered.
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
    product("BOOL-CUT") {
        name = "Boolean cut"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOOL-CUT-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOOL-CUT-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val boxA = OcctKernel.makeBox(20.0, 20.0, 10.0)
val boxB = OcctKernel.translate(OcctKernel.makeBox(20.0, 20.0, 10.0), 10.0, 10.0, 0.0)
// Cut: box A minus the shifted copy = 4000 - 1000 = 3000 (an L-shaped prism).
val shape = OcctKernel.cut(boxA, boxB)

stepFile(fileName = "boolean-cut.step") {
    root(definition)
    shape(definition, shape)
}
