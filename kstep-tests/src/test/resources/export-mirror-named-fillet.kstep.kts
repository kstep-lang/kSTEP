// export-mirror-named-fillet.kstep.kts -- A corner edge is named at its generator (extrude vertex 0 of the 10x20
// rectangle), the body is mirrored, and the fillet still finds the edge through the mirror step (Welle P, ADR-0036).
// Expected: volume 6000 - (4 - pi) * 30 = 5974.2478, bounding box x in [-10, 0].
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.MirrorFeature
import dev.kstep.geometry.feature.SelectedFilletFeature

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
    product("MIRROR-FILLET") {
        name = "Mirrored body with a named fillet"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("MIRROR-FILLET-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("MIRROR-FILLET-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val rect =
    listOf(
        ProfilePoint(0.0, 0.0),
        ProfilePoint(10.0, 0.0),
        ProfilePoint(10.0, 20.0),
        ProfilePoint(0.0, 20.0),
    )
val features =
    FeatureSequence(
        listOf(
            ExtrudeFeature(rect, 30.0),
            MirrorFeature(0),
            SelectedFilletFeature(1, GeneratedEdges(0, listOf(ProfileVertex(0)), expected = 1), 2.0),
        ),
    )
val shape = FeatureRebuilder.rebuild(features).final

stepFile(fileName = "mirror-named-fillet.step") {
    root(definition)
    shape(definition, shape)
}
