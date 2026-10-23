// export-two-bodies-one-part.kstep.kts -- Two separate bodies bound to ONE product definition (Welle P, ADR-0036):
// a box and its mirror image in the plane x = -5, combined with OcctKernel.compound.
// Expected: one SHAPE_REPRESENTATION with 2 MANIFOLD_SOLID_BREPs, total volume 12000.
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
    product("TWO-BODIES") {
        name = "Two bodies in one part"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("TWO-BODIES-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("TWO-BODIES-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val body = OcctKernel.makeBox(10.0, 20.0, 30.0)
val shape = OcctKernel.compound(listOf(body, mirror(body, MirrorPlane.x(-5.0))))

stepFile(fileName = "two-bodies-one-part.step") {
    root(definition)
    shape(definition, shape)
}
