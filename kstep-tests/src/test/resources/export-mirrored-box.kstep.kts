// export-mirrored-box.kstep.kts -- A 10x20x30 box mirrored in the plane x = 0 (Welle P, ADR-0036).
// Expected: volume 6000, bounding box x in [-10, 0], y in [0, 20], z in [0, 30], 6 faces.
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
    product("MIRROR-BOX") {
        name = "Mirrored box"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("MIRROR-BOX-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("MIRROR-BOX-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val box = OcctKernel.makeBox(10.0, 20.0, 30.0)
val shape = mirror(box, MirrorPlane.YZ)

stepFile(fileName = "mirrored-box.step") {
    root(definition)
    shape(definition, shape)
}
