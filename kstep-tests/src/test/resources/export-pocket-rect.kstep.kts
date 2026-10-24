// export-pocket-rect.kstep.kts -- 10x10 mm blind pocket, 5 mm deep, in the top of a 40x30x20 box (Welle Q2, ADR-0040).
// Expected: volume 40*30*20 - 10*10*5 = 23500, 11 faces, 1 solid, box x 0..40, y 0..30, z 0..20.
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
    product("POCKET-RECT") {
        name = "Box with a rectangular pocket"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("POCKET-RECT-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("POCKET-RECT-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(40.0, 30.0, 20.0)
val shape = pocket(box, Faces.atMax(Direction3.Z), PocketProfile.Rectangle(10.0, 10.0), 5.0)

stepFile(fileName = "pocket-rect.step") {
    root(definition)
    shape(definition, shape)
}
