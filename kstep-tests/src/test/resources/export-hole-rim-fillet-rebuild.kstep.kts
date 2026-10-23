// export-hole-rim-fillet-rebuild.kstep.kts -- same names after a parameter change (ADR-0034).
// The plate becomes 60x40x10 and the hole follows its centre; the name still hits the entry ring.
// Hand value: 24000 - 160 pi - 5.694718 = 23491.650, 8 faces.
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
    product("NAMING-N2-002") {
        name = "Hole rim fillet by name after rebuild"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("NAMING-N2-002-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("NAMING-N2-002-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val features =
    FeatureSequence(
        listOf(
            BoxFeature(60.0, 40.0, 10.0),
            HoleFeature(0, 8.0, 12.0, 30.0, 20.0, 11.0),
            SelectedFilletFeature(
                1,
                GeneratedEdges(1, listOf(HoleWall), GeneratedRole.RIM_ENTRY, expected = 1),
                1.0,
            ),
        ),
    )
val shape = FeatureRebuilder.rebuild(features).final

stepFile(fileName = "export-hole-rim-fillet-rebuild.step") {
    root(definition)
    shape(definition, shape)
}
