// export-colour-face-top.kstep.kts -- a 10x20xH box (H = 30; the rebuild test uses 40) whose TOP face is blue (0, 0, 1) on layer "Layer-F" (Welle S, ADR-0039).
// The face is chosen by what it is (Faces.atMax(Direction3.Z)), not by an index, so it stays the top face when H changes.
// Expected: volume 10*20*H, exactly one coloured face with normal +Z and area 200.
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
val box = part("COL-FACE", "Box with coloured top face")

stepFile(fileName = "colour-face-top.step") {
    root(box)
    shape(box, OcctKernel.makeBox(10.0, 20.0, height))
    styleFaces(
        box,
        Faces.atMax(Direction3.Z),
        colour = StepColour.rgb(0.0, 0.0, 1.0),
        layer = "Layer-F",
        expected = 1,
    )
}
