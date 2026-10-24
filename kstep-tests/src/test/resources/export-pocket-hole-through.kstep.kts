// export-pocket-hole-through.kstep.kts -- through hole d = 8 in a 40x30x20 box (Welle Q2, ADR-0040).
// Expected: volume 24000 - pi*16*20 = 22994.690351, 7 faces, 1 solid.
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
    product("POCKET-HOLE-THROUGH") {
        name = "Box with a through hole"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("POCKET-HOLE-THROUGH-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("POCKET-HOLE-THROUGH-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(40.0, 30.0, 20.0)
val shape = pocket(box, Faces.atMax(Direction3.Z), PocketProfile.Circle(8.0), PocketDepth.Through)

stepFile(fileName = "pocket-hole-through.step") {
    root(definition)
    shape(definition, shape)
}
