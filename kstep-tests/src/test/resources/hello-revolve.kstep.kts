// hello-revolve.kstep.kts -- a cone (right triangle revolved about the Y axis), Welle J.
// Run: kstep render hello-revolve.kstep.kts --format svg. Expected volume 2000*pi/3 = 2094.395.
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
    product("CONE-001") {
        name = "Cone"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("CONE-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("CONE-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape =
    OcctKernel.revolveProfile(
        listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 20.0)),
    )

stepFile(fileName = "hello-revolve.step") {
    root(definition)
    shape(definition, shape)
}
