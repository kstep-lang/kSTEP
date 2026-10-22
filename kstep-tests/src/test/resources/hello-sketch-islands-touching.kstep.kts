// hello-sketch-islands-touching.kstep.kts -- negative case (ADR-0032): two squares share the edge x = 10, so the
// contours touch and the profile is rejected with KSTEP-S-010 (INVALID_PROFILE). Needs PlaneGCS (solving).
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
    product("SKETCH-006") {
        name = "Touching islands"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SKETCH-006-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SKETCH-006-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape =
    sketch {
        rectangle(0.0, 0.0, 10.0, 10.0)
        rectangle(10.0, 0.0, 10.0, 10.0)
    }.solve().extrude(10.0)

stepFile(fileName = "never-written.step") {
    root(definition)
    shape(definition, shape)
}
