// export-union-section-fillet.kstep.kts -- the section edges of a union are rounded by NAME (ADR-0034).
// Two 20x20x10 boxes, the second moved by (10, 10, 0); fuse = 7000 (10 faces). The two concave vertical edges are
// BooleanSection/SECTION (expected = 2); rounding them at r = 2 adds 2 (1 - pi/4) 2^2 10 = 17.168 -> 7017.168, 12 faces.
import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TranslateFeature

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
    product("NAMING-N2-003") {
        name = "Union section fillet by name"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("NAMING-N2-003-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("NAMING-N2-003-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

val features =
    FeatureSequence(
        listOf(
            BoxFeature(20.0, 20.0, 10.0),
            BoxFeature(20.0, 20.0, 10.0),
            TranslateFeature(1, 10.0, 10.0, 0.0),
            BooleanFeature(BooleanOperation.UNION, 0, 2),
            SelectedFilletFeature(
                3,
                GeneratedEdges(3, listOf(BooleanSection), GeneratedRole.SECTION, expected = 2),
                2.0,
            ),
        ),
    )
val shape = FeatureRebuilder.rebuild(features).final

stepFile(fileName = "export-union-section-fillet.step") {
    root(definition)
    shape(definition, shape)
}
