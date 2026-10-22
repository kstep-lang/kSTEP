// export-boolean-common.kstep.kts -- Common of two overlapping 20x20x10 boxes (Welle J, ADR-0029),
// exported with its product structure via `kstep export`. Expected: volume/faces 1000, 6 faces.
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
    product("BOOL-COMMON") {
        name = "Boolean common"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOOL-COMMON-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOOL-COMMON-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val boxA = OcctKernel.makeBox(20.0, 20.0, 10.0)
val boxB = OcctKernel.translate(OcctKernel.makeBox(20.0, 20.0, 10.0), 10.0, 10.0, 0.0)
// Common (overlap) of box A and the shifted copy: a 10x10x10 cube = 1000.
val shape = OcctKernel.common(boxA, boxB)

stepFile(fileName = "boolean-common.step") {
    root(definition)
    shape(definition, shape)
}
