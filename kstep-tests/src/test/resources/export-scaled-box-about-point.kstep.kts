// export-scaled-box-about-point.kstep.kts -- A 10x20x30 box scaled by 0.5 about the point (5, 10, 15) (Welle P, ADR-0036).
// Expected: volume 750, bounding box [2.5, 7.5] x [5, 15] x [7.5, 22.5], 6 faces.
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
    product("SCALE-POINT") {
        name = "Scaled box about a point"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SCALE-POINT-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SCALE-POINT-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = scale(OcctKernel.makeBox(10.0, 20.0, 30.0), 0.5, Point3(5.0, 10.0, 15.0))

stepFile(fileName = "scaled-box-about-point.step") {
    root(definition)
    shape(definition, shape)
}
