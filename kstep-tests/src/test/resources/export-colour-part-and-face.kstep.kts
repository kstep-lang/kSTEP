// export-colour-part-and-face.kstep.kts -- a 10x20xH box (H = 30) with an ORANGE part colour and a BLUE top face on layer "Layer-F" (ADR-0039 S2: OCCT writes the face as an OVER_RIDING_STYLED_ITEM).
// Expected: volume 10*20*H, part colour (1, 0.5, 0), exactly one coloured face with normal +Z and area 200.
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

val height = 30.0
val box = part("COL-PF", "Box with part and face colour")

stepFile(fileName = "colour-part-and-face.step") {
    root(box)
    shape(box, OcctKernel.makeBox(10.0, 20.0, height), colour = StepColour.rgb(1.0, 0.5, 0.0))
    styleFaces(
        box,
        Faces.atMax(Direction3.Z),
        colour = StepColour.rgb(0.0, 0.0, 1.0),
        layer = "Layer-F",
        expected = 1,
    )
}
