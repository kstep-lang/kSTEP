// export-sketch-radius-unknown.kstep.kts -- the same plate, but hole 1 is set by radius(...) (ADR-0032).
// Expected: V = 7434.513, 8 faces. Needs PlaneGCS and OCCT.
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
    product("SKETCH-003") {
        name = "Sketch plate with radius unknowns"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SKETCH-003-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SKETCH-003-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape =
    sketch {
        val frame = rectangle(0.0, 0.0, 40.0, 20.0)
        val a = point(10.0, 10.0)
        val b = point(30.0, 10.0)
        offset(frame.bottomLeft, a, dx = 10.0, dy = 10.0)
        offset(frame.bottomLeft, b, dx = 30.0, dy = 10.0)
        val c1 = circle(a, radius = 2.0, fixedRadius = false)
        val c2 = circle(b, radius = 4.0, fixedRadius = false)
        radius(c1, 3.0)
        equalRadius(c2, c1)
    }.solve().extrude(10.0)

stepFile(fileName = "sketch-radius-unknown.step") {
    root(definition)
    shape(definition, shape)
}
