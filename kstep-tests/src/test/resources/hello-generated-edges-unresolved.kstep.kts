// hello-generated-edges-unresolved.kstep.kts -- negative case (ADR-0033): the profile vertex (0, 0) lies on the
// revolve axis, so it generates no usable edge; the failure is KSTEP-S-011 with the candidate edges listed.
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.RevolveFeature
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
    product("NAMING-003") {
        name = "Unresolved generated edge"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("NAMING-003-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("NAMING-003-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val triangle = listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 10.0))
val features =
    FeatureSequence(
        listOf(
            RevolveFeature(triangle, 360.0, RevolveAxis.Y),
            SelectedFilletFeature(0, GeneratedEdges(0, GenerationSources.profileVertices(3)), 1.0),
        ),
    )
val shape = FeatureRebuilder.rebuild(features).final

stepFile(fileName = "never-written.step") {
    root(definition)
    shape(definition, shape)
}
