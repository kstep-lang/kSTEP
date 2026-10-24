// export-thicken-face.kstep.kts -- the top face of a 40x30x20 box pushed 2 mm outward (Welle Q2, ADR-0040).
// Expected: volume 40*30*22 = 26400, 6 faces, 1 solid, box z 0..22.
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
    product("THICKEN-FACE") {
        name = "Box with a thickened top"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("THICKEN-FACE-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("THICKEN-FACE-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(40.0, 30.0, 20.0)
val shape = thicken(box, Faces.atMax(Direction3.Z), 2.0)

stepFile(fileName = "thicken-face.step") {
    root(definition)
    shape(definition, shape)
}
