// export-hole-rim-fillet.kstep.kts -- the entry rim of a hole is rounded by NAME (ADR-0034).
// Plate 40x40x10, through hole d = 8 from above (start z = 11), rim fillet r = 1 on RIM_ENTRY of the drill's
// HoleWall. Hand value: 16000 - 160 pi - 5.694718 = 15491.650, 8 faces.
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
    product("NAMING-N2-001") {
        name = "Hole rim fillet by name"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("NAMING-N2-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("NAMING-N2-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val features =
    FeatureSequence(
        listOf(
            BoxFeature(40.0, 40.0, 10.0),
            HoleFeature(0, 8.0, 12.0, 20.0, 20.0, 11.0),
            SelectedFilletFeature(
                1,
                GeneratedEdges(1, listOf(HoleWall), GeneratedRole.RIM_ENTRY, expected = 1),
                1.0,
            ),
        ),
    )
val shape = FeatureRebuilder.rebuild(features).final

stepFile(fileName = "export-hole-rim-fillet.step") {
    root(definition)
    shape(definition, shape)
}
