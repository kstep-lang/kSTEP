// export-colour-assembly-two-parts.kstep.kts -- a placed assembly of a red and a green box (Welle S, ADR-0039).
// Expected: two solids, volumes 6000 and 1000, bounding box [0,40] x [0,20] x [0,30]; the red part sits at the origin, the green one is moved by (30, 0, 0); colours survive the export.
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

val assembly = part("ASM", "Coloured assembly")
val red = part("RED", "Red part")
val green = part("GREEN", "Green part")
val nauoRed = nextAssemblyUsageOccurrence("NAUO-R") {
    name = "red"; relatingProductDefinition = assembly; relatedProductDefinition = red
}.getOrThrow()
val nauoGreen = nextAssemblyUsageOccurrence("NAUO-G") {
    name = "green"; relatingProductDefinition = assembly; relatedProductDefinition = green
}.getOrThrow()

stepFile(fileName = "colour-assembly-two-parts.step") {
    roots(nauoRed, nauoGreen)
    shape(red, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.0, 0.0), layer = "Red-Layer")
    shape(green, OcctKernel.makeBox(10.0, 10.0, 10.0), colour = StepColour.rgb(0.0, 0.6, 0.0))
    place(nauoGreen, translation = vec(30.0, 0.0, 0.0))
}
