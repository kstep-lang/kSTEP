// export-sketch-equal-radius-holes.kstep.kts -- two holes whose radius is a solver unknown (ADR-0032).
// Hole 1 is dimensioned by its diameter (6), hole 2 only by equalRadius. Expected: V = 8000 - 2*90*pi = 7434.513,
// 8 faces (4 sides, top, bottom, two cylinders). Needs PlaneGCS and OCCT.
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
    product("SKETCH-002") {
        name = "Sketch plate with two equal holes"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SKETCH-002-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SKETCH-002-D") {
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
        val c1 = circle(a, radius = 5.0, fixedRadius = false)
        val c2 = circle(b, radius = 5.0, fixedRadius = false)
        diameter(c1, 6.0)
        equalRadius(c2, c1)
    }.solve().extrude(10.0)

stepFile(fileName = "sketch-equal-radius.step") {
    root(definition)
    shape(definition, shape)
}
