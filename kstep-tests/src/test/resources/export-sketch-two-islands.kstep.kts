// export-sketch-two-islands.kstep.kts -- two separate outer contours become two bodies (ADR-0032).
// Expected: V = 2 * 1000 = 2000, 2 solids, 12 faces. Needs PlaneGCS and OCCT.
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
    product("SKETCH-005") {
        name = "Sketch with two islands"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SKETCH-005-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SKETCH-005-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape =
    sketch {
        rectangle(0.0, 0.0, 10.0, 10.0)
        rectangle(20.0, 0.0, 10.0, 10.0)
    }.solve().extrude(10.0)

stepFile(fileName = "sketch-two-islands.step") {
    root(definition)
    shape(definition, shape)
}
