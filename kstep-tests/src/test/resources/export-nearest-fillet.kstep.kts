// export-nearest-fillet.kstep.kts -- 10x20x30 box, the two vertical edges nearest to (100, 10, 15) rounded (ADR-0037).
// Expected: volume 6000 - 2*(4 - pi)*30 = 5948.4956, 8 faces, 1 solid.
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
    product("NEAR-FIL") {
        name = "Box with the edges nearest a point rounded"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("NEAR-FIL-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("NEAR-FIL-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// Edges.nearest measures the geometric distance to each edge: 90.55 for the two vertical edges at x = 10
// (the horizontal ones are 91.24 away), so exactly these two are selected.
val shape = OcctKernel.fillet(OcctKernel.makeBox(10.0, 20.0, 30.0), Edges.nearest(100.0, 10.0, 15.0), 2.0, expected = 2)

stepFile(fileName = "nearest-fillet.step") {
    root(definition)
    shape(definition, shape)
}
