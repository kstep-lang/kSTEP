// export-shell-box.kstep.kts -- 40x30x20 box, top face open, 2 mm wall inward (Welle Q, ADR-0037).
// Expected: volume 40*30*20 - 36*26*18 = 7152, 11 faces (5 outer + 5 inner + rim), 1 solid, box x 0..40, y 0..30, z 0..20.
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
    product("SHELL-BOX") {
        name = "Hollowed box, top open"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SHELL-BOX-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SHELL-BOX-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = shell(OcctKernel.makeBox(40.0, 30.0, 20.0), Faces.atMax(Direction3.Z), 2.0)

stepFile(fileName = "shell-box.step") {
    root(definition)
    shape(definition, shape)
}
