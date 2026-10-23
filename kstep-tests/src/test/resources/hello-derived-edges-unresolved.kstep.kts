// hello-derived-edges-unresolved.kstep.kts -- negative case (ADR-0034): a blind hole has no exit ring, so
// RIM_EXIT of its HoleWall names nothing; the failure is KSTEP-S-011 with the generated candidates, never a guess.
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.HoleFeature
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
    product("NAMING-N2-004") {
        name = "Unresolved derived edge"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("NAMING-N2-004-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("NAMING-N2-004-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val features =
    FeatureSequence(
        listOf(
            BoxFeature(40.0, 40.0, 10.0),
            HoleFeature(0, 8.0, 6.0, 20.0, 20.0, 11.0),
            SelectedFilletFeature(
                1,
                GeneratedEdges(1, listOf(HoleWall), GeneratedRole.RIM_EXIT),
                1.0,
            ),
        ),
    )
val shape = FeatureRebuilder.rebuild(features).final

stepFile(fileName = "hello-derived-edges-unresolved.step") {
    root(definition)
    shape(definition, shape)
}
