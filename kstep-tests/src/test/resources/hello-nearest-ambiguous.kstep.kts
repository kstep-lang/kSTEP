// hello-nearest-ambiguous.kstep.kts -- nearest(...) ties two edges and expected = 1 turns the tie into an error.
// Run: kstep export hello-nearest-ambiguous.kstep.kts  ->  KSTEP-S-011, two candidate edges (Welle Q, ADR-0037).
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
    product("NEAR-AMB") {
        name = "Ambiguous nearest edge"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("NEAR-AMB-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("NEAR-AMB-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape = OcctKernel.fillet(OcctKernel.makeBox(10.0, 20.0, 30.0), Edges.nearest(100.0, 10.0, 15.0), 2.0, expected = 1)

stepFile(fileName = "nearest-ambiguous.step") {
    root(definition)
    shape(definition, shape)
}
