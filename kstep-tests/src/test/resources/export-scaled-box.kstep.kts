// export-scaled-box.kstep.kts -- A 10x20x30 box scaled by 2 about the origin (Welle P, ADR-0036).
// Expected: volume 48000, bounding box [0, 20] x [0, 40] x [0, 60], 6 faces.
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
    product("SCALE-BOX") {
        name = "Scaled box"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SCALE-BOX-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SCALE-BOX-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = scale(OcctKernel.makeBox(10.0, 20.0, 30.0), 2.0)

stepFile(fileName = "scaled-box.step") {
    root(definition)
    shape(definition, shape)
}
