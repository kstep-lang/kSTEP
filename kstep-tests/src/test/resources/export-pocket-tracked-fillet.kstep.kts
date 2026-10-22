// export-pocket-tracked-fillet.kstep.kts -- 40x40x10 block with a 10x10 through-pocket, outer edges rounded (ADR-0031).
// Expected: volume 16000 - 1000 - 4*(1 - pi/4)*2^2*10 = 14965.664, 14 faces.
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TrackedEdges
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
    product("POCK-001") {
        name = "Pocketed block with rounded outer edges"
        frameOfReference = setOf(prodCtx)
    }.getOrThrow()
val prodFormation = productDefinitionFormation("POCK-001-F") { ofProduct = part }.getOrThrow()
val definition =
    productDefinition("POCK-001-D") {
        formation = prodFormation
        frameOfReference = defCtx
    }.getOrThrow()

// A through-pocket makes eight edges parallel to Z, so a plain selector is ambiguous. TrackedEdges picks
// the four edges of the ORIGINAL block (feature 0) and follows them through the cut by OCCT history.
val features =
    FeatureSequence(
        listOf(
            BoxFeature(40.0, 40.0, 10.0),
            BoxFeature(10.0, 10.0, 20.0),
            TranslateFeature(1, 15.0, 15.0, -5.0),
            BooleanFeature(BooleanOperation.CUT, 0, 2),
            SelectedFilletFeature(3, TrackedEdges(0, Edges.parallelTo(Direction3.Z), expected = 4), 2.0),
        ),
    )
// Intermediate shapes are released by the OcctShape Cleaner; only the final shape is registered.
val shape = FeatureRebuilder.rebuild(features).final

stepFile(fileName = "pocket-fillet.step") {
    root(definition)
    shape(definition, shape)
}
