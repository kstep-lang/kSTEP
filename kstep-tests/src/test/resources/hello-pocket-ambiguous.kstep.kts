// hello-pocket-ambiguous.kstep.kts -- a pocket whose face selector matches two faces (Welle Q2, ADR-0040).
// Run: kstep export hello-pocket-ambiguous.kstep.kts  ->  KSTEP-S-011 with the candidate faces; a pocket never picks one.
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
    product("POCKET-AMB") {
        name = "Ambiguous pocket"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("POCKET-AMB-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("POCKET-AMB-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(40.0, 30.0, 20.0)
val shape = pocket(box, Faces.normal(Direction3.X) or Faces.normal(Direction3.NEG_X), PocketProfile.Circle(4.0), 3.0)

stepFile(fileName = "pocket-ambiguous.step") {
    root(definition)
    shape(definition, shape)
}
