// export-chamfered-prism.kstep.kts -- a 20x30x40 prism with edge 0 chamfered by 2 (Welle J, ADR-0029).
// Edge 0 is one of the 40-long edges (edge indices are opaque, see OcctKernel.fillet's KDoc).
// Expected: volume 24000 - 2*2/2*40 = 23920, 7 faces.
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
    product("PRISM-001") {
        name = "Chamfered prism"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("PRISM-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("PRISM-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val prism =
    OcctKernel.extrudeProfile(
        listOf(ProfilePoint(0.0, 0.0), ProfilePoint(20.0, 0.0), ProfilePoint(20.0, 30.0), ProfilePoint(0.0, 30.0)),
        40.0,
    )
val shape = OcctKernel.chamfer(prism, 0, 2.0)

stepFile(fileName = "chamfered-prism.step") {
    root(definition)
    shape(definition, shape)
}
