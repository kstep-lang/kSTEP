// export-sketch-tangent-circle.kstep.kts -- a circle tangent to a construction line (ADR-0032).
// The line y = 4 and the centre (20, 10) are dimensioned from the corner, so r = 6 follows from the tangent.
// Expected: V = 8000 - 360*pi = 6869.027, 7 faces. Needs PlaneGCS and OCCT.
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
    product("SKETCH-004") {
        name = "Sketch plate with tangent hole"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SKETCH-004-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SKETCH-004-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val shape =
    sketch {
        val frame = rectangle(0.0, 0.0, 40.0, 20.0)
        val a = point(5.0, 4.0)
        val b = point(35.0, 4.0)
        offset(frame.bottomLeft, a, dx = 5.0, dy = 4.0)
        offset(frame.bottomLeft, b, dx = 35.0, dy = 4.0)
        val guide = line(a, b, construction = true)
        val centre = point(20.0, 10.0)
        offset(frame.bottomLeft, centre, dx = 20.0, dy = 10.0)
        val hole = circle(centre, radius = 5.0, fixedRadius = false)
        tangent(guide, hole)
    }.solve().extrude(10.0)

stepFile(fileName = "sketch-tangent-circle.step") {
    root(definition)
    shape(definition, shape)
}
