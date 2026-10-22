// export-sketch-feature-rebuild.kstep.kts -- a parametric sketch step in a feature history (ADR-0032).
// The plate is first 40 wide, then rebuilt with width = 60. Expected for the rebuilt shape: V = 60*20*10 - 90*pi
// = 11717.257, 7 faces. Needs PlaneGCS and OCCT.
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence

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
    product("SKETCH-007") {
        name = "Parametric sketch feature"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("SKETCH-007-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("SKETCH-007-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val plate =
    sketchFeature(mapOf("width" to 40.0), SketchOperation.Extrude(10.0)) { p ->
        val frame = rectangle(0.0, 0.0, p["width"], 20.0)
        val centre = point(20.0, 10.0)
        offset(frame.bottomLeft, centre, dx = 20.0, dy = 10.0)
        circle(centre, radius = 3.0)
    }
val shape = FeatureRebuilder.rebuild(FeatureSequence(listOf(plate.withParameter("width", 60.0)))).final

stepFile(fileName = "sketch-feature-rebuild.step") {
    root(definition)
    shape(definition, shape)
}
