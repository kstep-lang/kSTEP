// export-two-bodies-different.kstep.kts -- Two bodies of DIFFERENT size in one part (Welle R, ADR-0038), used to prove that body styles follow the solid order:
// a box 10x20x30 (6000) and a box 4x4x4 (64) mirrored in the plane x = -5, combined with OcctKernel.compound.
// Expected: one SHAPE_REPRESENTATION with 2 MANIFOLD_SOLID_BREPs, total volume 6064.
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
    product("TWO-DIFF") {
        name = "Two different bodies"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("TWO-DIFF-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("TWO-DIFF-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val body = OcctKernel.makeBox(10.0, 20.0, 30.0)
val small = OcctKernel.makeBox(4.0, 4.0, 4.0)
val shape = OcctKernel.compound(listOf(body, mirror(small, MirrorPlane.x(-5.0))))

stepFile(fileName = "two-bodies-different.step") {
    root(definition)
    shape(definition, shape)
}
