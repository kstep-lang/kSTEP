// export-revolved-sleeve.kstep.kts -- a sleeve made by revolving the rectangle x in [5, 10],
// y in [0, 20] a full 360 degrees about the Y axis (Welle J, ADR-0029).
// Expected: volume pi*(10^2 - 5^2)*20 = 1500*pi = 4712.389, 4 faces.
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
    product("SLEEVE-001") {
        name = "Revolved sleeve"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SLEEVE-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SLEEVE-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape =
    OcctKernel.revolveProfile(
        listOf(ProfilePoint(5.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(10.0, 20.0), ProfilePoint(5.0, 20.0)),
        angleDegrees = 360.0,
        axis = RevolveAxis.Y,
    )

stepFile(fileName = "revolved-sleeve.step") {
    root(definition)
    shape(definition, shape)
}
