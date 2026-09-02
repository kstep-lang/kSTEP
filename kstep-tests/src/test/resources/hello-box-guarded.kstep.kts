// hello-box-guarded.kstep.kts -- the "portable script" pattern ADR-0011 recommends: only
// registers geometry when OcctKernel.availability() actually reports Available, so this script
// produces a clean product-structure-only KStepModel (content = summary, not notice) on a
// machine without the OCCT native bridge, instead of throwing OcctUnavailableException.

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
    product("GBOX-001") {
        name = "Guarded Box"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("GBOX-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("GBOX-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

stepFile(fileName = "hello-box-guarded.step") {
    root(definition)
    if (OcctKernel.availability() is dev.kstep.geometry.OcctAvailability.Available) {
        shape(definition, OcctKernel.makeBox(5.0, 5.0, 5.0))
    }
}
