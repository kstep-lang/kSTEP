// hello-scale-invalid.kstep.kts -- A scale factor of 0 is rejected before any native call (Welle P, ADR-0036).
// Run: kstep export hello-scale-invalid.kstep.kts   ->  runtime error "scale factor must not be 0 ..."
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
    product("SCALE-INVALID") {
        name = "Invalid scale"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SCALE-INVALID-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SCALE-INVALID-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = scale(OcctKernel.makeBox(10.0, 20.0, 30.0), 0.0)

stepFile(fileName = "scale-invalid.step") {
    root(definition)
    shape(definition, shape)
}
