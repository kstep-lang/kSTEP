// export-edge-colour-top.kstep.kts -- 10x20x30 box with the four top edges coloured red (ADR-0042); no part colour.
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

val box = part("EDGE-001", "Box with red top edges")

stepFile(fileName = "edge-colour-top.step", timestamp = "2026-01-01T00:00:00") {
    root(box)
    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0))
    styleEdges(box, Edges.atMax(Direction3.Z), StepColours.RED, expected = 4)
}
