// hello-shell-invalid.kstep.kts -- a 12 mm wall on a box whose smallest extent is 20 (Welle Q, ADR-0037).
// Run: kstep export hello-shell-invalid.kstep.kts  ->  KSTEP-S-003, "thickness 12.0 must be less than half the smallest extent".
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
    product("SHELL-BAD") {
        name = "Shell that is too thick"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SHELL-BAD-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SHELL-BAD-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = shell(OcctKernel.makeBox(40.0, 30.0, 20.0), Faces.atMax(Direction3.Z), 12.0)

stepFile(fileName = "shell-invalid.step") {
    root(definition)
    shape(definition, shape)
}
