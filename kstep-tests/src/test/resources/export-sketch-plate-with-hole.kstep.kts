// export-sketch-plate-with-hole.kstep.kts -- sketch-driven plate with a hole (ADR-0031).
// Expected: volume 40*20*10 - pi*3^2*10 = 8000 - 90*pi = 7717.257, 7 faces (incl. one CYLINDRICAL_SURFACE).
// The sketch solves through the native PlaneGCS bridge and the extrusion needs OCCT.
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
    product("SKETCH-001") {
        name = "Sketch plate with hole"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SKETCH-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SKETCH-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// A constraint sketch: 40 x 20 rectangle (origin corner fixed, two dimensions) and a hole of
// diameter 6 whose centre is dimensioned 20 / 10 from that corner. The start value of every point
// tells the solver which side its dimension lies on.
val solved =
    sketch {
        val frame = rectangle(0.0, 0.0, 40.0, 20.0)
        val centre = point(20.0, 10.0)
        offset(frame.bottomLeft, centre, dx = 20.0, dy = 10.0)
        circle(centre, radius = 3.0)
    }.solve()
val shape = solved.extrude(10.0)

stepFile(fileName = "sketch-plate.step") {
    root(definition)
    shape(definition, shape)
}
