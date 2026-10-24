// export-pocket-hole-blind.kstep.kts -- blind hole d = 8, 6 deep, in the top of a 40x30x20 box (Welle Q2, ADR-0040).
// Expected: volume 24000 - pi*16*6 = 23698.407105, 8 faces, 1 solid.
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
    product("POCKET-HOLE-BLIND") {
        name = "Box with a blind hole"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("POCKET-HOLE-BLIND-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("POCKET-HOLE-BLIND-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(40.0, 30.0, 20.0)
val shape = pocket(box, Faces.atMax(Direction3.Z), PocketProfile.Circle(8.0), 6.0)

stepFile(fileName = "pocket-hole-blind.step") {
    root(definition)
    shape(definition, shape)
}
