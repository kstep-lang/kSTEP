// export-docs-alpha-edges.kstep.kts -- the example of docs/usage.adoc [[alpha-edge-colours]], verbatim, in a minimal shell (contexts, a part helper). CliExportOcctAlphaEdgeIntegrationTest checks that the block below equals the one in the docs.
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

val block = part("BLOCK", "Glass block")
val body = OcctKernel.makeBox(10.0, 20.0, 30.0)

stepFile(fileName = "glass-block.step") {
    root(block)
    shape(block, body, colour = StepColour.rgba(0.2, 0.6, 1.0, 0.4))                                 // a translucent part
    styleFaces(block, Faces.atMax(Direction3.Z), colour = StepColour.hex("#FF8000C0"), expected = 1)  // an opaque-ish top face
    styleEdges(block, Edges.atMax(Direction3.Z), StepColours.RED, expected = 4)                     // the four top edges
}
