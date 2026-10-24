// export-colour-assembly-part-face.kstep.kts -- placed two-part assembly; only RED carries a face colour (blue top face) on top of its part colour (ADR-0039 S2).
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

stepFile(fileName = "colour-assembly-part-face.step") {
    roots(nauoRed, nauoGreen)
    shape(red, OcctKernel.makeBox(10.0, 20.0, 30.0), colour = StepColour.rgb(1.0, 0.0, 0.0))
    styleFaces(red, Faces.atMax(Direction3.Z), colour = StepColour.rgb(0.0, 0.0, 1.0), expected = 1)
    shape(green, OcctKernel.makeBox(10.0, 10.0, 10.0), colour = StepColour.rgb(0.0, 0.6, 0.0))
    place(nauoGreen, translation = vec(30.0, 0.0, 0.0))
}
