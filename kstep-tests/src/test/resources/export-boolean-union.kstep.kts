// export-boolean-union.kstep.kts -- Union of two overlapping 20x20x10 boxes (Welle J, ADR-0029),
// exported with its product structure via `kstep export`. Expected: volume/faces 7000, 10 faces.
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
    product("BOOL-UNION") {
        name = "Boolean union"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOOL-UNION-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOOL-UNION-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val boxA = OcctKernel.makeBox(20.0, 20.0, 10.0)
val boxB = OcctKernel.translate(OcctKernel.makeBox(20.0, 20.0, 10.0), 10.0, 10.0, 0.0)
// Union of box A (20x20x10) and a copy of it shifted by (10, 10, 0): 2*4000 - 1000 = 7000.
val shape = OcctKernel.union(boxA, boxB)

stepFile(fileName = "boolean-union.step") {
    root(definition)
    shape(definition, shape)
}
