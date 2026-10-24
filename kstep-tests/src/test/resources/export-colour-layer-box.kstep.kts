// export-colour-layer-box.kstep.kts -- a 10x20x30 box whose whole part is orange (1.0, 0.5, 0.0) on layer "Layer-A" (Welle S, ADR-0039).
// Expected: volume 6000; `kstep import` and dump_xcaf_tree styles report the colour on 1e-6 and the layer "Layer-A".
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

val box = part("COL-BOX", "Coloured box")

stepFile(fileName = "colour-layer-box.step") {
    root(box)
    shape(box, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.5, 0.0), layer = "Layer-A")
}
