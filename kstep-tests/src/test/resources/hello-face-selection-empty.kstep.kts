// hello-face-selection-empty.kstep.kts -- the face selector matches no face (Welle Q, ADR-0037).
// Run: kstep export hello-face-selection-empty.kstep.kts  ->  KSTEP-S-011 with the candidate faces.
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
    product("FACE-NONE") {
        name = "Face selection that matches nothing"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("FACE-NONE-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("FACE-NONE-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = shell(OcctKernel.makeBox(40.0, 30.0, 20.0), Faces.normal(Direction3(0.3, 1.0, 0.7)), 2.0)

stepFile(fileName = "face-selection-empty.step") {
    root(definition)
    shape(definition, shape)
}
