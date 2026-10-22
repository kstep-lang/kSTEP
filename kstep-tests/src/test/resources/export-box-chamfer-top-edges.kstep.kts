// export-box-chamfer-top-edges.kstep.kts -- 40x40x10 box, top edges chamfered by 1 (ADR-0031).
// Expected: the top 1 mm slab becomes a frustum, V = 16000 - (1600 - (1600 + 1444 + 1520)/3) = 15921.333, 10 faces.
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
    product("BOXC-001") {
        name = "Box with chamfered top edges"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("BOXC-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("BOXC-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// The four edges of the top face, selected through the face (the face with the highest centroid along Z).
val blank = OcctKernel.makeBox(40.0, 40.0, 10.0)
val shape = OcctKernel.chamfer(blank, Edges.ofFace(Faces.atMax(Direction3.Z)), 1.0, expected = 4)

stepFile(fileName = "box-chamfer.step") {
    root(definition)
    shape(definition, shape)
}
