// export-alpha-box.kstep.kts -- 10x20x30 box, part colour with alpha 0.5, top face with alpha 0.25 (ADR-0042).
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

val box = part("ALPHA-001", "Translucent box")

stepFile(fileName = "alpha-box.step", timestamp = "2026-01-01T00:00:00") {
    root(box)
    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgba(1.0, 0.5, 0.0, 0.5))
    styleFaces(box, Faces.atMax(Direction3.Z), colour = StepColour.rgba(0.0, 0.0, 1.0, 0.25), expected = 1)
}
