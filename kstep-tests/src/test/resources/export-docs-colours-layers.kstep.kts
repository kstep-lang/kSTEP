// export-docs-colours-layers.kstep.kts -- the example of docs/usage.adoc [[colours-layers]], verbatim, in a minimal shell (contexts, part "bracket", a body). CliExportOcctColoursLayersIntegrationTest checks that the block below equals the one in the docs.
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

fun part(id: String, name: String): ProductDefinition {
    val p = product(id) { this.name = name; frameOfReference = setOf(prodCtx) }.getOrThrow()
    val f = productDefinitionFormation("$id-F") { ofProduct = p }.getOrThrow()
    return productDefinition("$id-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
}

val bracket = part("BRACKET", "Bracket")
val body = OcctKernel.makeBox(10.0, 20.0, 30.0)

stepFile(fileName = "bracket.step") {
    root(bracket)
    shape(bracket, body, colour = StepColour.rgb(1.0, 0.5, 0.0), layer = "Housing")   // the whole part
    styleFaces(bracket, Faces.atMax(Direction3.Z), colour = StepColours.BLUE, layer = "Top", expected = 1)
}
