// export-sketch-generated-fillet-rebuild.kstep.kts -- the same history after width 40 -> 60 (ADR-0033).
// The same four logical edges are hit again: V = 12000 - 34.336 = 11965.664, 10 faces.
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.sketch.SketchSources

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
    product("NAMING-002") {
        name = "Sketch plate, widened, generated-edge fillet"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("NAMING-002-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("NAMING-002-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// The four corners are addressed by what generated the edges: the sketch points p0..p3 of the rectangle.
// A change of "width" moves the geometry but keeps the same four logical edges.
val plate =
    sketchFeature(mapOf("width" to 40.0), SketchOperation.Extrude(10.0), label = "plate") { p ->
        rectangle(0.0, 0.0, p["width"], 20.0)
    }
val features =
    FeatureSequence(
        listOf(
            plate.withParameter("width", 60.0),
            SelectedFilletFeature(0, GeneratedEdges(0, SketchSources.corners("p0", "p1", "p2", "p3"), expected = 4), 2.0),
        ),
    )
val shape = FeatureRebuilder.rebuild(features).final

stepFile(fileName = "sketch-generated-fillet-rebuild.step") {
    root(definition)
    shape(definition, shape)
}
