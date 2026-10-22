// export-box-fillet-z-edges.kstep.kts -- 40x40x10 box, the four vertical edges rounded with r = 2 (ADR-0031).
// Expected: volume 16000 - 4*(1 - pi/4)*2^2*10 = 15965.664, 10 faces.
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
    product("BOXF-001") {
        name = "Box with rounded vertical edges"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOXF-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOXF-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// Edges chosen by geometry, not by index: the four edges parallel to Z. `expected = 4` makes a
// wrong match an error instead of a silently different part.
val blank = OcctKernel.makeBox(40.0, 40.0, 10.0)
val shape = OcctKernel.fillet(blank, Edges.parallelTo(Direction3.Z), 2.0, expected = 4)

stepFile(fileName = "box-fillet.step") {
    root(definition)
    shape(definition, shape)
}
